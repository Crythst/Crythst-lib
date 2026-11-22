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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Persistence tests for the moving-carrier binding: a portal attached to a
 * physics structure must reload attached to the same body id, with the local
 * plane intact.
 */
class PortalAnchorPersistenceTest {
    private static final double EPS = 1.0e-9;
    /** No {@code Bootstrap} needed: the key is built straight from the registry id. */
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("minecraft", "overworld"));

    private static PortalPlane planeAt(double x) {
        return new PortalPlane(
                new Vector3d(x, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    private static PortalPlane localPlane() {
        return new PortalPlane(
                new Vector3d(0.5, 1.0, -0.25),
                new Vector3d(0.0, 1.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    @Test
    void anchorSurvivesSaveAndLoad() {
        final UUID id = UUID.randomUUID();
        final PortalAnchor anchor = new PortalAnchor(42L, localPlane());
        final CompoundTag tag = PortalSavedData.write(List.of(
                new PortalSavedData.StoredPortal(
                        id, "carried_gate", DIMENSION, planeAt(10.0), null,
                        List.of(), anchor)));

        final List<PortalSavedData.StoredPortal> loaded =
                new PortalSavedData(tag).readPortals();

        assertEquals(1, loaded.size());
        final PortalAnchor restored = loaded.get(0).anchor();
        assertNotNull(restored, "anchor must be restored");
        assertEquals(42L, restored.carrierBodyId());
        assertEquals(anchor.localPlane().origin().x, restored.localPlane().origin().x, EPS);
        assertEquals(anchor.localPlane().origin().y, restored.localPlane().origin().y, EPS);
        assertEquals(anchor.localPlane().origin().z, restored.localPlane().origin().z, EPS);
        assertEquals(anchor.localPlane().normal().y, restored.localPlane().normal().y, EPS);
        assertEquals(2.0, restored.localPlane().width(), EPS);
        assertEquals(3.0, restored.localPlane().height(), EPS);
    }

    @Test
    void staticPortalSavesWithoutAnchor() {
        final CompoundTag tag = PortalSavedData.write(List.of(
                new PortalSavedData.StoredPortal(
                        UUID.randomUUID(), "static_gate", DIMENSION,
                        planeAt(0.0), null, List.of(), null)));

        final List<PortalSavedData.StoredPortal> loaded =
                new PortalSavedData(tag).readPortals();

        assertEquals(1, loaded.size());
        assertNull(loaded.get(0).anchor());
        assertEquals(64.0, loaded.get(0).plane().origin().y, EPS);
    }

    @Test
    void legacyVersionIgnoresTheAnchorTag() {
        final CompoundTag tag = PortalSavedData.write(List.of(
                new PortalSavedData.StoredPortal(
                        UUID.randomUUID(), "carried_gate", DIMENSION,
                        planeAt(0.0), null, List.of(),
                        new PortalAnchor(9L, localPlane()))));
        // A version-3 file predates moving carriers: the tag is meaningless and
        // must not resurrect a binding to a body id the world no longer has.
        tag.putInt("version", 3);

        final List<PortalSavedData.StoredPortal> loaded =
                new PortalSavedData(tag).readPortals();

        assertEquals(1, loaded.size());
        assertNull(loaded.get(0).anchor());
    }
}
