package io.mikaple.crythst.api.sublevel.tracking;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureTrackingPointsTest {
    @Test
    void pointNbtRoundTripPreservesIdentityAndLoadingOptions() {
        final UUID id = UUID.randomUUID();
        final UUID structureId = UUID.randomUUID();
        final StructureTrackingPoint point = new StructureTrackingPoint(id, "docking port",
                42L, structureId, new Vector3d(1.0, 2.0, 3.0),
                new Vector3d(10.0, 20.0, 30.0), true, 2);

        final List<StructureTrackingPoint> decoded = StructureTrackingPoints.readPoints(
                StructureTrackingPoints.writePoints(List.of(point)));
        assertEquals(1, decoded.size());
        final StructureTrackingPoint restored = decoded.get(0);
        assertEquals(id, restored.id());
        assertEquals("docking port", restored.name());
        assertEquals(42L, restored.bodyId());
        assertEquals(structureId, restored.structureIdentity());
        assertEquals(new Vector3d(1.0, 2.0, 3.0), restored.localPosition());
        assertEquals(new Vector3d(10.0, 20.0, 30.0), restored.worldPositionAtSave());
        assertTrue(restored.keepLoaded());
        assertEquals(2, restored.keepLoadedRadiusChunks());
    }

    @Test
    void invalidOrEmptyTagsAreIgnored() {
        assertTrue(StructureTrackingPoints.readPoints(null).isEmpty());
        assertTrue(StructureTrackingPoints.readPoints(
                new net.minecraft.nbt.CompoundTag()).isEmpty());
        assertEquals(0, StructureTrackingPoints.readPoints(
                StructureTrackingPoints.writePoints(List.of())).size());
    }

    @Test
    void recordCopiesVectorsAndValidatesRadius() {
        final StructureTrackingPoint point = new StructureTrackingPoint(UUID.randomUUID(), "x",
                1L, null, new Vector3d(1.0, 0.0, 0.0), new Vector3d(2.0, 0.0, 0.0),
                false, 1);
        final Vector3d local = point.localPosition();
        local.zero();
        assertEquals(1.0, point.localPosition().x, 0.0);

        final StructureTrackingPoint moved = point.withKeepLoaded(true, 4)
                .withSavePosition(new Vector3d(5.0, 6.0, 7.0));
        assertTrue(moved.keepLoaded());
        assertEquals(4, moved.keepLoadedRadiusChunks());
        assertEquals(7.0, moved.worldPositionAtSave().z, 0.0);
        assertFalse(moved.localPosition().lengthSquared() == 0.0);

        assertThrows(IllegalArgumentException.class,
                () -> point.withKeepLoaded(true, -1));
        assertNotNull(point.withName("renamed").name());
    }
}
