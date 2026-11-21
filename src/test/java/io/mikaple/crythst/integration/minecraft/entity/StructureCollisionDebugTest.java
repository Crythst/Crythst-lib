package io.mikaple.crythst.integration.minecraft.entity;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureCollisionDebugTest {
    @BeforeEach
    void clear() {
        StructureCollisionDebug.reset();
    }

    @Test
    void lastSampleDescribesAxisPenetrationAndVelocity() {
        final Vec3 before = new Vec3(0.2, -0.08, 0.0);
        final Vec3 after = new Vec3(0.2, 0.0, 0.0);
        StructureCollisionDebug.client("7", 0.08,
                new Vector3d(0.0, 1.0, 0.0), new Vector3d(0.0, 0.1, 0.0),
                before, after, true, "support");

        final StructureCollisionDebug.Sample sample = StructureCollisionDebug.last();
        assertNotNull(sample);
        assertEquals(StructureCollisionDebug.Path.CLIENT_PREDICTION, sample.path());
        assertEquals(0.08, sample.penetration(), 1.0e-9);
        assertEquals(1.0, sample.normal().y, 1.0e-9);
        assertTrue(sample.applied());
        assertEquals(1L, StructureCollisionDebug.correctionCount());
        assertEquals(0L, StructureCollisionDebug.deferredCount());

        final String description = StructureCollisionDebug.lastDescription();
        assertNotNull(description);
        assertTrue(description.contains("pen=0.0800"), description);
        assertTrue(description.contains("support"), description);
    }

    @Test
    void oppositeNormalsInQuickSuccessionCountAsAFlip() {
        final Vec3 velocity = new Vec3(0.0, 0.0, 0.0);
        StructureCollisionDebug.client("-", 0.02,
                new Vector3d(1.0, 0.0, 0.0), new Vector3d(0.04, 0.0, 0.0),
                velocity, velocity, true, "slide");
        StructureCollisionDebug.client("-", 0.02,
                new Vector3d(-1.0, 0.0, 0.0), new Vector3d(-0.04, 0.0, 0.0),
                velocity, velocity, true, "slide");
        assertEquals(1L, StructureCollisionDebug.flipCount());
    }

    @Test
    void deferredServerPlayerIsCountedSeparately() {
        final Vec3 velocity = new Vec3(0.1, 0.0, 0.0);
        StructureCollisionDebug.serverPlayer("-", 0.03,
                new Vector3d(-1.0, 0.0, 0.0), new Vector3d(-0.05, 0.0, 0.0),
                velocity, velocity, false, "client-prediction");
        assertEquals(0L, StructureCollisionDebug.correctionCount());
        assertEquals(1L, StructureCollisionDebug.deferredCount());
        assertEquals(StructureCollisionDebug.Path.SERVER_PLAYER_DEFERRED,
                StructureCollisionDebug.last().path());
    }
}
