package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.client.render.structure.selection.StructureOutlineTarget;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.interaction.StructurePlacementMath;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void slabHitUsesTheShapeSurfaceInsteadOfTheCellBoundary() {
        final BlockState slab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        final StructureVoxelView view = view(Transform3d.identity(), 1.0,
                new IntBounds3(0, 0, 0, 1, 1, 1), slab, 0, 0, 0);

        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view), new Vec3(0.5, 3.0, 0.5), new Vec3(0.5, -1.0, 0.5));

        assertNotNull(hit);
        assertEquals(0, hit.voxelX());
        assertEquals(0, hit.voxelY());
        assertEquals(0, hit.voxelZ());
        assertEquals(Direction.UP, hit.localFace());
        assertEquals(Direction.UP, hit.getDirection());
        assertEquals(2.5, hit.distance(), EPSILON);
        assertEquals(0.5, hit.getLocation().y, EPSILON);
        assertEquals(0.5, hit.getLocation().x, EPSILON);
        assertEquals(0.5, hit.getLocation().z, EPSILON);
        assertEquals(new BlockPos(0, 0, 0), hit.getBlockPos());

        // The cell boundary is at y == 1.0 (distance 2.0). A reach that ends
        // above the slab's top surface must not select the cell.
        assertNull(StructureRaycaster.nearest(List.of(view),
                new Vec3(0.5, 3.0, 0.5), new Vector3d(0.0, -1.0, 0.0), 2.2));
        assertOutlineTargetMatches(hit);
    }

    @Test
    void slabEmptyHalfDoesNotSelectTheCell() {
        final BlockState slab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        final StructureVoxelView view = view(Transform3d.identity(), 1.0,
                new IntBounds3(0, 0, 0, 1, 1, 1), slab, 0, 0, 0);

        assertNull(StructureRaycaster.nearest(
                List.of(view), new Vec3(-1.0, 0.75, 0.5), new Vec3(3.0, 0.75, 0.5)));
        assertNull(StructureRaycaster.nearest(
                List.of(view), new Vec3(0.5, 0.75, -1.0), new Vec3(0.5, 0.75, 3.0)));
    }

    @Test
    void slabEmptyHalfIsSkippedForTheNextActualShape() {
        final BlockState slab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        final StructureVoxelView view = view(Transform3d.identity(), 1.0,
                new IntBounds3(0, 0, 0, 2, 1, 1),
                (x, y, z) -> x == 0 ? slab : Blocks.STONE.defaultBlockState(),
                0, 0, 0, 1, 0, 0);

        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view), new Vec3(-1.0, 0.75, 0.5), new Vec3(3.0, 0.75, 0.5));

        assertNotNull(hit);
        assertEquals(1, hit.voxelX());
        assertEquals(0, hit.voxelY());
        assertEquals(0, hit.voxelZ());
        assertEquals(Direction.WEST, hit.localFace());
        assertEquals(2.0, hit.distance(), EPSILON);
        assertEquals(1.0, hit.getLocation().x, EPSILON);
    }

    @Test
    void stairsExposeOnlyTheirOccupiedHalf() {
        final BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        final StructureVoxelView view = view(Transform3d.identity(), 1.0,
                new IntBounds3(0, 0, 0, 1, 1, 1), stairs, 0, 0, 0);

        final VoxelShape shape = StructureVoxelShapes.localShape(view, 0, 0, 0);
        final double occupiedZ = covers(shape, 0.5, 0.75, 0.25) ? 0.25 : 0.75;
        final double emptyZ = occupiedZ == 0.25 ? 0.75 : 0.25;
        assertTrue(covers(shape, 0.5, 0.75, occupiedZ), "stairs should have an upper half");
        assertFalse(covers(shape, 0.5, 0.75, emptyZ), "stairs should have an empty upper half");

        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(view), new Vec3(0.5, 2.0, occupiedZ), new Vec3(0.5, 0.5, occupiedZ));

        assertNotNull(hit);
        assertEquals(0, hit.voxelX());
        assertEquals(0, hit.voxelY());
        assertEquals(0, hit.voxelZ());
        assertEquals(Direction.UP, hit.localFace());
        assertEquals(1.0, hit.getLocation().y, EPSILON);
        assertEquals(1.0, hit.distance(), EPSILON);

        // Stop above the bottom slab so only the empty upper half is probed.
        assertNull(StructureRaycaster.nearest(
                List.of(view), new Vec3(0.5, 2.0, emptyZ), new Vec3(0.5, 0.6, emptyZ)));
    }

    @Test
    void allSixFacesReportTheShapeSurfaceAndTheSameVoxel() {
        final StructureVoxelView view = view(Transform3d.identity(), 1.0,
                new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);
        final Direction[] faces = {
                Direction.WEST, Direction.EAST, Direction.DOWN,
                Direction.UP, Direction.NORTH, Direction.SOUTH
        };
        final Vec3[] from = {
                new Vec3(-2.0, 0.5, 0.5), new Vec3(2.0, 0.5, 0.5),
                new Vec3(0.5, -2.0, 0.5), new Vec3(0.5, 2.0, 0.5),
                new Vec3(0.5, 0.5, -2.0), new Vec3(0.5, 0.5, 2.0)
        };
        final Vec3[] to = {
                new Vec3(2.0, 0.5, 0.5), new Vec3(-2.0, 0.5, 0.5),
                new Vec3(0.5, 2.0, 0.5), new Vec3(0.5, -2.0, 0.5),
                new Vec3(0.5, 0.5, 2.0), new Vec3(0.5, 0.5, -2.0)
        };
        final Vec3[] locations = {
                new Vec3(0.0, 0.5, 0.5), new Vec3(1.0, 0.5, 0.5),
                new Vec3(0.5, 0.0, 0.5), new Vec3(0.5, 1.0, 0.5),
                new Vec3(0.5, 0.5, 0.0), new Vec3(0.5, 0.5, 1.0)
        };

        for (int i = 0; i < faces.length; i++) {
            final StructureBlockHitResult hit = StructureRaycaster.nearest(
                    List.of(view), from[i], to[i]);

            assertNotNull(hit, "face " + faces[i]);
            assertEquals(0, hit.voxelX(), "face " + faces[i]);
            assertEquals(0, hit.voxelY(), "face " + faces[i]);
            assertEquals(0, hit.voxelZ(), "face " + faces[i]);
            assertEquals(faces[i], hit.localFace(), "face " + faces[i]);
            assertEquals(faces[i], hit.getDirection(), "face " + faces[i]);
            assertEquals(faces[i], hit.worldFace(), "face " + faces[i]);
            assertEquals(from[i].distanceTo(locations[i]), hit.distance(), EPSILON,
                    "face " + faces[i]);
            assertEquals(0.0, hit.getLocation().distanceToSqr(locations[i]), 1.0e-9,
                    "face " + faces[i]);
            assertEquals(faces[i].getStepX(), hit.adjacentX(), "face " + faces[i]);
            assertEquals(faces[i].getStepY(), hit.adjacentY(), "face " + faces[i]);
            assertEquals(faces[i].getStepZ(), hit.adjacentZ(), "face " + faces[i]);
            assertOutlineTargetMatches(hit);
        }
    }

    @Test
    void rotatedAndScaledPoseKeepsHitVoxelFaceAndOutlineConsistent() {
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 64.0, -3.0),
                new Quaterniond().rotateY(Math.PI / 2.0).rotateX(0.3));
        final double scale = 2.0;
        final StructureVoxelView view = view(pose, scale,
                new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);

        final Vector3d localFaceCentre = new Vector3d(1.0, 0.5, 0.5).mul(scale);
        final Vector3d worldPoint = pose.transformPoint(localFaceCentre);
        final Vector3d worldNormal = pose.transformDirection(new Vector3d(1.0, 0.0, 0.0)).normalize();
        final Vec3 from = new Vec3(
                worldPoint.x + worldNormal.x * 4.0,
                worldPoint.y + worldNormal.y * 4.0,
                worldPoint.z + worldNormal.z * 4.0);
        final Vec3 to = new Vec3(worldPoint.x, worldPoint.y, worldPoint.z);

        final StructureBlockHitResult hit = StructureRaycaster.nearest(List.of(view), from, to);

        assertNotNull(hit);
        assertEquals(0, hit.voxelX());
        assertEquals(0, hit.voxelY());
        assertEquals(0, hit.voxelZ());
        assertEquals(Direction.EAST, hit.localFace());
        assertEquals(StructurePlacementMath.worldFace(pose, Direction.EAST), hit.getDirection());
        assertEquals(4.0, hit.distance(), EPSILON);
        assertEquals(0.0, hit.getLocation().distanceToSqr(to), 1.0e-9);
        assertEquals(scale, hit.blockScale(), EPSILON);
        assertOutlineTargetMatches(hit);
    }

    @Test
    void scaleChangesDistanceOrderingAndReachAreInWorldSpace() {
        final StructureVoxelView near = view(Transform3d.of(new Vector3d(1.0, 0.0, 0.0)),
                0.5, new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);
        final StructureVoxelView far = view(Transform3d.of(new Vector3d(4.0, 0.0, 0.0)),
                3.0, new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);

        final StructureBlockHitResult hit = StructureRaycaster.nearest(
                List.of(far, near), new Vec3(0.0, 0.25, 0.25), new Vec3(10.0, 0.25, 0.25));

        assertNotNull(hit);
        assertEquals(1.0, hit.distance(), EPSILON);
        assertEquals(1.0, hit.getLocation().x, EPSILON);
        assertEquals(0.5, hit.blockScale(), EPSILON);
        assertEquals(7L, hit.bodyId());

        // The far structure occupies [4, 7] despite being one voxel large and
        // scaled by 3; a world reach of 2 must not see it.
        assertNull(StructureRaycaster.nearest(List.of(far),
                new Vec3(0.0, 0.25, 0.25), new Vector3d(1.0, 0.0, 0.0), 2.0));
        assertNotNull(StructureRaycaster.nearest(List.of(near),
                new Vec3(0.0, 0.25, 0.25), new Vector3d(1.0, 0.0, 0.0), 2.0));
    }

    @Test
    void clipLetsARealBlockWinOverAScaledStructureBehindIt() {
        final FakeLevel level = new FakeLevel(new BlockPos(2, 0, 0));
        final StructureVoxelView structure = view(Transform3d.of(new Vector3d(3.0, 0.0, 0.0)),
                2.0, new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);
        final ClipContext context = context(new Vec3(0.5, 0.5, 0.5),
                new Vec3(8.0, 0.5, 0.5));

        final BlockHitResult hit = StructureSelection.clip(level, context, List.of(structure));

        assertEquals(HitResult.Type.BLOCK, hit.getType());
        assertFalse(hit instanceof StructureBlockHitResult);
        assertEquals(new BlockPos(2, 0, 0), hit.getBlockPos());
        assertEquals(2.0, hit.getLocation().x, EPSILON);
    }

    @Test
    void clipSelectsAScaledStructureInFrontOfARealBlock() {
        final FakeLevel level = new FakeLevel(new BlockPos(5, 0, 0));
        final StructureVoxelView structure = view(Transform3d.of(new Vector3d(1.0, 0.0, 0.0)),
                2.0, new IntBounds3(0, 0, 0, 1, 1, 1), 0, 0, 0);
        final ClipContext context = context(new Vec3(0.0, 0.5, 0.5),
                new Vec3(8.0, 0.5, 0.5));

        final BlockHitResult hit = StructureSelection.clip(level, context, List.of(structure));

        assertInstanceOf(StructureBlockHitResult.class, hit);
        final StructureBlockHitResult structureHit = (StructureBlockHitResult) hit;
        assertEquals(0, structureHit.voxelX());
        assertEquals(1.0, structureHit.getLocation().x, EPSILON);
        assertOutlineTargetMatches(structureHit);
    }

    private static void assertOutlineTargetMatches(StructureBlockHitResult hit) {
        final StructureOutlineTarget target = StructureOutlineTarget.from(hit);
        assertEquals(hit.bodyId(), target.bodyId());
        assertEquals(hit.voxelX(), target.voxelX());
        assertEquals(hit.voxelY(), target.voxelY());
        assertEquals(hit.voxelZ(), target.voxelZ());
        assertEquals(hit.blockScale(), target.blockScale(), EPSILON);
        assertEquals(hit.blockState(), target.state());
        assertEquals(0.0, target.worldHit().distance(hit.worldHit()), EPSILON);
    }

    private static boolean covers(VoxelShape shape, double x, double y, double z) {
        for (AABB box : shape.toAabbs()) {
            if (x >= box.minX && x <= box.maxX
                    && y >= box.minY && y <= box.maxY
                    && z >= box.minZ && z <= box.maxZ) {
                return true;
            }
        }
        return false;
    }

    private static ClipContext context(Vec3 from, Vec3 to) {
        return new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.empty());
    }

    private static StructureVoxelView view(Transform3d pose, double scale, IntBounds3 bounds,
                                           int... solid) {
        return view(pose, scale, bounds, (x, y, z) -> Blocks.STONE.defaultBlockState(), solid);
    }

    private static StructureVoxelView view(Transform3d pose, double scale, IntBounds3 bounds,
                                           BlockState state, int... solid) {
        return view(pose, scale, bounds, (x, y, z) -> state, solid);
    }

    @FunctionalInterface
    private interface BlockStateLookup {
        BlockState at(int x, int y, int z);
    }

    private static StructureVoxelView view(Transform3d pose, double scale, IntBounds3 bounds,
                                           BlockStateLookup lookup, int... solid) {
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
                return lookup.at(x, y, z);
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
