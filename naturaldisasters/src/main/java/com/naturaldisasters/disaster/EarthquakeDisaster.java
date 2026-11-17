package com.naturaldisasters.disaster;

import com.naturaldisasters.registry.ModSounds;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A full earthquake sequence:
 * <ul>
 *   <li>Foreshocks: faint rumbles and small tremors during the warning phase.</li>
 *   <li>Main shock with a build-up, a peak and a fade-out. Shaking is strongest near the epicenter.</li>
 *   <li>Terrain-driven damage: roofs and ledges collapse, caves cave in, loose sand sinks into sinkholes,
 *       steep slopes slide (avalanches on snow), the ground cracks open.</li>
 *   <li>Aftermath: fires near lamps, torches and campfires, a possible ground rift or tsunami, and aftershocks.</li>
 * </ul>
 */
public class EarthquakeDisaster extends Disaster {
	private final double radius;
	private final double magnitude;
	private final boolean aftershock;
	private int sinkholes;
	private boolean cracksDone;

	public EarthquakeDisaster(ServerLevel level, Vec3 epicenter, Severity severity, int warnTicks, boolean aftershock) {
		super(DisasterType.EARTHQUAKE, severity, level, epicenter, warnTicks);
		this.aftershock = aftershock;
		this.radius = 40.0 + 40.0 * severity.level;
		// Richter-style number purely for display: 3.x minor ... 8.x extreme.
		double base = 3.0 + severity.level * 1.2 + random.nextDouble() * 0.9;
		this.magnitude = aftershock ? base * 0.85 : base;
	}

	public double magnitude() {
		return magnitude;
	}

	@Override
	public String describe() {
		String name = aftershock ? "Aftershock" : "Earthquake";
		return severity.label + " " + name + " (M" + String.format("%.1f", magnitude) + ")";
	}

	@Override
	public double dangerRadius() {
		return radius;
	}

	@Override
	protected int activeTicks() {
		int base = (8 + 4 * severity.level) * 20;
		return aftershock ? base / 2 : base;
	}

	// ------------------------------------------------------------------ foreshocks

	@Override
	protected void tickWarning(int age) {
		if (age % 70 == 0) {
			cue(ModSounds.RUMBLE, 0.8f, 0.9f);
		}
		// In the last 6 seconds the ground starts to tremble.
		if (ticksToImpact() < 120 && age % 10 == 0) {
			for (ServerPlayer p : level.players()) {
				if (p.position().distanceToSqr(pos) > radius * radius * 2.25 || !p.onGround()
						|| !DisasterUtil.isVulnerable(p)) {
					continue;
				}
				DisasterUtil.push(p, (random.nextDouble() - 0.5) * 0.04, 0.05, (random.nextDouble() - 0.5) * 0.04);
				DisasterUtil.dust(level, p.blockPosition().below(), level.getBlockState(p.blockPosition().below()), 3);
			}
		}
	}

	// ------------------------------------------------------------------ main shock

	@Override
	protected void onStart() {
		Warnings.broadcastImpact(this, aftershock ? "An aftershock hits!" : "The ground begins to shake!");
	}

	/** 0..1 strength of the shaking: builds up, holds, then dies down. */
	private double intensity(int t) {
		double p = t / (double) activeTicks();
		if (p < 0.2) {
			return p / 0.2;
		}
		if (p < 0.6) {
			return 1.0;
		}
		return Math.max(0.0, (1.0 - p) / 0.4);
	}

	@Override
	protected void tickActive(int t) {
		double inten = intensity(t);
		if (inten <= 0.02) {
			return;
		}
		AABB box = new AABB(pos.x - radius, level.getMinY(), pos.z - radius,
				pos.x + radius, level.getMaxY(), pos.z + radius);

		// Rumble.
		if (t % 16 == 0) {
			for (ServerPlayer p : level.players()) {
				if (p.position().distanceToSqr(pos) < radius * radius * 4) {
					level.playSound(null, p.getX(), p.getY(), p.getZ(), ModSounds.RUMBLE, SoundSource.AMBIENT,
							(float) ((1.0f + 0.4f * severity.level) * (0.4 + 0.6 * inten)),
							0.8f + random.nextFloat() * 0.3f);
				}
			}
		}

		shake(t, inten, box);
		playerEffects(t, inten);
		surfaceBreaking(inten);
		if (t % 4 == 0) {
			structureCollapse(inten);
		}
		if (t % 20 == 0 && severity.level >= Severity.MODERATE.level) {
			caveIns(inten);
		}
		if (t % 10 == 0 && severity.level >= Severity.MODERATE.level) {
			terrainEffects(inten);
		}
		if (!cracksDone && t > activeTicks() * 0.3 && severity.level >= Severity.STRONG.level) {
			cracksDone = true;
			groundCracks();
		}
		if (t % 6 == 0) {
			dustNearPlayers();
		}
	}

	/** S-wave sway plus P-wave jolts for everything standing on the ground. */
	private void shake(int t, double inten, AABB box) {
		if (t % 2 != 0) {
			return;
		}
		double power = (0.04 + 0.04 * severity.level) * inten;
		double sway = Math.sin(t * 0.8);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (!e.onGround() || !DisasterUtil.isVulnerable(e)) {
				continue;
			}
			double falloff = 1.0 - Math.min(1.0, e.position().distanceTo(pos) / (radius * 1.2));
			if (falloff <= 0) {
				continue;
			}
			double k = power * falloff;
			DisasterUtil.push(e,
					(random.nextDouble() - 0.5) * 2 * k + sway * k * 0.6,
					(0.10 + 0.03 * severity.level) * falloff * inten,
					(random.nextDouble() - 0.5) * 2 * k + Math.cos(t * 0.8) * k * 0.6);
		}
	}

	/** Players near the epicenter get dizzy and slowed - you cannot just run through it. */
	private void playerEffects(int t, double inten) {
		if (t % 40 != 0 || inten < 0.5) {
			return;
		}
		for (ServerPlayer p : level.players()) {
			double d = p.position().distanceTo(pos);
			if (d > radius) {
				continue;
			}
			int amp = severity.level >= Severity.STRONG.level ? 1 : 0;
			DisasterUtil.effect(level, p, "slowness", 3, amp);
			if (severity.level >= Severity.STRONG.level && d < radius * 0.7) {
				DisasterUtil.effect(level, p, "nausea", 5, 0);
			}
		}
	}

	private void surfaceBreaking(double inten) {
		int attempts = (int) Math.ceil(3 * severity.level * inten);
		float maxHardness = DisasterUtil.maxHardness(severity);
		for (int i = 0; i < attempts; i++) {
			double ang = random.nextDouble() * Math.PI * 2;
			double dist = Math.sqrt(random.nextDouble()) * radius;
			int x = (int) Math.floor(pos.x + Math.cos(ang) * dist);
			int z = (int) Math.floor(pos.z + Math.sin(ang) * dist);
			if (!DisasterUtil.loaded(level, x, z)) {
				continue;
			}
			int y = DisasterUtil.surfaceY(level, x, z) - 1 - random.nextInt(3);
			BlockPos bp = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(bp);
			if (DisasterUtil.destructible(level, bp, state, maxHardness)) {
				DisasterUtil.dust(level, bp, state, 6);
				level.destroyBlock(bp, false);
			}
		}
	}

	/** Roofs, ledges and overhangs (blocks with nothing under them) drop as falling blocks. */
	private void structureCollapse(double inten) {
		int attempts = (int) Math.ceil(2 * severity.level * inten);
		float limit = DisasterUtil.maxHardness(severity) + 0.6f;
		for (int i = 0; i < attempts; i++) {
			Vec3 p = samplePoint(0.7, 20);
			int x = (int) Math.floor(p.x);
			int z = (int) Math.floor(p.z);
			if (!DisasterUtil.loaded(level, x, z)) {
				continue;
			}
			BlockPos bp = new BlockPos(x, DisasterUtil.surfaceY(level, x, z) - 1, z);
			BlockState state = level.getBlockState(bp);
			if (DisasterUtil.destructible(level, bp, state, limit) && level.getBlockState(bp.below()).isAir()) {
				FallingBlockEntity.fall(level, bp, state);
			}
		}
	}

	/** Players underground get rocks dropped on their heads. */
	private void caveIns(double inten) {
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(pos) > radius * radius * 1.44 || !DisasterUtil.isVulnerable(p)) {
				continue;
			}
			if (p.getY() < DisasterUtil.surfaceY(level, p.getBlockX(), p.getBlockZ()) - 5
					&& random.nextDouble() < 0.5 * inten) {
				TerrainEffects.caveIn(level, p, severity);
			}
		}
	}

	/** Sinkholes in loose ground, landslides on slopes, avalanches on snow. */
	private void terrainEffects(double inten) {
		int attempts = (int) Math.ceil(severity.level * 1.5 * inten);
		for (int i = 0; i < attempts; i++) {
			Vec3 p = samplePoint(0.6, 24);
			int x = (int) Math.floor(p.x);
			int z = (int) Math.floor(p.z);
			if (!DisasterUtil.loaded(level, x, z)) {
				continue;
			}
			BlockState top = level.getBlockState(new BlockPos(x, DisasterUtil.surfaceY(level, x, z) - 1, z));
			if (TerrainEffects.isLoose(top) && sinkholes < severity.level * 2 && random.nextFloat() < 0.35f) {
				sinkholes++;
				TerrainEffects.sinkhole(level, x, z, severity);
			} else {
				TerrainEffects.landslide(level, x, z, severity);
			}
		}
	}

	private void groundCracks() {
		int count = severity.level - 1;
		for (int i = 0; i < count; i++) {
			Vec3 p = samplePoint(0.5, 30);
			double ang = random.nextDouble() * Math.PI * 2;
			TerrainEffects.crack(level, (int) p.x, (int) p.z, Math.cos(ang), Math.sin(ang),
					8 + 4 * severity.level, 3 + severity.level);
		}
	}

	private void dustNearPlayers() {
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(pos) > radius * radius) {
				continue;
			}
			BlockPos below = p.blockPosition().below();
			DisasterUtil.dust(level, below, level.getBlockState(below), 8);
		}
	}

	/** A random point near a player (preferred, so the damage happens where it is felt) or in the quake zone. */
	private Vec3 samplePoint(double nearPlayerChance, double spread) {
		List<ServerPlayer> players = level.players();
		if (!players.isEmpty() && random.nextDouble() < nearPlayerChance) {
			ServerPlayer p = players.get(random.nextInt(players.size()));
			if (p.position().distanceToSqr(pos) < radius * radius * 1.44) {
				return new Vec3(p.getX() + (random.nextDouble() - 0.5) * 2 * spread, p.getY(),
						p.getZ() + (random.nextDouble() - 0.5) * 2 * spread);
			}
		}
		double ang = random.nextDouble() * Math.PI * 2;
		double dist = Math.sqrt(random.nextDouble()) * radius;
		return new Vec3(pos.x + Math.cos(ang) * dist, pos.y, pos.z + Math.sin(ang) * dist);
	}

	// ------------------------------------------------------------------ aftermath

	@Override
	protected void onEnd() {
		Warnings.broadcastEnd(this);
		if (aftershock) {
			return;
		}
		boolean rift = severity == Severity.EXTREME || (severity == Severity.STRONG && random.nextFloat() < 0.5f);
		if (rift) {
			DisasterManager.spawnLinked(DisasterType.FISSURE, level, pos, severity, 0);
		}
		if (severity.level >= Severity.STRONG.level && DisasterManager.isCoastal(level, pos, 96)) {
			DisasterManager.spawnLinked(DisasterType.TSUNAMI, level, pos, severity, 45);
		}
		if (severity.level >= Severity.STRONG.level) {
			startFires();
		}
		// Aftershocks: weaker quakes at the same place, 15-60 seconds apart.
		if (severity.level >= Severity.MODERATE.level) {
			int count = 1 + random.nextInt(severity.level);
			for (int i = 0; i < count; i++) {
				DisasterManager.spawnLinked(DisasterType.EARTHQUAKE, level, pos, severity.lower(),
						15 + random.nextInt(45) + i * 20);
			}
		}
	}

	/** Torches, lamps and campfires knocked over start fires. Only flammable things keep burning. */
	private void startFires() {
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(pos) > radius * radius) {
				continue;
			}
			for (int i = 0; i < 30 * severity.level; i++) {
				BlockPos bp = p.blockPosition().offset(random.nextInt(33) - 16, random.nextInt(15) - 6,
						random.nextInt(33) - 16);
				if (!DisasterUtil.loaded(level, bp.getX(), bp.getZ())) {
					continue;
				}
				BlockState state = level.getBlockState(bp);
				if (state.getLightEmission() < 12 || state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
						|| random.nextFloat() > 0.5f) {
					continue;
				}
				for (BlockPos n : new BlockPos[] {bp.above(), bp.north(), bp.south(), bp.east(), bp.west()}) {
					if (level.getBlockState(n).isAir()) {
						level.setBlock(n, Blocks.FIRE.defaultBlockState(), 3);
						break;
					}
				}
			}
		}
	}
}
