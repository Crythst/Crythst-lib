package io.mikaple.crythst.integration.minecraft.entity.riding;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityStructureRidingMathTest {
    private static final double TOLERANCE = EntityStructureRidingMath.DEFAULT_SUPPORT_TOLERANCE;

    private static VoxelBox box(int x, int y, int z, int sx, int sy, int sz) {
        return new VoxelBox(x, y, z, sx, sy, sz, PhysicsMaterial.STONE, 1.0);
    }

    @Test
    void staticCarryDoesNotProduceATeleport() {
        final Vector3d anchor = new Vector3d(3.0, 4.0, 5.0);
        assertFalse(EntityStructureRidingMath.needsCarryUpdate(
                anchor, new Vector3d(3.0, 4.0, 5.0), 45.0f, 45.0f),
                "an unchanged pose must not move or teleport the entity");
        assertFalse(EntityStructureRidingMath.needsCarryUpdate(
                anchor, new Vector3d(3.0, 4.0, 5.0 + 1.0e-7), 45.0f, 45.0f),
                "numerical noise must not teleport the entity");
    }

    @Test
    void realCarryAndYawChangeAreApplied() {
        final Vector3d anchor = new Vector3d(3.0, 4.0, 5.0);
        assertTrue(EntityStructureRidingMath.needsCarryUpdate(
                anchor, new Vector3d(3.01, 4.0, 5.0), 0.0f, 0.0f));
        assertTrue(EntityStructureRidingMath.needsCarryUpdate(
                anchor, new Vector3d(3.0, 4.0, 5.0), 0.0f, 0.5f));
    }

    @Test
    void localWorldRoundTripKeepsRotatedPose() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(4.0, 5.0, 6.0),
                new Quaterniond().rotateY(0.7).rotateX(0.2));
        final Vector3d local = new Vector3d(1.2, -0.3, 2.4);

        final Vector3d world = EntityStructureRidingMath.toWorld(pose, local);
        final Vector3d roundTrip = EntityStructureRidingMath.toLocal(pose, world);

        assertEquals(local.x, roundTrip.x, 1.0e-9);
        assertEquals(local.y, roundTrip.y, 1.0e-9);
        assertEquals(local.z, roundTrip.z, 1.0e-9);
        assertTrue(pose.inverseTransformPoint(world).distance(local) < 1.0e-9);
    }

    @Test
    void supportFindsTopOfIdentityBox() {
        final var support = EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 2, 1, 2)),
                1.0,
                new Vector3d(1.0, 1.0, 1.0),
                0.3, 0.3,
                TOLERANCE,
                64);

        assertNotNull(support);
        assertEquals(1.0, support.topY(), 1.0e-9);
        assertEquals(0.0, support.verticalGap(), 1.0e-9);
        assertTrue(support.samples() >= 1);
    }

    @Test
    void supportAcceptsSmallGapAndRejectsTooHigh() {
        assertNotNull(EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, new Vector3d(0.5, 1.05, 0.5),
                0.0, 0.0, TOLERANCE, 64));

        assertNull(EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, new Vector3d(0.5, 1.2, 0.5),
                0.0, 0.0, TOLERANCE, 64));
    }

    @Test
    void supportAcceptsFeetSlightlyInsideTheTop() {
        assertNotNull(EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, new Vector3d(0.5, 0.97, 0.5),
                0.0, 0.0, TOLERANCE, 64));
    }

    @Test
    void supportUsesRotatedPose() {
        final Transform3d pose = Transform3d.of(
                new Vector3d(10.0, 0.0, 0.0),
                new Quaterniond().rotateY(Math.toRadians(90.0)));
        // Top-centre of the unit box in local metres.
        final Vector3d feet = pose.transformPoint(new Vector3d(0.5, 1.0, 0.5));

        assertNotNull(EntityStructureRidingMath.findSupport(
                pose,
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, feet,
                0.0, 0.0, TOLERANCE, 64));

        final Vector3d tooHigh = new Vector3d(feet).add(0.0, 0.2, 0.0);
        assertNull(EntityStructureRidingMath.findSupport(
                pose,
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, tooHigh,
                0.0, 0.0, TOLERANCE, 64));
    }

    @Test
    void supportRejectsTallSideAndDistantBox() {
        assertNull(EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 4, 1)),
                1.0, new Vector3d(0.5, 0.2, 0.5),
                0.0, 0.0, TOLERANCE, 64));

        assertNull(EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, new Vector3d(5.0, 1.0, 5.0),
                0.0, 0.0, TOLERANCE, 64));
    }

    @Test
    void supportRequiresCentreOrMostFootCorners() {
        // Centre outside the +X edge: only the two corners on the -X side are
        // over the surface, so the entity is treated as having left it.
        final var support = EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, new Vector3d(1.1, 1.0, 0.5),
                0.2, 0.2, TOLERANCE, 64);

        assertNull(support);
    }

    @Test
    void supportBudgetIsRespected() {
        assertNull(EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 1, 1, 1)),
                1.0, new Vector3d(0.5, 1.0, 0.5),
                0.0, 0.0, TOLERANCE, 0));
    }

    @Test
    void supportHonoursBlockScale() {
        final var support = EntityStructureRidingMath.findSupport(
                Transform3d.identity(),
                List.of(box(0, 0, 0, 2, 2, 2)),
                0.5, new Vector3d(0.5, 1.0, 0.5),
                0.0, 0.0, TOLERANCE, 64);

        assertNotNull(support);
        assertEquals(1.0, support.topY(), 1.0e-9);
        assertEquals(0.0, support.verticalGap(), 1.0e-9);
    }

    @Test
    void yawConventionsRoundTrip() {
        assertEquals(0.0, EntityStructureRidingMath.structureYawDegrees(new Quaterniond()), 1.0e-9);

        final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(90.0));
        assertEquals(-90.0, EntityStructureRidingMath.structureYawDegrees(rotation), 1.0e-6);

        final double localYaw = EntityStructureRidingMath.localYawDegrees(0.0f, rotation);
        assertEquals(90.0, localYaw, 1.0e-6);
        assertEquals(0.0, EntityStructureRidingMath.worldYawDegrees(localYaw, rotation), 1.0e-6);
    }

    @Test
    void yawWrappingStaysInRange() {
        assertEquals(-170.0, EntityStructureRidingMath.wrapDegrees(190.0), 1.0e-9);
        assertEquals(170.0, EntityStructureRidingMath.wrapDegrees(-190.0), 1.0e-9);
        assertEquals(180.0, EntityStructureRidingMath.wrapDegrees(180.0), 1.0e-9);
        assertEquals(-179.0, EntityStructureRidingMath.wrapDegrees(181.0), 1.0e-9);
    }

    @Test
    void poseDeltaCarriesTheLocalPoint() {
        final Vector3d local = new Vector3d(2.0, 0.5, -1.0);
        final Transform3d previous = Transform3d.identity();
        final Transform3d current = Transform3d.of(new Vector3d(1.0, 0.0, 0.0));

        final Vector3d oldAnchor = EntityStructureRidingMath.toWorld(previous, local);
        final Vector3d newAnchor = EntityStructureRidingMath.toWorld(current, local);
        final Vector3d delta = new Vector3d(newAnchor).sub(oldAnchor);

        // Without entity movement the world-space carry is exactly the anchor
        // delta, so the local point is unchanged.
        final Vector3d carriedAtRest = new Vector3d(oldAnchor).add(delta);
        assertTrue(EntityStructureRidingMath.toLocal(current, carriedAtRest).distance(local) < 1.0e-9);

        // Entity movement is added on top of the carry delta and then becomes
        // the new local point.
        final Vector3d moved = new Vector3d(oldAnchor).add(0.25, 0.0, 0.0);
        final Vector3d carried = moved.add(delta);
        final Vector3d newLocal = EntityStructureRidingMath.toLocal(current, carried);
        assertEquals(local.x + 0.25, newLocal.x, 1.0e-9);
        assertEquals(local.y, newLocal.y, 1.0e-9);
        assertEquals(local.z, newLocal.z, 1.0e-9);
    }
}
