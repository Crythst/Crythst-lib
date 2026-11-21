package io.mikaple.crythst.client.network;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.network.structure.StructurePosePayload;
import io.mikaple.crythst.network.structure.StructureSnapshot;
import io.mikaple.crythst.network.structure.StructureSnapshotPayload;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientStructureCacheInterpolationTest {
    private static final Identifier OVERWORLD =
            Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final long MS = 1_000_000L;
    private static final long TICK = 50L * MS;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clear() {
        ClientStructureCache.clear();
    }

    @Test
    void poseUpdatesAreInterpolatedWithOneTickDelay() {
        applySnapshotAt(poseX(0.0), 0L);
        applyPoseAt(poseX(1.0), TICK);

        assertEquals(0.0, ClientStructureCache.interpolatedPose(1L, TICK).position().x, 1.0e-9);
        assertEquals(0.5, ClientStructureCache.interpolatedPose(1L, TICK + TICK / 2)
                .position().x, 1.0e-9);
        assertEquals(1.0, ClientStructureCache.interpolatedPose(1L, 2 * TICK)
                .position().x, 1.0e-9);
        assertEquals(1, ClientStructureCache.interpolatedBodyCount());
    }

    @Test
    void networkTeleportSnaps() {
        applySnapshotAt(poseX(0.0), 0L);
        applyPoseAt(poseX(100.0), TICK);

        assertEquals(100.0, ClientStructureCache.interpolatedPose(1L, TICK)
                .position().x, 1.0e-9);
        assertEquals(100.0, ClientStructureCache.interpolatedPose(1L, TICK + TICK / 2)
                .position().x, 1.0e-9);
    }

    @Test
    void removalDropsTheTimeline() {
        applySnapshotAt(poseX(0.0), 0L);
        applyPoseAt(poseX(1.0), TICK);
        assertEquals(1, ClientStructureCache.interpolatedBodyCount());

        ClientStructureCache.applyRemove(new io.mikaple.crythst.network.structure.StructureRemovePayload(
                1L, new long[]{1L}));
        assertEquals(0, ClientStructureCache.interpolatedBodyCount());
        assertEquals(0.0, ClientStructureCache.interpolatedPose(1L, TICK).position().x, 1.0e-9);
    }

    private static void applySnapshotAt(Transform3d pose, long nanos) {
        final StructureSnapshot wire = new StructureSnapshot(
                1L, pose, new IntBounds3(0, 0, 0, 1, 1, 1),
                new int[]{1}, new int[]{1}, 1.0, 1.0, 1.0,
                new Vector3d(0.5, 0.5, 0.5));
        ClientStructureCache.apply(new StructureSnapshotPayload(
                1L, OVERWORLD, List.of(wire), 0, 1, true), nanos);
    }

    private static void applyPoseAt(Transform3d pose, long nanos) {
        ClientStructureCache.applyPose(new StructurePosePayload(1L, List.of(
                new StructurePosePayload.PoseUpdate(1L, pose))), nanos);
    }

    private static Transform3d poseX(double x) {
        return Transform3d.of(new Vector3d(x, 0.0, 0.0));
    }
}
