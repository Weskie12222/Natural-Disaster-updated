package com.naturaldisasters.disaster;

import com.naturaldisasters.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A huge slow storm. What it does depends on the ground it is over, evaluated per player:
 * <ul>
 *   <li>Desert / badlands: a Sandstorm. No rain; blinding, slowing sand.</li>
 *   <li>Snow: a Blizzard. Freezing damage unless you stand near a light source, or indoors.</li>
 *   <li>Jungle / swamp / plains: heavy rain, lightning, flash floods under the storm.</li>
 *   <li>Mountains: rain triggers landslides on steep slopes.</li>
 *   <li>Coast: a storm surge (a tsunami) at landfall.</li>
 * </ul>
 * Wind only hurts what is under open sky; a roof or being underground protects you. Strong and extreme storms
 * spawn tornadoes inside them.
 */
public class HurricaneDisaster extends Disaster {
	private final double radius;
	private final double windX;
	private final double windZ;
	private final double drift;
	private final double heading;
	private int floods;

	public HurricaneDisaster(ServerLevel level, Vec3 center, double headingRadians, Severity severity, int warnTicks) {
		super(DisasterType.HURRICANE, severity, level, center, warnTicks);
		this.radius = 90.0 + 30.0 * severity.level;
		this.heading = headingRadians;
		this.windX = Math.cos(headingRadians + 0.6);
		this.windZ = Math.sin(headingRadians + 0.6);
		this.drift = 0.04;
	}

	@Override
	public String describe() {
		String name = switch (env) {
			case ARID -> "Sandstorm";
			case SNOWY -> "Blizzard";
			case MOUNTAIN -> "Mountain Storm";
			case WET -> "Tropical Cyclone";
			default -> "Hurricane";
		};
		return severity.label + " " + name;
	}

	@Override
	public double warningRadius() {
		return radius + 160.0;
	}

	@Override
	public double dangerRadius() {
		return radius;
	}

	@Override
	protected int activeTicks() {
		return (60 + 30 * severity.level) * 20;
	}

	@Override
	protected void tickWarning(int age) {
		// The first sign: the weather turns. (Not in deserts, where the sky just turns brown.)
		if (age == 1 && env != Environment.ARID) {
			Weather.set(level, "rain", ticksToImpact() / 20 + 30);
		}
		if (age % 100 == 0) {
			cue(ModSounds.ROAR, 1.0f, 0.5f);
		}
	}

	@Override
	protected void onStart() {
		if (env != Environment.ARID) {
			Weather.set(level, "thunder", activeTicks() / 20 + 30);
		}
		Warnings.broadcastImpact(this, describe() + " has arrived! Seek shelter!");
		// Storm surge at landfall.
		if (severity.level >= Severity.STRONG.level && DisasterManager.isCoastal(level, pos, 128)) {
			DisasterManager.spawnLinked(DisasterType.TSUNAMI, level, pos, Severity.MODERATE, 20);
		}
	}

	@Override
	protected void tickActive(int t) {
		pos = new Vec3(pos.x + Math.cos(heading) * drift, pos.y, pos.z + Math.sin(heading) * drift);
		if (t % 100 == 0) {
			env = Environment.at(level, pos.x, pos.z);
		}

		if (t % 40 == 0) {
			for (ServerPlayer p : level.players()) {
				if (p.position().distanceToSqr(pos) < radius * radius) {
					level.playSound(null, p.getX(), p.getY(), p.getZ(), ModSounds.ROAR, SoundSource.WEATHER,
							1.5f + 0.5f * severity.level, 0.5f + random.nextFloat() * 0.2f);
					localEffects(p);
				}
			}
		}

		windOnEntities();
		debris(t);

		if (severity.level >= Severity.STRONG.level && t % 20 == 0 && env != Environment.ARID) {
			lightning();
		}
		if (t > 100 && t % 100 == 0) {
			slides();
		}
		if (severity.level >= Severity.STRONG.level && t > 200 && t % (severity == Severity.EXTREME ? 600 : 900) == 0) {
			spawnEmbeddedTornado();
		}
		// Torrential rain floods low ground under the eye.
		if (t > 300 && t % 1500 == 0 && floods < severity.level - 1
				&& (env == Environment.WET || env == Environment.PLAINS)) {
			floods++;
			DisasterManager.spawnLinked(DisasterType.FLOOD, level, pos, Severity.MODERATE, 5);
		}
	}

	/** Effects that depend on the terrain the player is standing on. */
	private void localEffects(ServerPlayer p) {
		if (!DisasterUtil.isVulnerable(p) || !level.canSeeSky(p.blockPosition()) || DisasterUtil.sheltered(level, p)) {
			return;
		}
		switch (Environment.at(level, p.getX(), p.getZ())) {
			case ARID -> {
				DisasterUtil.effect(level, p, "blindness", 4, 0);
				DisasterUtil.effect(level, p, "slowness", 4, severity.level >= 3 ? 1 : 0);
			}
			case SNOWY -> {
				// Light sources (torches, campfires...) keep you warm.
				if (level.getBrightness(LightLayer.BLOCK, p.blockPosition()) < 8) {
					DisasterUtil.freeze(p, 90 + 25 * severity.level);
				}
				DisasterUtil.effect(level, p, "slowness", 3, 0);
			}
			default -> {
			}
		}
	}

	private void windOnEntities() {
		AABB box = new AABB(pos.x - radius, level.getMinY(), pos.z - radius,
				pos.x + radius, level.getMaxY(), pos.z + radius);
		double gust = 0.012 + 0.012 * severity.level;
		gust *= 0.7 + 0.6 * random.nextDouble();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (!DisasterUtil.isVulnerable(e) || e.isInWater()) {
				continue;
			}
			BlockPos bp = e.blockPosition();
			if (!level.canSeeSky(bp)) {
				continue; // sheltered
			}
			DisasterUtil.push(e, windX * gust, e.onGround() ? 0.0 : 0.002, windZ * gust);
			if (severity == Severity.EXTREME && e.onGround() && random.nextInt(40) == 0) {
				DisasterUtil.push(e, windX * 0.5, 0.35, windZ * 0.5); // knocked off your feet
			}
		}
	}

	private ParticleOptions windParticle() {
		return switch (env) {
			case ARID -> new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
			case SNOWY -> ParticleTypes.SNOWFLAKE;
			default -> ParticleTypes.CLOUD;
		};
	}

	private void debris(int t) {
		if (t % 2 != 0) {
			return;
		}
		float maxHardness = DisasterUtil.maxHardness(severity) * 0.6f;
		int attempts = 2 + severity.level;
		for (int i = 0; i < attempts; i++) {
			double ang = random.nextDouble() * Math.PI * 2;
			double dist = Math.sqrt(random.nextDouble()) * radius;
			int x = (int) Math.floor(pos.x + Math.cos(ang) * dist);
			int z = (int) Math.floor(pos.z + Math.sin(ang) * dist);
			if (!DisasterUtil.loaded(level, x, z)) {
				continue;
			}
			BlockPos bp = new BlockPos(x, DisasterUtil.surfaceY(level, x, z) - 1, z);
			BlockState state = level.getBlockState(bp);
			if (DisasterUtil.destructible(level, bp, state, maxHardness)) {
				DisasterUtil.dust(level, bp, state, 4);
				level.destroyBlock(bp, false);
			}
		}
		ParticleOptions wp = windParticle();
		int count = env == Environment.ARID || env == Environment.SNOWY ? 10 : 3;
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(pos) < radius * radius) {
				level.sendParticles(wp, p.getX() - windX * 6, p.getY() + 1.5, p.getZ() - windZ * 6,
						count, 3, 2, 3, 0.4);
			}
		}
	}

	/** Heavy rain shakes loose steep slopes near players. */
	private void slides() {
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(pos) > radius * radius) {
				continue;
			}
			for (int i = 0; i < 3; i++) {
				int x = (int) (p.getX() + (random.nextDouble() - 0.5) * 80);
				int z = (int) (p.getZ() + (random.nextDouble() - 0.5) * 80);
				TerrainEffects.landslide(level, x, z, severity);
			}
		}
	}

	private void lightning() {
		if (level.players().isEmpty()) {
			return;
		}
		ServerPlayer p = level.players().get(random.nextInt(level.players().size()));
		if (p.position().distanceToSqr(pos) > radius * radius || random.nextInt(6) != 0) {
			return;
		}
		int x = (int) (p.getX() + (random.nextDouble() - 0.5) * 70);
		int z = (int) (p.getZ() + (random.nextDouble() - 0.5) * 70);
		if (!DisasterUtil.loaded(level, x, z)) {
			return;
		}
		int y = DisasterUtil.surfaceY(level, x, z);
		Weather.run(level, "summon minecraft:lightning_bolt " + x + " " + y + " " + z);
	}

	private void spawnEmbeddedTornado() {
		double ang = random.nextDouble() * Math.PI * 2;
		double dist = 20 + random.nextDouble() * (radius - 20);
		Vec3 at = new Vec3(pos.x + Math.cos(ang) * dist, pos.y, pos.z + Math.sin(ang) * dist);
		DisasterManager.spawnLinked(DisasterType.TORNADO, level, at, Severity.MODERATE, 0);
	}

	@Override
	protected void onEnd() {
		Warnings.broadcastEnd(this);
	}
}
