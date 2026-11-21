package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StructureRaycasterTest {
    private static final double EPSILON = 1.0e-6;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftPhysics.init(new CrythstConfig());
    }

    @Test
    void hitsTheNearestSolidVoxelAndReportsTheEntryFace() {
        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view(Transform3d.of(new Vector3d(10.0, 64.0, -3.0)),
                        1.0, new IntBounds3(0, 0, 0, 3, 1, 1), 2, 0, 0)),
                new Vec3(8.0, 64.5, -2.5), new Vec3(14.0, 64.5, -2.5));

        assertNotNull(hit);
        assertEquals(4.0, hit.distance(), EPSILON);
        assertEquals(2, hit.voxelX());
        assertEquals(0, hit.voxelY());
        assertEquals(0, hit.voxelZ());
        assertEquals(Direction.WEST, hit.localFace());
        assertEquals(Direction.WEST, hit.getDirection());
        assertEquals(new BlockPos(12, 64, -3), hit.getBlockPos());
        assertEquals(new Vec3(12.0, 64.5, -2.5), hit.getLocation());
        assertEquals(HitResult.Type.BLOCK, hit.getType());
        assertEquals(7L, hit.bodyId());
    }

    @Test
    void stopsAtTheFirstSolidCellNotACloserEmptyCell() {
        final StructureVoxelView view = view(Transform3d.identity(), 1.0,
                new IntBounds3(0, 0, 0, 4, 1, 1), 0, 0, 0, 3, 0, 0);
        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view), new Vec3(-1.0, 0.5, 0.5), new Vec3(6.0, 0.5, 0.5));

        assertNotNull(hit);
        assertEquals(0, hit.voxelX());
        assertEquals(1.0, hit.distance(), EPSILON);
    }

    @Test
    void rotatedPoseTransformsTheLocalFaceIntoWorldSpace() {
        final Transform3d yaw90 = Transform3d.of(new Vector3d(), new Quaterniond().rotateY(Math.PI / 2.0));
        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view(yaw90, 1.0, new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0)),
                new Vec3(0.5, 0.5, 10.0), new Vec3(0.5, 0.5, -2.0));

        assertNotNull(hit);
        assertEquals(Direction.WEST, hit.localFace());
        assertEquals(Direction.SOUTH, hit.getDirection());
        assertEquals(0, hit.voxelX());
        assertEquals(10.0, hit.distance(), EPSILON);
        assertEquals(0.5, hit.getLocation().x, EPSILON);
        assertEquals(0.5, hit.getLocation().y, EPSILON);
        assertEquals(0.0, hit.getLocation().z, EPSILON);
    }

    @Test
    void blockScaleConvertsGridCoordinatesAndDistance() {
        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view(Transform3d.identity(), 2.0,
                        new IntBounds3(0, 0, 0, 2, 1, 1), 1, 0, 0)),
                new Vec3(-1.0, 1.0, 1.0), new Vec3(6.0, 1.0, 1.0));

        assertNotNull(hit);
        assertEquals(1, hit.voxelX());
        assertEquals(3.0, hit.distance(), EPSILON);
        assertEquals(2.0, hit.getLocation().x, EPSILON);
        assertEquals(2.0, hit.blockScale(), EPSILON);
    }

    @Test
    void originInsideASolidVoxelStillProducesAFace() {
        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view(Transform3d.identity(), 1.0,
                        new IntBounds3(0, 0, 0, 2, 2, 2), 0, 0, 0)),
                new Vec3(0.5, 0.5, 0.5), new Vec3(1.5, 0.5, 0.5));

        assertNotNull(hit);
        assertEquals(0.0, hit.distance(), EPSILON);
        assertEquals(Direction.WEST, hit.localFace());
    }

    @Test
    void missOutsideReachReturnsNull() {
        assertNull(StructureRaycaster.nearest(
                List.of(view(Transform3d.identity(), 1.0,
                        new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0)),
                new Vec3(-5.0, 0.5, 0.5), new Vec3(-1.0, 0.5, 0.5)));
    }

    @Test
    void picksTheNearerOfTwoStructures() {
        final StructureVoxelView near = view(Transform3d.of(new Vector3d(3.0, 0.0, 0.0)),
                1.0, new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);
        final StructureVoxelView far = view(Transform3d.of(new Vector3d(8.0, 0.0, 0.0)),
                1.0, new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);

        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(far, near), new Vec3(0.0, 0.5, 0.5), new Vec3(12.0, 0.5, 0.5));

        assertNotNull(hit);
        assertEquals(3.0, hit.distance(), EPSILON);
    }

    @Test
    void supportsNegativeVoxelCoordinatesAndAdjacentPlacement() {
        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view(Transform3d.identity(), 1.0,
                        new IntBounds3(-3, -1, -1, -1, 1, 1), -2, 0, 0)),
                new Vec3(-5.0, 0.5, 0.5), new Vec3(1.0, 0.5, 0.5));

        assertNotNull(hit);
        assertEquals(-2, hit.voxelX());
        assertEquals(Direction.WEST, hit.localFace());
        assertEquals(-3, hit.adjacentX());
        assertEquals(0, hit.adjacentY());
        assertEquals(0, hit.adjacentZ());
        assertEquals(new BlockPos(-2, 0, 0), hit.getBlockPos());
        assertEquals(3.0, hit.distance(), EPSILON);
    }

    @Test
    void clipLetsARealBlockWinWhenItIsCloser() {
        final FakeLevel level = new FakeLevel(new BlockPos(1, 0, 0));
        final StructureVoxelView structure = view(Transform3d.identity(), 1.0,
                new IntBounds3(2, 0, 0, 4, 1, 1), 2, 0, 0);
        final ClipContext context = context(new Vec3(0.5, 0.5, 0.5), new Vec3(6.0, 0.5, 0.5));

        final BlockHitResult hit = StructureSelection.clip(level, context, List.of(structure));

        assertEquals(HitResult.Type.BLOCK, hit.getType());
        assertEquals(new BlockPos(1, 0, 0), hit.getBlockPos());
        assertEquals(1.0, hit.getLocation().x, EPSILON);
    }

    @Test
    void clipSelectsAStructureWhenItIsInFront() {
        final FakeLevel level = new FakeLevel(new BlockPos(3, 0, 0));
        final StructureVoxelView structure = view(Transform3d.identity(), 1.0,
                new IntBounds3(2, 0, 0, 4, 1, 1), 2, 0, 0);
        final ClipContext context = context(new Vec3(0.5, 0.5, 0.5), new Vec3(6.0, 0.5, 0.5));

        final BlockHitResult hit = StructureSelection.clip(level, context, List.of(structure));

        assertInstanceOf(StructureBlockHitResult.class, hit);
        assertEquals(2, ((StructureBlockHitResult) hit).voxelX());
        assertEquals(2.0, hit.getLocation().x, EPSILON);
    }

    private static ClipContext context(Vec3 from, Vec3 to) {
        return new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.empty());
    }

    private static StructureVoxelView view(Transform3d pose, double scale, IntBounds3 bounds,
                                           int... solid) {
        final Set<Long> cells = new HashSet<>();
        for (int i = 0; i + 2 < solid.length; i += 3) {
            cells.add(key(solid[i], solid[i + 1], solid[i + 2]));
        }
        return new StructureVoxelView() {
            @Override
            public long bodyId() {
                return 7L;
            }

            @Override
            public Transform3d pose() {
                return pose;
            }

            @Override
            public double blockScale() {
                return scale;
            }

            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return cells.contains(key(x, y, z));
            }

            @Override
            public BlockState blockState(int x, int y, int z) {
                return Blocks.STONE.defaultBlockState();
            }
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1fffff) << 42) | ((long) (y & 0x1fffff) << 21) | (z & 0x1fffff);
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
            return realBlock.equals(pos)
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
