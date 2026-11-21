package io.mikaple.crythst.integration.minecraft.entity.projection;

import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureEntityProjectionTest {
    private static final double EPS = 1.0e-9;

    @Test
    void regionAnchorRemovesTheVolumeMinimumFromTheBase() {
        final IntBounds3 bounds = new IntBounds3(-3, -2, 5, 4, 3, 9);
        final StructureEntityProjection.RegionAnchor anchor =
                StructureEntityProjection.RegionAnchor.ofRegion(100, 0, 200, bounds);

        assertEquals(103, anchor.originX(), "originX = baseX - volumeMinX");
        assertEquals(2, anchor.originY(), "originY = baseY - volumeMinY");
        assertEquals(195, anchor.originZ(), "originZ = baseZ - volumeMinZ");

        // Volume voxel (1, 2, 3) is copied to the region base plus the offset
        // from the volume minimum.
        final Vec3 voidPos = new Vec3(
                100 + (1 - bounds.minX()) + 0.5,
                0 + (2 - bounds.minY()) + 0.5,
                200 + (3 - bounds.minZ()) + 0.5);
        final Vec3 world = StructureEntityProjection.projectPosition(
                Transform3d.identity(), 1.0, anchor, voidPos);

        assertEquals(1.5, world.x, EPS);
        assertEquals(2.5, world.y, EPS);
        assertEquals(3.5, world.z, EPS);
    }

    @Test
    void identityPoseKeepsVoidOffsetUnchanged() {
        final StructureEntityProjection.RegionAnchor anchor =
                new StructureEntityProjection.RegionAnchor(64, 10, -8);
        final Vec3 world = StructureEntityProjection.projectPosition(
                Transform3d.identity(), 1.0, anchor, new Vec3(66.5, 12.25, -5.5));

        assertEquals(2.5, world.x, EPS);
        assertEquals(2.25, world.y, EPS);
        assertEquals(2.5, world.z, EPS);
    }

    @Test
    void rotatedAndScaledPoseMapsPositionAndDirection() {
        final Transform3d pose = new Transform3d(
                new Vector3d(10.0, 64.0, -3.0),
                new Quaterniond().rotateY(Math.PI / 2.0).rotateX(0.35));
        final double scale = 2.5;
        final StructureEntityProjection.RegionAnchor anchor =
                new StructureEntityProjection.RegionAnchor(0, 0, 0);
        final Vec3 voidPos = new Vec3(1.5, 2.5, 3.5);

        final Vec3 expectedPosition = toVec(pose.transformPoint(new Vector3d(
                voidPos.x * scale, voidPos.y * scale, voidPos.z * scale)));
        final Vec3 actualPosition = StructureEntityProjection.projectPosition(
                pose, scale, anchor, voidPos);
        assertEquals(expectedPosition.x, actualPosition.x, 1.0e-8);
        assertEquals(expectedPosition.y, actualPosition.y, 1.0e-8);
        assertEquals(expectedPosition.z, actualPosition.z, 1.0e-8);

        final Vec3 voidMotion = new Vec3(0.2, -0.1, 0.4);
        final Vec3 expectedDirection = toVec(pose.transformDirection(new Vector3d(
                voidMotion.x, voidMotion.y, voidMotion.z)));
        final Vec3 actualDirection = StructureEntityProjection.projectDirection(pose, voidMotion);
        assertEquals(expectedDirection.x, actualDirection.x, 1.0e-8);
        assertEquals(expectedDirection.y, actualDirection.y, 1.0e-8);
        assertEquals(expectedDirection.z, actualDirection.z, 1.0e-8);
        assertEquals(voidMotion.length(), actualDirection.length(), 1.0e-8,
                "rotation must not scale velocity");
    }

    @Test
    void rotationIsProjectedThroughTheTransformedLookVector() {
        final Transform3d pose = new Transform3d(
                new Vector3d(4.0, 70.0, 8.0),
                new Quaterniond().rotateY(-0.9).rotateZ(0.2));

        final float yaw = 35.0f;
        final float pitch = -20.0f;
        final Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        final Vec3 worldLook = toVec(pose.transformDirection(
                new Vector3d(look.x, look.y, look.z))).normalize();
        final Vec2 expected = worldLook.rotation();

        final Vec2 actual = StructureEntityProjection.projectRotation(pose, yaw, pitch);
        assertEquals(expected.x, actual.x, 1.0e-5f, "pitch");
        assertEquals(expected.y, actual.y, 1.0e-5f, "yaw");

        final Vec3 reprojectedLook = Vec3.directionFromRotation(actual.x, actual.y);
        final String detail = "worldLook=" + worldLook + " actual=" + actual
                + " reprojected=" + reprojectedLook;
        assertEquals(worldLook.x, reprojectedLook.x, 1.0e-4, detail);
        assertEquals(worldLook.y, reprojectedLook.y, 1.0e-4, detail);
        assertEquals(worldLook.z, reprojectedLook.z, 1.0e-4, detail);
    }

    @Test
    void nullPoseFallsBackToIdentity() {
        final StructureEntityProjection.RegionAnchor anchor =
                new StructureEntityProjection.RegionAnchor(10, 20, 30);
        final Vec3 world = StructureEntityProjection.projectPosition(
                null, 1.5, anchor, new Vec3(11.0, 22.0, 33.0));
        assertEquals(1.5, world.x, EPS);
        assertEquals(3.0, world.y, EPS);
        assertEquals(4.5, world.z, EPS);

        final Vec3 direction = StructureEntityProjection.projectDirection(
                null, new Vec3(0.0, -0.5, 0.25));
        assertEquals(0.0, direction.x, EPS);
        assertEquals(-0.5, direction.y, EPS);
        assertEquals(0.25, direction.z, EPS);
    }

    @Test
    void regionViewContainsOnlyItsOwnVoidVolume() {
        final EmptyPhysicsLevel.RegionView view = new EmptyPhysicsLevel.RegionView(
                7L, 10, 0, 20, 0, 0, 0, 3, 4, 5);

        assertTrue(view.contains(10.0, 0.0, 20.0));
        assertTrue(view.contains(12.99, 3.99, 24.99));
        assertFalse(view.contains(9.99, 0.0, 20.0), "left of region");
        assertFalse(view.contains(13.0, 0.0, 20.0), "right of region");
        assertFalse(view.contains(10.0, 4.0, 20.0), "above region");
        assertFalse(view.contains(10.0, 0.0, 25.0), "beyond region");

        assertTrue(view.containsChunk(0, 1), "chunk 0,1 hosts x 10..12, z 20..24");
        assertFalse(view.containsChunk(1, 1), "chunk 1,1 starts at x 16");
        assertFalse(view.containsChunk(0, 2), "chunk 0,2 starts at z 32");
    }

    @Test
    void batchAccumulatesEveryOutcome() {
        StructureEntityProjection.Batch batch = StructureEntityProjection.Batch.EMPTY;
        for (StructureEntityProjection.Result result : StructureEntityProjection.Result.values()) {
            batch = batch.plus(result);
        }

        assertEquals(1, batch.projected());
        assertEquals(1, batch.duplicates());
        assertEquals(1, batch.skippedPlayers());
        assertEquals(1, batch.skippedPassengers());
        assertEquals(1, batch.skippedBlockAttached());
        assertEquals(1, batch.skippedPlotOnly());
        assertEquals(1, batch.skippedRemoved());
        assertEquals(1, batch.failed());
        assertEquals(StructureEntityProjection.Result.values().length, batch.total());
        assertTrue(batch.summary().contains("projected=1"));
    }

    private static Vec3 toVec(Vector3d value) {
        return new Vec3(value.x, value.y, value.z);
    }
}
