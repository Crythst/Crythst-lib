package io.mikaple.crythst.integration.minecraft.boundary;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundarySignalTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void redstoneSourcesEmitTheirVanillaSignal() {
        final AirGetter level = new AirGetter();
        final BlockPos pos = BlockPos.ZERO;
        assertEquals(15, BoundarySignals.signalOf(
                Blocks.REDSTONE_BLOCK.defaultBlockState(), level, pos, Direction.UP));
        assertEquals(15, BoundarySignals.signalOf(
                Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, true),
                level, pos, Direction.NORTH));
        assertEquals(0, BoundarySignals.signalOf(
                Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, false),
                level, pos, Direction.NORTH));
        assertEquals(15, BoundarySignals.signalOf(
                Blocks.REDSTONE_TORCH.defaultBlockState()
                        .setValue(RedstoneTorchBlock.LIT, true),
                level, pos, Direction.NORTH));
    }

    @Test
    void directSignalIsSeparateFromStrongSignal() {
        final AirGetter level = new AirGetter();
        final BlockPos pos = BlockPos.ZERO;
        // A redstone block supplies weak power; it does not provide directed
        // strong power. A lit redstone torch does provide strong power to the
        // block below it.
        assertEquals(0, BoundarySignals.directSignalOf(
                Blocks.REDSTONE_BLOCK.defaultBlockState(), level, pos, Direction.NORTH));
        assertEquals(15, BoundarySignals.directSignalOf(
                Blocks.REDSTONE_TORCH.defaultBlockState()
                        .setValue(RedstoneTorchBlock.LIT, true),
                level, pos, Direction.DOWN));
        assertEquals(0, BoundarySignals.directSignalOf(
                Blocks.STONE.defaultBlockState(), level, pos, Direction.NORTH));
    }

    private static final class AirGetter implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
