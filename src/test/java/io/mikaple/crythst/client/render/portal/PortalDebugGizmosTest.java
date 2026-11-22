package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalAnchor;
import io.mikaple.crythst.integration.portal.PortalPlane;
import io.mikaple.crythst.integration.portal.PortalShape;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry and label tests for the portal debug layer.
 *
 * <p>The traversal box is what the teleporter actually tests against, so the
 * box must grow along the plane normal only — growing the opening's bounds
 * uniformly would draw a volume that includes cells the crossing test rejects.
 */
class PortalDebugGizmosTest {
    private static final double EPS = 1.0e-9;
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("minecraft", "overworld"));

    /** Vertical portal facing +Z at the origin, 2 wide and 3 high. */
    private static PortalPlane facingZ() {
        return new PortalPlane(new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0), new Vector3d(1.0, 0.0, 0.0), 2.0, 3.0);
    }

    @Test
    void traversalBoundsGrowsAlongTheNormalOnly() {
        final AABB box = PortalDebugGizmos.traversalBounds(
                PortalShape.rectangle(facingZ()));
        assertEquals(-1.0, box.minX, EPS, "minX");
        assertEquals(1.0, box.maxX, EPS, "maxX");
        assertEquals(62.5, box.minY, EPS, "minY");
        assertEquals(65.5, box.maxY, EPS, "maxY");
        assertEquals(-PortalDebugGizmos.TRAVERSAL_THICKNESS, box.minZ, EPS, "minZ");
        assertEquals(PortalDebugGizmos.TRAVERSAL_THICKNESS, box.maxZ, EPS, "maxZ");
    }

    @Test
    void traversalBoundsFollowsARotatedPlane() {
        // Same opening rotated 90 degrees so its normal is +X.
        final PortalPlane plane = new PortalPlane(new Vector3d(4.0, 64.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0), new Vector3d(0.0, 0.0, -1.0), 2.0, 3.0);
        final AABB box = PortalDebugGizmos.traversalBounds(PortalShape.rectangle(plane));
        assertEquals(4.0 - PortalDebugGizmos.TRAVERSAL_THICKNESS, box.minX, EPS, "minX");
        assertEquals(4.0 + PortalDebugGizmos.TRAVERSAL_THICKNESS, box.maxX, EPS, "maxX");
        assertEquals(-1.0, box.minZ, EPS, "minZ");
        assertEquals(1.0, box.maxZ, EPS, "maxZ");
        assertEquals(62.5, box.minY, EPS, "minY");
        assertEquals(65.5, box.maxY, EPS, "maxY");
    }

    @Test
    void labelReportsSizeLinkStateAndCarrier() {
        final PortalPlane plane = facingZ();
        final PortalAnchor anchor = PortalAnchor.at(42L, plane, new io.mikaple.crythst.math.Transform3d(
                new Vector3d(), new org.joml.Quaterniond()));
        final PortalSnapshot carried = new PortalSnapshot(UUID.randomUUID(), "gate", DIMENSION,
                plane, PortalShape.rectangle(plane), null, null, null, null, anchor, null);
        final String carriedLabel = PortalDebugGizmos.label(carried);
        assertTrue(carriedLabel.startsWith("gate  2.0x3.0"), carriedLabel);
        assertTrue(carriedLabel.contains("unlinked"), carriedLabel);
        assertTrue(carriedLabel.contains("carrier#42"), carriedLabel);

        final PortalSnapshot linked = PortalSnapshot.rectangular(UUID.randomUUID(), "north", DIMENSION,
                plane, UUID.randomUUID(), plane, DIMENSION);
        final String linkedLabel = PortalDebugGizmos.label(linked);
        assertTrue(linkedLabel.contains("-> minecraft:overworld"), linkedLabel);
        assertTrue(!linkedLabel.contains("carrier#"), linkedLabel);
    }
}
