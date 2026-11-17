package com.naturaldisasters.disaster;

import com.naturaldisasters.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A moving funnel. It sucks entities in and flings them upward, rips loose blocks off the ground
 * (as falling blocks that land somewhere else) and wanders across the landscape. It changes with the terrain
 * it crosses:
 * <ul>
 *   <li>Desert: a fast Dust Devil of sand that blinds and slows.</li>
 *   <li>Snow: a Snow Vortex that freezes whatever it catches.</li>
 *   <li>Ocean: a Waterspout; it tears at boats and swimmers but not at blocks.</li>
 *   <li>Otherwise: a normal tornado that makes you dizzy.</li>
 * </ul>
 * Anyone underground with a roof overhead is safe from the pull.
 */
public class TornadoDisaster extends Disaster {
	private final double radius;
	private final double height;
	private final double baseSpeed; // blocks per tick
	private double heading; // radians
	private double spin;

	public TornadoDisaster(ServerLevel level, Vec3 start, double headingRadians, Severity severity, int warnTicks) {
		super(DisasterType.TORNADO, severity, level, start, warnTicks);
		this.heading = headingRadians;
		this.radius = 3.0 + 2.2 * severity.level;
		this.height = 26.0 + 12.0 * severity.level;
		this.baseSpeed = 0.12 + 0.04 * severity.level;
	}

	@Override
	public String describe() {
		String name = switch (env) {
			case ARID -> "Dust Devil";
			case SNOWY -> "Snow Vortex";
			case OCEAN -> "Waterspout";
			case WET -> "Jungle Twister";
			case MOUNTAIN -> "Mountain Whirlwind";
			default -> "Tornado";
		};
		String[] ef = {"EF0-1", "EF2", "EF3-4", "EF5"};
		return severity.label + " " + name + " (" + ef[severity.level - 1] + ")";
	}

	@Override
	public double dangerRadius() {
		return radius * 6;
	}

	@Override
	protected int activeTicks() {
		return (40 + 20 * severity.level) * 20;
	}

	@Override
	protected void tickWarning(int age) {
		if (age % 90 == 0) {
			cue(ModSounds.ROAR, 1.0f, 0.6f);
		}
	}

	@Override
	protected void onStart() {
		Warnings.broadcastImpact(this, describe().replaceAll(" \\(.*\\)", "") + " has touched down!");
	}

	@Override
	protected void tickActive(int t) {
		if (t % 40 == 0) {
			env = Environment.at(level, pos.x, pos.z);
		}
		move();
		spin += 0.45;

		funnelParticles();
		if (t % 30 == 0) {
			level.playSound(null, pos.x, pos.y + 6, pos.z, ModSounds.ROAR, SoundSource.WEATHER,
					3.0f + severity.level, 0.7f + random.nextFloat() * 0.2f);
		}
		if (t % 2 == 0 && env != Environment.OCEAN) {
			ripBlocks();
		}
		affectEntities(t);
	}

	private double speed() {
		return env == Environment.ARID ? baseSpeed * 1.3 : baseSpeed;
	}

	private void move() {
		heading += (random.nextDouble() - 0.5) * 0.10;
		double s = speed();
		double nx = pos.x + Math.cos(heading) * s;
		double nz = pos.z + Math.sin(heading) * s;
		if (!DisasterUtil.loaded(level, (int) Math.floor(nx), (int) Math.floor(nz))) {
			heading += Math.PI;
			return;
		}
		double ny = DisasterUtil.surfaceY(level, (int) Math.floor(nx), (int) Math.floor(nz));
		pos = new Vec3(nx, ny, nz);
	}

	private ParticleOptions funnelParticle() {
		return switch (env) {
			case ARID -> new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
			case SNOWY -> ParticleTypes.SNOWFLAKE;
			case OCEAN -> ParticleTypes.SPLASH;
			default -> ParticleTypes.CLOUD;
		};
	}

	private void funnelParticles() {
		ParticleOptions main = funnelParticle();
		int rings = 6 + 2 * severity.level;
		for (int i = 0; i < rings; i++) {
			double h = random.nextDouble() * height;
			double r = 0.8 + (radius * 1.4) * (h / height);
			double a = spin + h * 0.35 + random.nextDouble() * 0.6;
			double x = pos.x + Math.cos(a) * r;
			double z = pos.z + Math.sin(a) * r;
			level.sendParticles(main, x, pos.y + h, z, 1, 0.1, 0.1, 0.1, 0.01);
			if (i % 3 == 0) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, pos.y + h, z, 1, 0.2, 0.2, 0.2, 0.0);
			}
		}
	}

	private void ripBlocks() {
		int attempts = 2 + 2 * severity.level;
		float maxHardness = DisasterUtil.maxHardness(severity);
		for (int i = 0; i < attempts; i++) {
			double ang = random.nextDouble() * Math.PI * 2;
			double dist = Math.sqrt(random.nextDouble()) * radius * 1.3;
			int x = (int) Math.floor(pos.x + Math.cos(ang) * dist);
			int z = (int) Math.floor(pos.z + Math.sin(ang) * dist);
			if (!DisasterUtil.loaded(level, x, z)) {
				continue;
			}
			int top = DisasterUtil.surfaceY(level, x, z) - 1;
			BlockPos bp = new BlockPos(x, top - random.nextInt(2), z);
			BlockState state = level.getBlockState(bp);
			if (!DisasterUtil.destructible(level, bp, state, maxHardness)) {
				continue;
			}
			FallingBlockEntity flying = FallingBlockEntity.fall(level, bp, state);
			double tangent = ang + Math.PI / 2;
			DisasterUtil.setMotion(flying, new Vec3(
					Math.cos(tangent) * 0.5 + (random.nextDouble() - 0.5) * 0.2,
					0.7 + random.nextDouble() * 0.6,
					Math.sin(tangent) * 0.5 + (random.nextDouble() - 0.5) * 0.2));
		}
	}

	private void affectEntities(int t) {
		double influence = radius * 2.4;
		AABB box = new AABB(pos.x - influence, pos.y - 4, pos.z - influence,
				pos.x + influence, pos.y + height, pos.z + influence);
		double pull = 0.025 + 0.015 * severity.level;
		double lift = 0.05 + 0.035 * severity.level;
		double spinPower = 0.05 + 0.03 * severity.level;

		for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
			if (!DisasterUtil.isVulnerable(e) || DisasterUtil.sheltered(level, e)) {
				continue;
			}
			double dx = pos.x - e.getX();
			double dz = pos.z - e.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist > influence || dist < 0.01) {
				continue;
			}
			double nx = dx / dist;
			double nz = dz / dist;
			double closeness = 1.0 - dist / influence;

			Vec3 motion = e.getDeltaMovement();
			double mx = motion.x + (nx * pull + -nz * spinPower) * (0.4 + closeness);
			double mz = motion.z + (nz * pull + nx * spinPower) * (0.4 + closeness);
			double my = dist < radius * 1.5 ? Math.min(motion.y + lift, 0.9 + 0.1 * severity.level) : motion.y;
			DisasterUtil.setMotion(e, new Vec3(mx, my, mz));
			e.fallDistance = 0; // the tornado carries you; the landing is what hurts.

			if (!(e instanceof LivingEntity living)) {
				continue;
			}
			if (t % 10 == 0 && dist < radius) {
				DisasterUtil.hurt(level, living, 1.0f + severity.level);
			}
			if (t % 10 == 0) {
				switch (env) {
					case SNOWY -> DisasterUtil.freeze(living, 50);
					default -> {
					}
				}
			}
			if (e instanceof ServerPlayer && t % 20 == 0) {
				switch (env) {
					case ARID -> {
						DisasterUtil.effect(level, e, "blindness", 3, 0);
						DisasterUtil.effect(level, e, "slowness", 3, 1);
					}
					case SNOWY -> DisasterUtil.effect(level, e, "slowness", 3, 1);
					default -> {
						if (dist < radius * 2) {
							DisasterUtil.effect(level, e, "nausea", 5, 0);
						}
					}
				}
			}
		}
	}

	@Override
	protected void onEnd() {
		Warnings.broadcastEnd(this);
	}
}
