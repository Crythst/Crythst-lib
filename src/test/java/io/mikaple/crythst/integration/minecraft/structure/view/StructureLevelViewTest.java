package io.mikaple.crythst.integration.minecraft.structure.view;

import io.mikaple.crythst.api.sublevel.view.StructureBlockGetter;
import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.selection.StructureRaycaster;
import io.mikaple.crythst.integration.minecraft.selection.StructureVoxelShapes;
import io.mikaple.crythst.integration.minecraft.selection.StructureVoxelView;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureLevelViewTest {
    private static final double EPSILON = 1.0e-6;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftPhysics.init(new CrythstConfig());
    }

    @Test
    void localAndWorldCoordinatesRoundTripForRotatedScaledStructure() {
        final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(37.0))
                .rotateX(Math.toRadians(-12.0));
        final Transform3d pose = Transform3d.of(new Vector3d(12.0, 64.0, -7.0), rotation);
        try (Fixture fixture = fixture(pose, 0.5)) {
            final StructureLevelView view = new ServerStructureLevelView(fixture.structure, null, null);
            assertEquals(0.5, view.blockScale(), 0.0);
            assertEquals(fixture.structure.volume().bounds(), view.bounds());

            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 2; z++) {
                        final Vector3d expected = fixture.structure.blockCenter(x, y, z);
                        final Vector3d actual = view.voxelCenterToWorld(x, y, z, new Vector3d());
                        assertEquals(expected.x, actual.x, EPSILON);
                        assertEquals(expected.y, actual.y, EPSILON);
                        assertEquals(expected.z, actual.z, EPSILON);

                        final Vector3d voxel = view.worldToVoxel(actual, new Vector3d());
                        assertEquals(x, voxel.x, EPSILON);
                        assertEquals(y, voxel.y, EPSILON);
                        assertEquals(z, voxel.z, EPSILON);

                        assertEquals(BlockPos.containing(expected.x, expected.y, expected.z),
                                view.localBlockToWorld(new BlockPos(x, y, z)));
                    }
                }
            }

            final Vector3d local = new Vector3d(1.25, 2.5, -0.75);
            final Vector3d world = view.localToWorld(local, new Vector3d());
            final Vector3d expectedWorld = pose.transformPoint(local);
            assertEquals(expectedWorld.x, world.x, 1.0e-4);
            assertEquals(expectedWorld.y, world.y, 1.0e-4);
            assertEquals(expectedWorld.z, world.z, 1.0e-4);
            final Vector3d roundTrip = view.worldToLocal(world, new Vector3d());
            assertEquals(local.x, roundTrip.x, 1.0e-4);
            assertEquals(local.y, roundTrip.y, 1.0e-4);
            assertEquals(local.z, roundTrip.z, 1.0e-4);
        }
    }

    @Test
    void outsideBoundsAndUnloadedBlocksReadAsAir() {
        try (Fixture fixture = fixture(Transform3d.identity(), 1.0)) {
            final StructureLevelView view = new ServerStructureLevelView(fixture.structure, null, null);
            assertTrue(view.getBlockState(new BlockPos(-1, 0, 0)).isAir());
            assertTrue(view.getBlockState(new BlockPos(3, 0, 0)).isAir());
            assertTrue(view.getBlockState(new BlockPos(0, 2, 0)).isAir());
            assertTrue(view.getFluidState(new BlockPos(99, 99, 99)).isEmpty());
            assertNull(view.getBlockEntity(new BlockPos(99, 99, 99)));
            assertTrue(view.isEmptyBlock(new BlockPos(-5, 0, 0)));
            assertTrue(view.getBlockStates(new AABB(-10, -10, -10, -5, -5, -5))
                    .allMatch(BlockState::isAir));
            assertTrue(view.getBlockCollisions(null, new AABB(-10, -10, -10, -5, -5, -5)).isEmpty());
            assertNull(view.operationTarget(new BlockPos(0, 0, 0)),
                    "no auxiliary level means no native operation target");

            // A live block absent from the auxiliary copy is air even when the
            // volume says the cell is solid: the view must never load a chunk.
            final Map<BlockPos, ServerStructureLevelView.LiveBlock> live = new HashMap<>();
            final StructureLevelView liveView = new ServerStructureLevelView(
                    fixture.structure, null, null,
                    (structure, x, y, z) -> live.get(new BlockPos(x, y, z)), true);
            assertTrue(liveView.getBlockState(new BlockPos(0, 0, 0)).isAir());
            assertEquals(0, liveView.getBlockEntity(new BlockPos(0, 0, 0)) == null ? 0 : 1);
        }
    }

    @Test
    void fluidAndBlockEntityComeFromTheLiveAuxiliaryBlock() {
        try (Fixture fixture = fixture(Transform3d.identity(), 1.0)) {
            final BlockState water = Blocks.WATER.defaultBlockState();
            final BlockState chest = Blocks.CHEST.defaultBlockState();
            final BlockEntity chestEntity = new ChestBlockEntity(BlockPos.ZERO, chest);
            final Map<BlockPos, ServerStructureLevelView.LiveBlock> live = new HashMap<>();
            live.put(new BlockPos(2, 0, 0), new ServerStructureLevelView.LiveBlock(water, null));
            live.put(new BlockPos(1, 0, 0), new ServerStructureLevelView.LiveBlock(chest, chestEntity));

            final StructureLevelView view = new ServerStructureLevelView(
                    fixture.structure, null, null,
                    (structure, x, y, z) -> live.get(new BlockPos(x, y, z)), true);

            assertEquals(water, view.getBlockState(new BlockPos(2, 0, 0)));
            assertTrue(view.getFluidState(new BlockPos(2, 0, 0)).isSource());
            assertSame(chestEntity, view.getBlockEntity(new BlockPos(1, 0, 0)));
            assertNull(view.getBlockEntity(new BlockPos(0, 0, 0)));
        }
    }

    @Test
    void serverWritesUpdateVolumeStateAndCollider() {
        try (Fixture fixture = fixture(Transform3d.identity(), 1.0)) {
            final StructureLevelView view = new ServerStructureLevelView(fixture.structure, null, null);
            assertTrue(view.isWritable());

            final BlockState before = view.getBlockState(new BlockPos(1, 0, 0));
            assertEquals(Blocks.STONE.defaultBlockState(), before);
            final VoxelColliderData oldCollider = fixture.structure.collider();
            final long oldEpoch = view.epoch();

            final BlockState previous = view.setBlockAndUpdate(new BlockPos(1, 0, 0),
                    Blocks.OAK_PLANKS.defaultBlockState());
            assertEquals(before, previous);
            assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), view.getBlockState(new BlockPos(1, 0, 0)));
            assertEquals(Blocks.OAK_PLANKS.defaultBlockState(),
                    StructureBlockStates.get(fixture.structure, 1, 0, 0));
            assertTrue(fixture.structure.volume().isSolid(1, 0, 0));
            assertNotSame(oldCollider, fixture.structure.collider(),
                    "a write must rebuild the collider");
            assertTrue(view.epoch() > oldEpoch);
            assertTrue(view.isCurrent());

            assertTrue(view.getBlockState(new BlockPos(2, 0, 0)).isAir());
            assertTrue(view.setBlockAndUpdate(new BlockPos(2, 0, 0),
                    Blocks.STONE.defaultBlockState()).isAir());
            assertTrue(fixture.structure.volume().isSolid(2, 0, 0));
            assertEquals(Blocks.STONE.defaultBlockState(), view.getBlockState(new BlockPos(2, 0, 0)));

            assertEquals(Blocks.STONE.defaultBlockState(), view.removeBlock(new BlockPos(2, 0, 0), false));
            assertTrue(view.getBlockState(new BlockPos(2, 0, 0)).isAir());
            assertFalse(fixture.structure.volume().isSolid(2, 0, 0));

            assertNull(view.setBlock(new BlockPos(3, 0, 0),
                    Blocks.STONE.defaultBlockState(), 3, 0));
            assertNull(view.removeBlock(new BlockPos(-1, 0, 0), false));
        }
    }

    @Test
    void exactCollisionShapesUseStructureLocalNeighbours() {
        try (Fixture fixture = fixture(Transform3d.identity(), 1.0)) {
            final BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
            final Map<BlockPos, ServerStructureLevelView.LiveBlock> live = new HashMap<>();
            live.put(new BlockPos(1, 0, 0), new ServerStructureLevelView.LiveBlock(slab, null));
            final StructureLevelView view = new ServerStructureLevelView(
                    fixture.structure, null, null,
                    (structure, x, y, z) -> live.get(new BlockPos(x, y, z)), true);

            final List<VoxelShape> collisions = view.getBlockCollisions(null,
                    new AABB(1.0, 0.0, 0.0, 1.5, 1.0, 1.0));
            assertEquals(1, collisions.size(), "only the slab occupies a partial shape");
            final AABB bounds = collisions.get(0).bounds();
            assertEquals(1.0, bounds.minX, EPSILON);
            assertEquals(0.0, bounds.minY, EPSILON);
            assertEquals(0.0, bounds.minZ, EPSILON);
            assertEquals(2.0, bounds.maxX, EPSILON);
            assertEquals(0.5, bounds.maxY, EPSILON);
            assertEquals(1.0, bounds.maxZ, EPSILON);

            final StructureBlockGetter getter = view.blockGetter();
            assertEquals(slab, getter.getBlockState(new BlockPos(1, 0, 0)));
            assertEquals(fixture.structure.volume().bounds().minY(), getter.getMinY());

            final StructureVoxelView voxelView = ViewStructureVoxelView.of(view);
            final VoxelShape outline = StructureVoxelShapes.localShape(voxelView, 1, 0, 0);
            assertEquals(0.5, outline.max(net.minecraft.core.Direction.Axis.Y), EPSILON);

            assertNotNull(StructureRaycaster.nearest(List.of(voxelView),
                    new Vec3(1.5, 3.0, 0.5), new Vec3(1.5, -1.0, 0.5)));
        }
    }

    @Test
    void auxiliaryRegionMappingRoundTripsLocalVoxels() {
        final EmptyPhysicsLevel.RegionView region = new EmptyPhysicsLevel.RegionView(
                7L, 100, 0, 200, -4, 2, 6, 3, 4, 5);
        final BlockPos aux = region.toAuxiliaryVoxel(-4, 2, 6);
        assertEquals(new BlockPos(100, 0, 200), aux);
        assertEquals(new BlockPos(-4, 2, 6), region.toLocalVoxel(aux));
        assertEquals(new BlockPos(102, 3, 204),
                region.toAuxiliaryVoxel(-2, 5, 10));
        assertNull(region.toAuxiliaryVoxel(-5, 2, 6), "before volume X");
        assertNull(region.toAuxiliaryVoxel(-1, 2, 6), "after region X");
        assertNull(region.toAuxiliaryVoxel(-4, 6, 6), "above region Y");
        assertNull(region.toAuxiliaryVoxel(-4, 2, 11), "after region Z");
    }

    @Test
    void clientViewIsReadOnly() {
        try (Fixture fixture = fixture(Transform3d.identity(), 1.0)) {
            final ClientStructureLevelView view = ClientStructureLevelView.ofLive(
                    fixture.structure, null, fixture.structure.pose());
            assertFalse(view.isWritable());
            assertEquals(Blocks.STONE.defaultBlockState(),
                    view.getBlockState(new BlockPos(0, 0, 0)));
            assertNull(view.setBlock(new BlockPos(0, 0, 0),
                    Blocks.OAK_PLANKS.defaultBlockState(), 3, 0));
            assertNull(view.removeBlock(new BlockPos(0, 0, 0), false));
            assertNull(view.operationTarget(new BlockPos(0, 0, 0)));
            assertEquals(Blocks.STONE.defaultBlockState(),
                    view.getBlockState(new BlockPos(0, 0, 0)));
        }
    }

    @Test
    void networkSnapshotViewIsReadOnlyAndUsesSnapshotCells() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 1, 1);
        final int stoneId = Block.getId(Blocks.STONE.defaultBlockState());
        final StructureSnapshot wire = new StructureSnapshot(
                42L, Transform3d.identity(), bounds,
                new int[]{stoneId}, new int[]{1, 0},
                1.0, 1.0, 1000.0, new Vector3d(0.5, 0.5, 0.5));
        final Identifier dimension = Identifier.fromNamespaceAndPath("crythst", "view_test");
        assertTrue(ClientStructureCache.apply(new StructureSnapshotPayload(
                1L, dimension, List.of(wire), 0, 1, true)));
        try {
            final ClientStructureSnapshot snapshot = ClientStructureCache.get(42L);
            assertNotNull(snapshot);
            final ClientStructureLevelView view = ClientStructureLevelView.ofSnapshot(snapshot, null);
            assertEquals(42L, view.bodyId());
            assertEquals(Blocks.STONE.defaultBlockState(), view.getBlockState(new BlockPos(0, 0, 0)));
            assertTrue(view.getBlockState(new BlockPos(1, 0, 0)).isAir());
            assertTrue(view.getBlockState(new BlockPos(5, 0, 0)).isAir());
            assertNull(view.setBlock(new BlockPos(0, 0, 0),
                    Blocks.OAK_PLANKS.defaultBlockState(), 3, 0));
            assertNull(view.removeBlock(new BlockPos(0, 0, 0), false));
            assertTrue(view.isCurrent());
        } finally {
            ClientStructureCache.clear();
        }
    }

    private static Fixture fixture(Transform3d pose, double blockScale) {
        final PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults());
        final MutableVoxelVolume volume = new MutableVoxelVolume(new IntBounds3(0, 0, 0, 3, 2, 2));
        solid(volume, 0, 0, 0);
        solid(volume, 1, 0, 0);
        solid(volume, 0, 1, 0);
        final PhysicsStructure structure = new PhysicsStructure(world,
                StructureDef.builder(volume)
                        .pose(pose)
                        .colliderOptions(VoxelColliderOptions.DEFAULT.withBlockScale(blockScale))
                        .build());
        final BlockState stone = Blocks.STONE.defaultBlockState();
        StructureBlockStates.set(structure, 0, 0, 0, stone);
        StructureBlockStates.set(structure, 1, 0, 0, stone);
        StructureBlockStates.set(structure, 0, 1, 0, stone);
        return new Fixture(world, structure);
    }

    private static void solid(MutableVoxelVolume volume, int x, int y, int z) {
        volume.setBlock(x, y, z, true, 1000.0, PhysicsMaterial.STONE);
    }

    private static final class Fixture implements AutoCloseable {
        private final PhysicsWorld world;
        private final PhysicsStructure structure;

        private Fixture(PhysicsWorld world, PhysicsStructure structure) {
            this.world = world;
            this.structure = structure;
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
