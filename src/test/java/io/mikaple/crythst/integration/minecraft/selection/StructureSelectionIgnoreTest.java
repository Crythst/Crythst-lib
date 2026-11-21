package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureSelectionIgnoreTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftPhysics.init(new CrythstConfig());
    }

    @AfterEach
    void clear() {
        StructureSelectionIgnore.clear();
    }

    @Test
    void nestedSingleIgnoresKeepTheStructureHiddenUntilBothClose() throws Exception {
        try (AutoCloseable outer = StructureSelectionIgnore.ignore(7L)) {
            assertTrue(StructureSelectionIgnore.isIgnored(7L));
            assertFalse(StructureSelectionIgnore.isIgnored(8L));
            try (AutoCloseable inner = StructureSelectionIgnore.ignore(7L)) {
                assertTrue(StructureSelectionIgnore.isIgnored(7L));
            }
            assertTrue(StructureSelectionIgnore.isIgnored(7L));
        }
        assertFalse(StructureSelectionIgnore.isIgnored(7L));
        assertFalse(StructureSelectionIgnore.active());
    }

    @Test
    void ignoreAllIsScopedAndNested() throws Exception {
        try (AutoCloseable outer = StructureSelectionIgnore.ignoreAll()) {
            assertTrue(StructureSelectionIgnore.ignoresAll());
            assertTrue(StructureSelectionIgnore.isIgnored(123L));
            try (AutoCloseable inner = StructureSelectionIgnore.ignoreAll()) {
                assertTrue(StructureSelectionIgnore.ignoresAll());
            }
            assertTrue(StructureSelectionIgnore.ignoresAll());
        }
        assertFalse(StructureSelectionIgnore.ignoresAll());
        assertFalse(StructureSelectionIgnore.isIgnored(123L));
    }

    @Test
    void ignoresAreThreadLocal() throws Exception {
        try (AutoCloseable ignored = StructureSelectionIgnore.ignore(7L)) {
            final CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(
                    () -> StructureSelectionIgnore.isIgnored(7L));
            assertFalse(otherThread.get(5, TimeUnit.SECONDS));
            assertTrue(StructureSelectionIgnore.isIgnored(7L));
        }
    }

    @Test
    void raycastSkipsIgnoredStructures() throws Exception {
        final StructureVoxelView near = view(7L, 2.0);
        final StructureVoxelView far = view(8L, 5.0);
        final List<StructureVoxelView> views = List.of(near, far);
        final Vec3 from = new Vec3(0.5, 0.5, 0.5);
        final Vec3 to = new Vec3(10.5, 0.5, 0.5);

        assertEquals(7L, StructureSelection.raycast(views, from, to).bodyId());

        try (AutoCloseable ignored = StructureSelectionIgnore.ignore(7L)) {
            assertEquals(8L, StructureSelection.raycast(views, from, to).bodyId());
        }

        try (AutoCloseable ignored = StructureSelectionIgnore.ignoreAll()) {
            assertNull(StructureSelection.raycast(views, from, to));
        }
    }

    @Test
    void clipKeepsRealBlockPrecedenceWhileStructuresAreIgnored() throws Exception {
        final StructureVoxelView structure = view(7L, 2.0);
        final ClipContext context = new ClipContext(
                new Vec3(0.5, 0.5, 0.5), new Vec3(10.5, 0.5, 0.5),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty());

        final FakeLevel level = new FakeLevel(new BlockPos(1, 0, 0));
        try (AutoCloseable ignored = StructureSelectionIgnore.ignoreAll()) {
            final BlockHitResult hit = StructureSelection.clip(level, context, List.of(structure));
            assertEquals(HitResult.Type.BLOCK, hit.getType());
            assertEquals(new BlockPos(1, 0, 0), hit.getBlockPos());
        }

        final FakeLevel empty = new FakeLevel(null);
        try (AutoCloseable ignored = StructureSelectionIgnore.ignoreAll()) {
            final BlockHitResult hit = StructureSelection.clip(empty, context, List.of(structure));
            assertEquals(HitResult.Type.MISS, hit.getType());
        }

        try (AutoCloseable ignored = StructureSelectionIgnore.ignore(7L)) {
            final BlockHitResult hit = StructureSelection.clip(empty, context, List.of(structure));
            assertEquals(HitResult.Type.MISS, hit.getType());
        }

        final BlockHitResult hit = StructureSelection.clip(empty, context, List.of(structure));
        assertInstanceOf(StructureBlockHitResult.class, hit);
    }

    private static StructureVoxelView view(long bodyId, double x) {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 1, 1, 1);
        final Set<Long> solid = new HashSet<>(Set.of(0L));
        return new StructureVoxelView() {
            @Override
            public long bodyId() {
                return bodyId;
            }

            @Override
            public Transform3d pose() {
                return Transform3d.of(new org.joml.Vector3d(x, 0.0, 0.0));
            }

            @Override
            public double blockScale() {
                return 1.0;
            }

            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int vx, int vy, int vz) {
                return solid.contains((long) vx);
            }

            @Override
            public BlockState blockState(int vx, int vy, int vz) {
                return Blocks.STONE.defaultBlockState();
            }
        };
    }

    private static final class FakeLevel implements BlockGetter {
        private final BlockPos realBlock;

        private FakeLevel(BlockPos realBlock) {
            this.realBlock = realBlock;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return realBlock != null && realBlock.equals(pos)
                    ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
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
