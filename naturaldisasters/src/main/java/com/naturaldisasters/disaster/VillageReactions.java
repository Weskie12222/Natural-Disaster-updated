package com.naturaldisasters.disaster;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How villages react.
 * <ul>
 *   <li>Villagers hide in their homes (the same vanilla behaviour as when a bell is rung) as soon as a
 *       disaster is forecast near them, and while a siren is wailing within 96 blocks.</li>
 *   <li>Village bells toll during the warning and the disaster.</li>
 *   <li>Villagers caught in the open when it hits panic and run around.</li>
 * </ul>
 */
public final class VillageReactions {
	private VillageReactions() {
	}

	/** Called every second from the disaster manager. */
	public static void tick(MinecraftServer server, List<Disaster> disasters, long tick) {
		for (Disaster d : disasters) {
			if (d.severity.level < Severity.MODERATE.level && !d.isActive()) {
				continue;
			}
			ServerLevel level = d.level;
			double r = Math.min(d.warningRadius(), 192.0);
			Vec3 c = d.position();
			AABB box = new AABB(c.x - r, level.getMinY(), c.z - r, c.x + r, level.getMaxY(), c.z + r);
			double danger = d.dangerRadius();
			Set<BlockPos> bells = new HashSet<>();

			for (LivingEntity v : level.getEntities(EntityType.VILLAGER, box, e -> true)) {
				if (tick % 40 == 0) {
					hide(level, v);
				}
				v.getBrain().getMemory(MemoryModuleType.MEETING_POINT).ifPresent(gp -> {
					if (gp.dimension() == level.dimension()) {
						bells.add(gp.pos());
					}
				});
				// Caught outside when it hits: panic.
				if (d.isActive() && v.position().distanceTo(c) < danger && level.canSeeSky(v.blockPosition())) {
					panic(level, v);
				}
			}
			// Bells toll every 6 seconds.
			if (tick % 120 == 0) {
				for (BlockPos bell : bells) {
					level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 4.0f, 0.9f);
				}
			}
		}
	}

	/** A siren is wailing: every villager within {@code radius} blocks takes cover. */
	public static void alertNear(ServerLevel level, BlockPos pos, double radius) {
		AABB box = new AABB(pos).inflate(radius);
		for (LivingEntity v : level.getEntities(EntityType.VILLAGER, box, e -> true)) {
			hide(level, v);
		}
	}

	/** Same memory the vanilla village bell sets: villagers go and hide at home until it expires. */
	private static void hide(ServerLevel level, LivingEntity villager) {
		villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
	}

	private static void panic(ServerLevel level, LivingEntity villager) {
		villager.getBrain().setMemoryWithExpiry(MemoryModuleType.HURT_BY, level.damageSources().generic(), 100L);
		if (level.getRandom().nextInt(4) == 0) {
			level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f);
		}
	}
}
