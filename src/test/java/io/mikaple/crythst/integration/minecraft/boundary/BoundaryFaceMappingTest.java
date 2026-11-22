package io.mikaple.crythst.integration.minecraft.boundary;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryFaceMappingTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftPhysics.init(new CrythstConfig());
    }

    @Test
    void shellContainsOnlyFacesThatLeaveTheVolume() {
        try (Fixture fixture = fixture(Transform3d.identity(), 1.0)) {
            final StructureBoundaryIndex index = StructureBoundaryIndex.build(fixture.structure);
            assertEquals(10, index.faces().size(),
                    "two adjacent voxels have 10 external faces, not 12");
            assertTrue(index.faces().contains(new BoundaryFace(0, 0, 0, Direction.WEST)));
            assertTrue(index.faces().contains(new BoundaryFace(1, 0, 0, Direction.EAST)));
            assertFalse(index.faces().contains(new BoundaryFace(0, 0, 0, Direction.EAST)),
                    "the face between the two voxels is internal");
            assertTrue(index.matches(fixture.structure));
        }
    }

    @Test
    void faceHostPositionsAreVoxelCentresInHostSpace() {
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 64.0, 10.0));
        try (Fixture fixture = fixture(pose, 1.0)) {
            final PhysicsStructure structure = fixture.structure;
            assertEquals(new BlockPos(10, 64, 10),
                    BoundaryFaceMapping.hostBlock(structure, 0, 0, 0));
            assertEquals(new BlockPos(12, 64, 10),
                    BoundaryFaceMapping.hostBlockOutside(
                            structure, new BoundaryFace(1, 0, 0, Direction.EAST)));
            assertEquals(new BlockPos(10, 65, 10),
                    BoundaryFaceMapping.hostBlockOutside(
                            structure, new BoundaryFace(0, 0, 0, Direction.UP)));
        }
    }

    @Test
    void localAndWorldDirectionsRoundTripForRotatedStructure() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(-4.0, 80.0, 3.0),
                new Quaterniond().rotateY(Math.toRadians(37.0))
                        .rotateX(Math.toRadians(-18.0)));
        try (Fixture fixture = fixture(pose, 1.0)) {
            for (Direction local : Direction.values()) {
                final Direction world = BoundaryFaceMapping.worldDirection(
                        fixture.structure, local);
                assertEquals(local, BoundaryFaceMapping.localDirection(
                                fixture.structure, world),
                        "direction mapping must round-trip for " + local);
            }
        }
    }

    @Test
    void touchingDistanceAccountsForBlockScale() {
        assertEquals(1.25, BoundaryFaceMapping.touchingDistance(1.0), 1.0e-9);
        assertEquals(1.75, BoundaryFaceMapping.touchingDistance(0.5), 1.0e-9);
        assertEquals(1, BoundaryFaceMapping.searchRadius(2.0));
        assertEquals(2, BoundaryFaceMapping.searchRadius(0.5));
    }

    @Test
    void localPointMapsToAuxiliaryVoxelCentreWithoutExposingAuxCoordinates() {
        final io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel.RegionView region =
                new io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel.RegionView(
                        1L, 100, 0, 200, -4, 2, 6, 3, 4, 5);
        final net.minecraft.world.phys.Vec3 point = region.toAuxiliaryPoint(-4, 2, 6);
        assertEquals(100.5, point.x, 1.0e-9);
        assertEquals(0.5, point.y, 1.0e-9);
        assertEquals(200.5, point.z, 1.0e-9);
    }

    private static Fixture fixture(Transform3d pose, double blockScale) {
        final PhysicsWorld world = BulletPhysicsEngine.create()
                .createWorld(WorldSettings.defaults());
        final MutableVoxelVolume volume = new MutableVoxelVolume(new IntBounds3(0, 0, 0, 2, 1, 1));
        volume.setBlock(0, 0, 0, true, 1000.0, PhysicsMaterial.STONE);
        volume.setBlock(1, 0, 0, true, 1000.0, PhysicsMaterial.STONE);
        final PhysicsStructure structure = new PhysicsStructure(world,
                StructureDef.builder(volume)
                        .pose(pose)
                        .colliderOptions(io.mikaple.crythst.physics.voxel.VoxelColliderOptions
                                .DEFAULT.withBlockScale(blockScale))
                        .build());
        final BlockState stone = Blocks.STONE.defaultBlockState();
        StructureBlockStates.set(structure, 0, 0, 0, stone);
        StructureBlockStates.set(structure, 1, 0, 0, stone);
        return new Fixture(world, structure, List.of(stone));
    }

    private static final class Fixture implements AutoCloseable {
        private final PhysicsWorld world;
        private final PhysicsStructure structure;
        @SuppressWarnings("unused")
        private final List<BlockState> states;

        private Fixture(PhysicsWorld world, PhysicsStructure structure, List<BlockState> states) {
            this.world = world;
            this.structure = structure;
            this.states = states;
        }

        @Override
        public void close() {
            StructureBlockStates.clear(structure);
            if (!structure.closed()) {
                structure.close();
            }
            world.close();
        }
    }
}
