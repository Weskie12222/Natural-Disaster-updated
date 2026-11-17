package com.naturaldisasters.disaster;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;

/**
 * What kind of terrain a disaster is happening over. Disasters change their behaviour per environment:
 * dust devils and sandstorms in deserts, blizzards and avalanches in snow, flash floods in mountains,
 * monsoon floods in jungles, waterspouts and storm surges at sea, and so on.
 */
public enum Environment {
	PLAINS("Plains"),
	ARID("Desert / Badlands"),
	SNOWY("Snowy"),
	WET("Jungle / Swamp"),
	MOUNTAIN("Mountains"),
	OCEAN("Ocean");

	public final String label;

	Environment(String label) {
		this.label = label;
	}

	private static final Set<ResourceKey<Biome>> COLD = Set.of(
			Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA, Biomes.SNOWY_SLOPES,
			Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS, Biomes.GROVE, Biomes.FROZEN_RIVER,
			Biomes.SNOWY_BEACH, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN);

	private static final Set<ResourceKey<Biome>> ARID_BIOMES = Set.of(
			Biomes.DESERT, Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);

	private static final Set<ResourceKey<Biome>> WET_BIOMES = Set.of(
			Biomes.SWAMP, Biomes.MANGROVE_SWAMP, Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE);

	private static boolean in(ServerLevel level, BlockPos pos, Set<ResourceKey<Biome>> set) {
		for (ResourceKey<Biome> key : set) {
			if (level.getBiome(pos).is(key)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isCold(ServerLevel level, BlockPos pos) {
		return in(level, pos, COLD);
	}

	public static Environment at(ServerLevel level, double x, double z) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!DisasterUtil.loaded(level, bx, bz)) {
			return PLAINS;
		}
		int surface = DisasterUtil.surfaceY(level, bx, bz);
		BlockPos top = new BlockPos(bx, surface - 1, bz);
		if (level.getBlockState(top).is(Blocks.WATER)) {
			return OCEAN;
		}
		BlockPos here = new BlockPos(bx, surface, bz);
		if (in(level, here, COLD)) {
			return SNOWY;
		}
		if (surface > level.getSeaLevel() + 60) {
			return MOUNTAIN;
		}
		if (in(level, here, ARID_BIOMES)) {
			return ARID;
		}
		if (in(level, here, WET_BIOMES)) {
			return WET;
		}
		return PLAINS;
	}
}
