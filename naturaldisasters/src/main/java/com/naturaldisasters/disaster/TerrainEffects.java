package com.naturaldisasters.disaster;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Ground-level damage shared by several disasters: slides, sinkholes, cracks and cave-ins. */
public final class TerrainEffects {
	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private TerrainEffects() {
	}

	private static boolean snowy(BlockState s) {
		return s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW) || s.is(Blocks.POWDER_SNOW)
				|| s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE);
	}

	public static boolean isLoose(BlockState s) {
		return s.is(Blocks.SAND) || s.is(Blocks.RED_SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.MUD);
	}

	/**
	 * Slope collapse. On steep terrain the top layers of the ground break loose and slide downhill as falling
	 * blocks. On snow it becomes an avalanche (wider and deeper, can bury things).
	 * @return true if something slid
	 */
	public static boolean landslide(ServerLevel level, int x, int z, Severity sev) {
		if (!DisasterUtil.loaded(level, x, z)) {
			return false;
		}
		RandomSource r = level.getRandom();
		int h = DisasterUtil.surfaceY(level, x, z);
		int dirX = 0;
		int dirZ = 0;
		int bestDrop = 0;
		for (int[] d : DIRS) {
			int nx = x + d[0] * 3;
			int nz = z + d[1] * 3;
			if (!DisasterUtil.loaded(level, nx, nz)) {
				continue;
			}
			int drop = h - DisasterUtil.surfaceY(level, nx, nz);
			if (drop > bestDrop) {
				bestDrop = drop;
				dirX = d[0];
				dirZ = d[1];
			}
		}
		if (bestDrop < 4) {
			return false;
		}
		boolean snow = snowy(level.getBlockState(new BlockPos(x, h - 1, z)))
				|| snowy(level.getBlockState(new BlockPos(x, h - 2, z)));
		int cols = snow ? 1 : 0;
		int layers = 2 + sev.level + (snow ? 2 : 0);
		float limit = snow ? 1.0f : Math.max(1.6f, DisasterUtil.maxHardness(sev) + 0.7f);
		boolean any = false;
		for (int dx = -cols; dx <= cols; dx++) {
			for (int dz = -cols; dz <= cols; dz++) {
				int cx = x + dx;
				int cz = z + dz;
				if (!DisasterUtil.loaded(level, cx, cz)) {
					continue;
				}
				int ch = DisasterUtil.surfaceY(level, cx, cz);
				for (int i = 0; i < layers; i++) {
					BlockPos bp = new BlockPos(cx, ch - 1 - i, cz);
					BlockState state = level.getBlockState(bp);
					if (!DisasterUtil.destructible(level, bp, state, limit)) {
						break;
					}
					FallingBlockEntity fb = FallingBlockEntity.fall(level, bp, state);
					DisasterUtil.setMotion(fb, new Vec3(
							dirX * 0.3 + (r.nextDouble() - 0.5) * 0.15, 0.1,
							dirZ * 0.3 + (r.nextDouble() - 0.5) * 0.15));
					any = true;
				}
			}
		}
		return any;
	}

	/** The ground liquefies and sinks into a funnel-shaped hole. */
	public static void sinkhole(ServerLevel level, int x, int z, Severity sev) {
		int radius = 2 + sev.level;
		int depth = 3 + sev.level * 2;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (dist > radius + 0.5) {
					continue;
				}
				int cx = x + dx;
				int cz = z + dz;
				if (!DisasterUtil.loaded(level, cx, cz)) {
					continue;
				}
				int d = (int) Math.round(depth * (1.0 - dist / (radius + 0.5)));
				int top = DisasterUtil.surfaceY(level, cx, cz) - 1;
				for (int y = top; y > top - d; y--) {
					BlockPos bp = new BlockPos(cx, y, cz);
					BlockState state = level.getBlockState(bp);
					if (state.isAir() || state.hasBlockEntity() || state.is(Blocks.BEDROCK)
							|| state.getDestroySpeed(level, bp) < 0 || !state.getFluidState().isEmpty()) {
						continue;
					}
					if (y == top && level.getRandom().nextInt(3) == 0) {
						DisasterUtil.dust(level, bp, state, 4);
					}
					level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
	}

	/** A narrow surface crack, {@code length} blocks long and {@code depth} deep. */
	public static void crack(ServerLevel level, int x, int z, double dirX, double dirZ, int length, int depth) {
		RandomSource r = level.getRandom();
		double cx = x;
		double cz = z;
		for (int s = 0; s < length; s++) {
			cx += dirX + (r.nextDouble() - 0.5) * 0.5;
			cz += dirZ + (r.nextDouble() - 0.5) * 0.5;
			int bx = (int) Math.floor(cx);
			int bz = (int) Math.floor(cz);
			if (!DisasterUtil.loaded(level, bx, bz)) {
				continue;
			}
			int top = DisasterUtil.surfaceY(level, bx, bz) - 1;
			for (int y = top; y > top - depth; y--) {
				BlockPos bp = new BlockPos(bx, y, bz);
				BlockState state = level.getBlockState(bp);
				if (state.isAir() || state.hasBlockEntity() || state.is(Blocks.BEDROCK)
						|| state.getDestroySpeed(level, bp) < 0 || !state.getFluidState().isEmpty()) {
					continue;
				}
				level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	/** Part of the ceiling above an entity in a cave or mine collapses onto it. */
	public static void caveIn(ServerLevel level, LivingEntity entity, Severity sev) {
		RandomSource r = level.getRandom();
		BlockPos head = entity.blockPosition().above(2);
		for (int dy = 0; dy < 8; dy++) {
			BlockPos bp = head.above(dy);
			BlockState s = level.getBlockState(bp);
			if (s.isAir()) {
				continue;
			}
			if (!DisasterUtil.destructible(level, bp, s, 3.2f)) {
				return;
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos q = bp.offset(dx, 0, dz);
					BlockState st = level.getBlockState(q);
					if (DisasterUtil.destructible(level, q, st, 3.2f) && r.nextFloat() < 0.6f) {
						FallingBlockEntity.fall(level, q, st);
					}
				}
			}
			if (dy <= 3) {
				DisasterUtil.hurt(level, entity, 1.0f + sev.level);
			}
			return;
		}
	}
}
