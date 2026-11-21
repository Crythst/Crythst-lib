package io.mikaple.crythst.client.render.structure.batch;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureBatchSnapshotsTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearClientCache() {
        ClientStructureCache.clear();
    }

    @Test
    void serverSnapshotConvertsToBatchView() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState air = Blocks.AIR.defaultBlockState();
        final IntBounds3 bounds = new IntBounds3(10, 20, 30, 12, 21, 32);
        // Order is ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX).
        final BlockState[] blocks = {stone, air, air, stone};

        final StructureBlockSnapshots.Snapshot server =
                StructureBlockSnapshots.create(7L, bounds, blocks);
        final StructureBatchSnapshots.Snapshot view =
                StructureBatchSnapshots.fromServer(server);

        assertEquals(7L, view.bodyId());
        assertEquals(10, view.minX());
        assertEquals(20, view.minY());
        assertEquals(30, view.minZ());
        assertEquals(2, view.sizeX());
        assertEquals(1, view.sizeY());
        assertEquals(2, view.sizeZ());
        assertEquals(2, view.nonAirBlocks());
        assertEquals(server.revision(), view.contentRevision());
        assertEquals(stone, view.stateAt(10, 20, 30));
        assertTrue(view.stateAt(10, 20, 31).isAir());
        assertEquals(stone, view.stateAt(11, 20, 31));
    }

    @Test
    void clientSnapshotConvertsToBatchViewAndWorldBounds() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final StructureSnapshot wire = new StructureSnapshot(
                42L,
                Transform3d.of(new Vector3d(1.0, 2.0, 3.0)),
                new IntBounds3(0, 0, 0, 2, 1, 1),
                new int[]{Block.getId(stone)},
                new int[]{1, 0},
                1.0,
                1.0,
                100.0,
                new Vector3d(0.5, 0.5, 0.5));
        ClientStructureCache.apply(new StructureSnapshotPayload(List.of(wire), 0, 1, true));

        final ClientStructureSnapshot source = ClientStructureCache.get(42L);
        final StructureBatchSnapshots.Snapshot view =
                StructureBatchSnapshots.fromClient(source);

        assertEquals(42L, view.bodyId());
        assertEquals(0, view.minX());
        assertEquals(2, view.sizeX());
        assertEquals(1, view.nonAirBlocks());
        assertEquals(source.geometryRevision(), view.contentRevision());
        assertEquals(stone, view.stateAt(0, 0, 0));
        assertTrue(view.stateAt(1, 0, 0).isAir());

        final Aabb world = StructureBatchSnapshots.worldBounds(
                view, source.pose(), source.blockScale(), source.centerOfMassOffset());
        assertEquals(0.5, world.min().x, 1.0e-12);
        assertEquals(1.5, world.min().y, 1.0e-12);
        assertEquals(2.5, world.min().z, 1.0e-12);
        assertEquals(2.5, world.max().x, 1.0e-12);
        assertEquals(2.5, world.max().y, 1.0e-12);
        assertEquals(3.5, world.max().z, 1.0e-12);
    }

    @Test
    void networkGeometryIsReusedAcrossPoseOnlyUpdates() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final StructureSnapshot firstWire = new StructureSnapshot(
                5L,
                Transform3d.of(new Vector3d(0, 0, 0)),
                new IntBounds3(0, 0, 0, 2, 1, 1),
                new int[]{Block.getId(stone)},
                new int[]{1, 0},
                1.0,
                1.0,
                10.0,
                new Vector3d(0.5, 0.5, 0.5));
        ClientStructureCache.apply(new StructureSnapshotPayload(List.of(firstWire), 0, 1, true));

        final NetworkStructureSource source = new NetworkStructureSource();
        source.beginFrame();
        final StructureBatchSnapshots.Snapshot first =
                source.geometry(ClientStructureCache.get(5L));

        ClientStructureCache.applyPose(new StructurePosePayload(List.of(
                new StructurePosePayload.PoseUpdate(5L,
                        Transform3d.of(new Vector3d(10, 0, 0))))));
        source.beginFrame();
        final StructureBatchSnapshots.Snapshot afterPose =
                source.geometry(ClientStructureCache.get(5L));
        assertSame(first, afterPose, "pose-only updates must reuse cached geometry");

        final StructureSnapshot secondWire = new StructureSnapshot(
                5L,
                Transform3d.of(new Vector3d(10, 0, 0)),
                new IntBounds3(0, 0, 0, 2, 1, 1),
                new int[]{Block.getId(stone)},
                new int[]{1, 1},
                1.0,
                1.0,
                10.0,
                new Vector3d(0.5, 0.5, 0.5));
        ClientStructureCache.apply(new StructureSnapshotPayload(List.of(secondWire), 0, 1, true));
        source.beginFrame();
        final StructureBatchSnapshots.Snapshot afterGeometry =
                source.geometry(ClientStructureCache.get(5L));
        assertNotSame(afterPose, afterGeometry, "a new block volume must rebuild geometry");
        assertEquals(2, afterGeometry.nonAirBlocks());
    }
}
