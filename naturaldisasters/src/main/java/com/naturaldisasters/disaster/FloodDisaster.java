package com.naturaldisasters.disaster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Heavy rain and rising water that climbs one layer at a time over a disc around the center, flooding valleys
 * and low ground (hills stay dry), then recedes. Per environment:
 * <ul>
 *   <li>Desert / mountains: a Flash Flood. Rises very fast, short, with a strong current that sweeps you outward.</li>
 *   <li>Jungle / swamp: a Monsoon Flood. Bigger, and stays for a long time.</li>
 *   <li>Snow: a Meltwater Flood. The water is freezing; swimming in it builds up frostbite.</li>
 * </ul>
 */
public class FloodDisaster extends Disaster {
	private static final int COLUMNS_PER_TICK = 1500;

	private record Column(int x, int z, int base) {
	}

	private final int radius;
	private final int maxRise;
	private final int layerInterval;
	private final int holdTicks;
	private int datumY;
	private final List<Column> columns = new ArrayList<>();
	private final Deque<List<BlockPos>> layers = new ArrayDeque<>();

	private int currentRise = 0;
	private int cursor = 0;
	private List<BlockPos> building;

	public FloodDisaster(ServerLevel level, Vec3 center, Severity severity, int warnTicks) {
		super(DisasterType.FLOOD, severity, level, center, warnTicks);
		boolean flash = env == Environment.ARID || env == Environment.MOUNTAIN;
		this.radius = (int) ((20 + 11 * severity.level) * (env == Environment.WET ? 1.4 : 1.0));
		this.maxRise = 1 + severity.level; // 2..5 blocks
		this.layerInterval = flash ? 30 : 90;
		this.holdTicks = 20 * (flash ? 6 + 4 * severity.level : env == Environment.WET ? 40 + 20 * severity.level
				: 20 + 15 * severity.level);
	}

	@Override
	public String describe() {
		String name = switch (env) {
			case ARID, MOUNTAIN -> "Flash Flood";
			case WET -> "Monsoon Flood";
			case SNOWY -> "Meltwater Flood";
			default -> "Flood";
		};
		return severity.label + " " + name;
	}

	@Override
	public double dangerRadius() {
		return radius * 1.5;
	}

	@Override
	protected int activeTicks() {
		return layerInterval * maxRise * 2 + holdTicks;
	}

	@Override
	protected void tickWarning(int age) {
		// Rain is the first sign.
		if (age == 1) {
			Weather.set(level, "rain", ticksToImpact() / 20 + activeTicks() / 20);
		}
	}

	@Override
	protected void onStart() {
		int cx = (int) Math.floor(pos.x);
		int cz = (int) Math.floor(pos.z);
		datumY = DisasterUtil.surfaceY(level, cx, cz) - 1;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radius * radius) {
					continue;
				}
				int x = cx + dx;
				int z = cz + dz;
				if (DisasterUtil.loaded(level, x, z)) {
					columns.add(new Column(x, z, DisasterUtil.surfaceY(level, x, z)));
				}
			}
		}
		Warnings.broadcastImpact(this, "Floodwaters are rising!");
	}

	@Override
	protected void tickActive(int t) {
		int riseTicks = layerInterval * maxRise;
		int holdEnd = riseTicks + holdTicks;

		if (t < riseTicks) {
			advanceBuilding(t);
		} else if (t >= holdEnd && !layers.isEmpty() && (t - holdEnd) % layerInterval == 0) {
			removeTopLayer();
		}
		if (t % 4 == 0 && !layers.isEmpty()) {
			waterEffects(t);
		}
	}

	/** Currents and cold water. */
	private void waterEffects(int t) {
		double reach = radius + 4;
		AABB box = new AABB(pos.x - reach, datumY - 4, pos.z - reach, pos.x + reach, datumY + maxRise + 6, pos.z + reach);
		boolean flash = env == Environment.ARID || env == Environment.MOUNTAIN;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (!e.isInWater() || !DisasterUtil.isVulnerable(e)) {
				continue;
			}
			if (flash) {
				double dx = e.getX() - pos.x;
				double dz = e.getZ() - pos.z;
				double d = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
				double force = 0.05 + 0.02 * severity.level;
				DisasterUtil.push(e, dx / d * force, 0.0, dz / d * force);
			}
			if (env == Environment.SNOWY) {
				DisasterUtil.freeze(e, 12);
			}
			if (t % 20 == 0) {
				DisasterUtil.effect(level, e, "slowness", 2, severity.level >= 3 ? 1 : 0);
			}
		}
	}

	private void advanceBuilding(int t) {
		if (building == null) {
			if (t % layerInterval != 0 || currentRise >= maxRise) {
				return;
			}
			currentRise++;
			cursor = 0;
			building = new ArrayList<>();
		}
		int end = Math.min(columns.size(), cursor + COLUMNS_PER_TICK);
		int y = datumY + currentRise;
		for (; cursor < end; cursor++) {
			Column c = columns.get(cursor);
			if (c.base() > y) {
				continue; // this ground is above the water line: stays dry
			}
			BlockPos bp = new BlockPos(c.x(), y, c.z());
			BlockState state = level.getBlockState(bp);
			if (DisasterUtil.replaceable(state) && !state.is(Blocks.WATER)) {
				level.setBlock(bp, Blocks.WATER.defaultBlockState(), 2);
				building.add(bp);
			}
		}
		if (cursor >= columns.size()) {
			layers.addLast(building);
			building = null;
		}
	}

	private void removeTopLayer() {
		List<BlockPos> top = layers.pollLast();
		if (top == null) {
			return;
		}
		for (BlockPos bp : top) {
			if (level.getBlockState(bp).is(Blocks.WATER)) {
				level.setBlock(bp, Blocks.AIR.defaultBlockState(), 2);
			}
		}
	}

	@Override
	public void cleanup() {
		if (building != null) {
			layers.addLast(building);
			building = null;
		}
		while (!layers.isEmpty()) {
			removeTopLayer();
		}
	}

	@Override
	protected void onEnd() {
		Warnings.broadcastEnd(this);
	}
}
