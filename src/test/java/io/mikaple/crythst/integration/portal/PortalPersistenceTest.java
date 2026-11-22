package io.mikaple.crythst.integration.portal;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalPersistenceTest {
    private static final double EPS = 1.0e-9;

    @Test
    void roundTripsStablePortalData() {
        final UUID id = UUID.randomUUID();
        final UUID linked = UUID.randomUUID();
        final PortalPlane plane = new PortalPlane(
                new Vector3d(12.5, 64.0, -3.25),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.5, 3.5);
        final ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse("minecraft:overworld"));

        final CompoundTag tag = PortalSavedData.write(List.of(
                new PortalSavedData.StoredPortal(id, "north_gate", dimension, plane, linked)));
        final PortalSavedData data = new PortalSavedData(tag);
        final List<PortalSavedData.StoredPortal> loaded = data.readPortals();

        assertEquals(1, loaded.size());
        final PortalSavedData.StoredPortal portal = loaded.getFirst();
        assertEquals(id, portal.id());
        assertEquals("north_gate", portal.name());
        assertEquals(dimension, portal.dimension());
        assertEquals(linked, portal.linkedId());
        assertEquals(plane.width(), portal.plane().width(), EPS);
        assertEquals(plane.height(), portal.plane().height(), EPS);
        assertVectorEquals(plane.origin(), portal.plane().origin());
        assertVectorEquals(plane.normal(), portal.plane().normal());
        assertVectorEquals(plane.right(), portal.plane().right());
        assertVectorEquals(plane.up(), portal.plane().up());
    }

    @Test
    void skipsEntriesWithoutIdsOrValidPlanes() {
        final CompoundTag tag = new CompoundTag();
        final CompoundTag missingId = new CompoundTag();
        missingId.putString("name", "broken");
        missingId.putString("dimension", "minecraft:overworld");
        final CompoundTag invalidPlane = new CompoundTag();
        invalidPlane.putString("id", UUID.randomUUID().toString());
        invalidPlane.putString("name", "broken_plane");
        invalidPlane.putString("dimension", "minecraft:overworld");
        invalidPlane.putDouble("ox", 0.0);
        invalidPlane.putDouble("oy", 0.0);
        invalidPlane.putDouble("oz", 0.0);
        invalidPlane.putDouble("width", 2.0);
        invalidPlane.putDouble("height", 3.0);

        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(missingId);
        list.add(invalidPlane);
        tag.put("portals", list);

        assertTrue(new PortalSavedData(tag).readPortals().isEmpty());
    }

    @Test
    void roundTripsPolygonOutlineAndLoadsVersionOneAsRectangle() {
        final PortalPlane plane = planeAt(2.0);
        final PortalShape diamond = PortalShape.polygon(plane, List.of(
                new org.joml.Vector2d(0.0, 1.5),
                new org.joml.Vector2d(1.0, 0.0),
                new org.joml.Vector2d(0.0, -1.5),
                new org.joml.Vector2d(-1.0, 0.0)));
        final UUID id = UUID.randomUUID();
        final CompoundTag tag = PortalSavedData.write(List.of(
                new PortalSavedData.StoredPortal(id, "diamond",
                        ResourceKey.create(Registries.DIMENSION,
                                Identifier.parse("minecraft:overworld")),
                        plane, null, diamond.outline())));
        final PortalSavedData.StoredPortal loaded = new PortalSavedData(tag).readPortals().getFirst();
        assertEquals(4, loaded.outline().size());
        assertFalse(loaded.shape().rectangular());
        assertTrue(loaded.shape().containsLocal(0.0, 0.0));

        // A version-1 entry has no outline tag; it must remain a rectangle.
        final CompoundTag legacy = new CompoundTag();
        legacy.putInt("version", 1);
        final CompoundTag entry = new CompoundTag();
        entry.putString("id", id.toString());
        entry.putString("name", "legacy");
        entry.putString("dimension", "minecraft:overworld");
        entry.putDouble("ox", 0.0);
        entry.putDouble("oy", 64.0);
        entry.putDouble("oz", 0.0);
        entry.putDouble("nx", 0.0);
        entry.putDouble("ny", 0.0);
        entry.putDouble("nz", 1.0);
        entry.putDouble("rx", 1.0);
        entry.putDouble("ry", 0.0);
        entry.putDouble("rz", 0.0);
        entry.putDouble("width", 2.0);
        entry.putDouble("height", 3.0);
        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(entry);
        legacy.put("portals", list);
        final PortalSavedData.StoredPortal old = new PortalSavedData(legacy).readPortals().getFirst();
        assertTrue(old.shape().rectangular());
    }

    @Test
    void transformMapsNormalsAndRoundTrips() {
        final PortalPlane entry = new PortalPlane(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0), 2.0, 3.0);
        final PortalPlane exit = new PortalPlane(
                new Vector3d(10.0, 4.0, -2.0),
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, -1.0), 2.0, 3.0);

        final PortalTransform transform = PortalTransform.between(entry, exit);
        final Vector3d normal = transform.transformDirection(entry.normal());
        final Vector3d exitNormal = exit.normal();
        assertNotNull(normal);
        assertVectorEquals(exitNormal.negate(), normal);
        assertVectorEquals(exit.up(), transform.transformDirection(entry.up()));

        final Vector3d point = new Vector3d(0.75, 1.25, 0.5);
        assertVectorEquals(point, transform.inverse().transformPoint(transform.transformPoint(point)));
    }

    @Test
    void loadDropsDanglingLinksAndRepairsOneSidedLinks() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final UUID c = UUID.randomUUID();
        final UUID missing = UUID.randomUUID();
        final ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse("minecraft:overworld"));

        final List<PortalSavedData.StoredPortal> stored = List.of(
                new PortalSavedData.StoredPortal(a, "a", dimension, planeAt(0.0), missing),
                new PortalSavedData.StoredPortal(b, "b", dimension, planeAt(4.0), c),
                new PortalSavedData.StoredPortal(c, "c", dimension, planeAt(8.0), null));

        PortalManager.loadFromSaved(stored);
        try {
            final Portal loadedA = PortalManager.findById(a);
            final Portal loadedB = PortalManager.findById(b);
            final Portal loadedC = PortalManager.findById(c);
            assertNotNull(loadedA);
            assertNotNull(loadedB);
            assertNotNull(loadedC);
            assertNull(loadedA.linkedPortalId());
            assertEquals(c, loadedB.linkedPortalId());
            assertEquals(b, loadedC.linkedPortalId());
        } finally {
            PortalManager.clearAll();
        }
    }

    @Test
    void createTreatsWhitespaceAsTheSameName() {
        final ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
        PortalManager.clearAll();
        try {
            final Portal first = PortalManager.create(dimension, "gate", planeAt(0.0));
            assertThrows(IllegalArgumentException.class,
                    () -> PortalManager.create(dimension, " gate ", planeAt(4.0)));
            assertEquals(1, PortalManager.size());
            final Portal existing = PortalManager.findByName("gate");
            assertNotNull(existing);
            assertEquals(first.id(), existing.id());
        } finally {
            PortalManager.clearAll();
        }
    }

    @Test
    void moveKeepsIdLinkAndUpdatesPlane() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
        PortalManager.loadFromSaved(List.of(
                new PortalSavedData.StoredPortal(a, "a", dimension, planeAt(0.0), b),
                new PortalSavedData.StoredPortal(b, "b", dimension, planeAt(4.0), a)));
        try {
            final PortalPlane movedPlane = planeAt(12.0);
            final Portal moved = PortalManager.move("a", movedPlane);
            assertNotNull(moved);
            assertEquals(a, moved.id());
            assertEquals(b, moved.linkedPortalId());
            assertEquals(12.0, moved.plane().origin().x, EPS);
            final Portal reloaded = PortalManager.findById(a);
            assertNotNull(reloaded);
            assertEquals(12.0, reloaded.plane().origin().x, EPS);
        } finally {
            PortalManager.clearAll();
        }
    }

    private static PortalPlane planeAt(double x) {
        return new PortalPlane(
                new Vector3d(x, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }
}
