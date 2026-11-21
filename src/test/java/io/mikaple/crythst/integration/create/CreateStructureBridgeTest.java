package io.mikaple.crythst.integration.create;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure transform math and "no entity / no Create" fallback behaviour. No
 * native physics backend or Create classes are needed.
 */
class CreateStructureBridgeTest {
    private static final double EPSILON = 1.0e-9;

    @AfterEach
    void clearRegistry() {
        CreateStructureBridge.clear();
    }

    @Test
    void localAndWorldTransformsRoundTrip() {
        final Transform3d structurePose = new Transform3d(
                new Vector3d(10.0, 5.0, -2.0),
                new Quaterniond().rotateY(Math.toRadians(65.0)));
        final Vector3d worldPosition = new Vector3d(12.5, 7.25, 3.75);

        final Transform3d local = CreateStructureBridge.localTransform(structurePose, worldPosition);
        final Transform3d world = CreateStructureBridge.worldTransform(structurePose, local);

        assertEquals(0.0, world.position().distance(worldPosition), 1.0e-9);
        assertEquals(1.0, Math.abs(world.rotation().dot(new Quaterniond())), 1.0e-9);
    }

    @Test
    void structureRotationIsAppliedToLocalOffset() {
        final Transform3d structurePose = new Transform3d(
                new Vector3d(),
                new Quaterniond().rotateY(Math.toRadians(90.0)));
        final Transform3d local = Transform3d.of(new Vector3d(1.0, 0.0, 0.0));

        final Vector3d world = CreateStructureBridge.worldTransform(structurePose, local).position();

        assertEquals(0.0, world.x, 1.0e-9);
        assertEquals(0.0, world.y, 1.0e-9);
        assertEquals(-1.0, world.z, 1.0e-9);
    }

    @Test
    void worldRotationIsCapturedAndRestored() {
        final Transform3d structurePose = new Transform3d(
                new Vector3d(-4.0, 2.0, 8.0),
                new Quaterniond().rotateY(Math.toRadians(-30.0)));
        final Quaterniond worldRotation = new Quaterniond().rotateX(Math.toRadians(15.0))
                .mul(new Quaterniond().rotateY(Math.toRadians(17.0)));

        final Transform3d local = CreateStructureBridge.localTransform(
                structurePose, new Vector3d(1.0, 2.0, 3.0), worldRotation);
        final Transform3d world = CreateStructureBridge.worldTransform(structurePose, local);

        assertEquals(1.0, Math.abs(world.rotation().dot(worldRotation)), 1.0e-9);
    }

    @Test
    void entityYawConvertsBothWays() {
        for (float yaw : new float[]{0.0f, 37.5f, 90.0f, 179.0f, -120.0f}) {
            final Quaterniond rotation = CreateStructureBridge.rotationOfYaw(yaw);
            assertEquals(yaw, CreateStructureBridge.yawOf(rotation), 1.0e-4,
                    "yaw round-trip for " + yaw);
        }
    }

    @Test
    void emptyRegistryBehaviourIsSafe() {
        assertTrue(CreateStructureBridge.attachmentOf(null).isEmpty());
        assertTrue(CreateStructureBridge.structureOf(null).isEmpty());
        assertTrue(CreateStructureBridge.localTransformOf(null).isEmpty());
        assertTrue(CreateStructureBridge.attachedEntityId(null).isEmpty());
        assertTrue(CreateStructureBridge.detach(null).isEmpty());
        assertFalse(CreateStructureBridge.tick(null));
        assertFalse(CreateStructureBridge.isAttached(null));
        assertEquals(0, CreateStructureBridge.tickAll(null));
        assertEquals(0, CreateStructureBridge.prune(null));
        assertEquals(0, CreateStructureBridge.detachLevel(null));
        assertEquals(0, CreateStructureBridge.detachStructure(null));
        assertEquals(0, CreateStructureBridge.attachedCount());
    }

    @Test
    void applyRejectsNullInputs() {
        assertFalse(CreateStructureBridge.apply(null, (StructureAttachmentTarget) null));
        assertFalse(CreateStructureBridge.apply(null, (StructureAttachmentTarget) null, true));
        assertFalse(CreateStructureBridge.applyWorldTransform(null, Transform3d.IDENTITY, false));
        assertFalse(CreateStructureBridge.applyWorldTransform(null, null, false));
    }

    @Test
    void closedOrMissingStructureCannotBeAttached() {
        final PhysicsStructure absent = null;
        assertTrue(CreateStructureBridge.attach(null, absent).isEmpty());
        assertTrue(CreateStructureBridge.attachAtLocalPosition(null, absent, new Vector3d()).isEmpty());
    }
}
