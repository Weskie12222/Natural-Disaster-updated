package com.naturaldisasters.block;

import com.mojang.serialization.MapCodec;
import com.naturaldisasters.disaster.DisasterManager;
import com.naturaldisasters.disaster.VillageReactions;
import com.naturaldisasters.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Warning siren. It wails when a Strong/Extreme disaster (Moderate for tsunamis) is forecast or active
 * within range, and also whenever it receives a redstone signal. Audible from 300 blocks.
 * <p>
 * The siren recording is split into 16 five-second pieces; every 100 game ticks the block plays the next piece
 * (chosen from the world clock so consecutive plays are consecutive pieces), which gives one continuous wail.
 * The block polls itself with scheduled ticks, which are saved with the chunk, so no block entity is needed.
 * While it wails it also tells nearby villagers to take cover and registers itself with the disaster manager,
 * so players within earshot get the forecast messages.
 */
public class SirenBlock extends Block {
	public static final MapCodec<SirenBlock> CODEC = simpleCodec(SirenBlock::new);
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	private static final int POLL_TICKS = 10;
	/** Length of one siren piece in ticks (5 seconds). */
	private static final int SEGMENT_TICKS = 100;

	public SirenBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		level.scheduleTick(pos, this, POLL_TICKS);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		boolean alarm = level.hasNeighborSignal(pos) || DisasterManager.alarmActive(level, pos);

		if (alarm != state.getValue(LIT)) {
			level.setBlock(pos, state.setValue(LIT, alarm), 3);
		}
		if (alarm) {
			DisasterManager.noteSiren(level, pos);
			long t = level.getGameTime();
			if (t % SEGMENT_TICKS < POLL_TICKS) {
				int piece = (int) ((t / SEGMENT_TICKS) % ModSounds.SIREN_SEGMENTS);
				level.playSound(null, pos, ModSounds.SIREN[piece], SoundSource.BLOCKS, 1.0f, 1.0f);
			}
			if (t % 40 < POLL_TICKS) {
				VillageReactions.alertNear(level, pos, 96.0);
			}
			level.sendParticles(ParticleTypes.NOTE, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
					1, 0.3, 0.2, 0.3, 1.0);
		}
		level.scheduleTick(pos, this, POLL_TICKS);
	}
}
