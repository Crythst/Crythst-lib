package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure placement-validation matrix for structure-local voxels. No server,
 * physics body or client is needed: the virtual level is backed by a test
 * volume, and entity obstruction is expressed with world AABBs.
 */
class StructurePlacementValidationTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void fullBlockIsRejectedWhenItIntersectsAPlayerButAllowedWhenClear() {
        final Scene scene = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        scene.solid(1, 0, 1);

        assertFailure(scene.validate(Blocks.STONE.defaultBlockState(), 1, 1, 1, 1, 0, 1,
                List.of(new AABB(1.1, 1.1, 1.1, 1.9, 1.9, 1.9))),
                StructurePlacementValidation.Failure.OBSTRUCTED);

        assertTrue(scene.validate(Blocks.STONE.defaultBlockState(), 1, 1, 1, 1, 0, 1,
                List.of(new AABB(8.0, 8.0, 8.0, 9.0, 9.0, 9.0))).valid());
    }

    @Test
    void slabUsesItsExactCollisionShapeNotTheFullVoxel() {
        final Scene scene = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        scene.solid(1, 0, 1);

        final BlockState bottomSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);

        assertTrue(scene.validate(bottomSlab, 1, 1, 1, 1, 0, 1,
                List.of(new AABB(1.1, 1.6, 1.1, 1.9, 1.9, 1.9))).valid());
        assertFailure(scene.validate(bottomSlab, 1, 1, 1, 1, 0, 1,
                List.of(new AABB(1.1, 1.1, 1.1, 1.9, 1.4, 1.9))),
                StructurePlacementValidation.Failure.OBSTRUCTED);

        // The same upper-half entity box intersects a full cube, proving the
        // slab result came from the exact shape rather than the voxel AABB.
        assertFailure(scene.validate(Blocks.STONE.defaultBlockState(), 1, 1, 1, 1, 0, 1,
                List.of(new AABB(1.1, 1.6, 1.1, 1.9, 1.9, 1.9))),
                StructurePlacementValidation.Failure.OBSTRUCTED);
    }

    @Test
    void torchRequiresTheStructureNeighbourBelowItToBeSolid() {
        final Scene supported = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        supported.solid(1, 0, 1);
        assertTrue(supported.validate(Blocks.TORCH.defaultBlockState(),
                1, 1, 1, 1, 0, 1, List.of()).valid());

        final Scene floating = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        floating.solid(0, 1, 1);
        assertFailure(floating.validate(Blocks.TORCH.defaultBlockState(),
                        1, 1, 1, 0, 1, 1, List.of()),
                StructurePlacementValidation.Failure.CANNOT_SURVIVE);
    }

    @Test
    void onlyReplaceableCellsMayBeOverwritten() {
        final Scene water = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        water.solid(1, 0, 1);
        water.state(1, 1, 1, Blocks.WATER.defaultBlockState());
        assertTrue(water.validate(Blocks.STONE.defaultBlockState(),
                1, 1, 1, 1, 0, 1, List.of()).valid());

        final Scene grass = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        grass.solid(1, 0, 1);
        grass.state(1, 1, 1, Blocks.SHORT_GRASS.defaultBlockState());
        assertTrue(grass.validate(Blocks.STONE.defaultBlockState(),
                1, 1, 1, 1, 0, 1, List.of()).valid());

        final Scene solid = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        solid.solid(1, 0, 1);
        solid.solid(1, 1, 1);
        assertFailure(solid.validate(Blocks.STONE.defaultBlockState(),
                        1, 1, 1, 1, 0, 1, List.of()),
                StructurePlacementValidation.Failure.SOLID_TARGET);

        final Scene nonReplaceable = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        nonReplaceable.solid(1, 0, 1);
        nonReplaceable.state(1, 1, 1, Blocks.STONE.defaultBlockState());
        assertFailure(nonReplaceable.validate(Blocks.STONE.defaultBlockState(),
                        1, 1, 1, 1, 0, 1, List.of()),
                StructurePlacementValidation.Failure.NOT_REPLACEABLE);
    }

    @Test
    void arbitraryInteriorEmptyCellsAreNotSupported() {
        final Scene scene = new Scene(new IntBounds3(0, 0, 0, 5, 5, 5));
        scene.solid(0, 0, 0);

        assertFailure(scene.validate(Blocks.STONE.defaultBlockState(),
                        2, 2, 2, 2, 1, 2, List.of()),
                StructurePlacementValidation.Failure.UNSUPPORTED);

        // Air does not become a valid "replaceable cell" merely because some
        // unrelated neighbour is solid: the clicked support voxel must be the
        // exposed face.
        scene.solid(3, 2, 2);
        assertFailure(scene.validate(Blocks.STONE.defaultBlockState(),
                        2, 2, 2, 2, 1, 2, List.of()),
                StructurePlacementValidation.Failure.UNSUPPORTED);

        scene.solid(2, 1, 2);
        assertTrue(scene.validate(Blocks.STONE.defaultBlockState(),
                2, 2, 2, 2, 1, 2, List.of()).valid());
    }

    @Test
    void rotatedAndScaledStructuresUseTheWorldShapeForObstruction() {
        final Scene scene = new Scene(new IntBounds3(0, 0, 0, 4, 4, 4));
        scene.solid(1, 0, 1);
        final Transform3d pose = new Transform3d(new Vector3d(10.0, 20.0, 30.0),
                new Quaterniond().rotateY(Math.toRadians(90.0)));
        final double scale = 2.0;

        final AABB inside = StructurePlacementValidation.worldVoxelBounds(pose, 1, 1, 1, scale)
                .contract(0.4, 0.4, 0.4);
        assertFailure(scene.validate(Blocks.STONE.defaultBlockState(), 1, 1, 1, 1, 0, 1,
                        List.of(inside), pose, scale),
                StructurePlacementValidation.Failure.OBSTRUCTED);

        final AABB outside = inside.move(100.0, 0.0, 0.0);
        assertTrue(scene.validate(Blocks.STONE.defaultBlockState(), 1, 1, 1, 1, 0, 1,
                List.of(outside), pose, scale).valid());

        // Support is resolved in local voxel space even when the voxel's world
        // orientation and size are transformed.
        assertTrue(scene.validate(Blocks.TORCH.defaultBlockState(),
                1, 1, 1, 1, 0, 1, List.of(), pose, scale).valid());
    }

    private static void assertFailure(StructurePlacementValidation.Result result,
                                      StructurePlacementValidation.Failure expected) {
        assertFalse(result.valid());
        assertEquals(expected, result.failure());
    }

    /**
     * A tiny structure scene: solid cells plus optional block states. Cells
     * without an explicit state use stone when solid and air otherwise.
     */
    private static final class Scene {
        private final MutableVoxelVolume volume;
        private final Map<BlockPos, BlockState> states = new HashMap<>();

        private Scene(IntBounds3 bounds) {
            this.volume = new MutableVoxelVolume(bounds);
        }

        private void solid(int x, int y, int z) {
            volume.setBlock(x, y, z, true, 1000.0, PhysicsMaterial.STONE);
        }

        private void state(int x, int y, int z, BlockState state) {
            states.put(new BlockPos(x, y, z), state);
        }

        private StructurePlacementValidation.Result validate(BlockState state,
                                                             int tx, int ty, int tz,
                                                             int sx, int sy, int sz,
                                                             List<AABB> entities) {
            return validate(state, tx, ty, tz, sx, sy, sz, entities,
                    Transform3d.IDENTITY, 1.0);
        }

        private StructurePlacementValidation.Result validate(BlockState state,
                                                             int tx, int ty, int tz,
                                                             int sx, int sy, int sz,
                                                             List<AABB> entities,
                                                             Transform3d pose,
                                                             double scale) {
            final StructurePlacementLevel level = new StructurePlacementLevel(
                    null, pose, scale, volume.bounds(), this::stateAt);
            return StructurePlacementValidation.validate(state, volume, level,
                    tx, ty, tz, sx, sy, sz, entities, pose, scale);
        }

        private BlockState stateAt(int x, int y, int z) {
            final BlockState explicit = states.get(new BlockPos(x, y, z));
            if (explicit != null) {
                return explicit;
            }
            return volume.bounds().contains(x, y, z) && volume.isSolid(x, y, z)
                    ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }
    }
}
