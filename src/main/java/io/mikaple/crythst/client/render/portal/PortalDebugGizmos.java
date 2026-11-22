package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalPlane;
import io.mikaple.crythst.integration.portal.PortalShape;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.math.Aabb;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Locale;

/**
 * Portal debug layer for the {@code crythst:portal} F3 + F6 entry.
 *
 * <p>Draws, per portal, the opening outline, the plane normal, the right/up
 * axes, the destination opening of the linked portal (same dimension only) and
 * the traversal box an entity must enter to be teleported. A billboard label
 * reports the name, size, link state and carrier body id.</p>
 *
 * <p>The layer reads the same interpolated snapshots the renderer uses
 * ({@link PortalClientCache#effective(long)}), so a portal carried by a physics
 * structure is drawn where it is actually rendered. Work is bounded: at most
 * {@value #MAX_PORTALS} portals within {@value #MAX_DISTANCE} blocks, and the
 * outline is capped at {@value #MAX_OUTLINE_POINTS} corners. The layer never
 * logs and does nothing when no portal is visible.</p>
 */
public final class PortalDebugGizmos {
    /**
     * Half-thickness of the traversal slab, matching the teleporter's plane
     * thickness so the drawn box is the volume that actually teleports.
     */
    public static final double TRAVERSAL_THICKNESS = 0.35;
    /** Portals beyond this distance are not drawn. */
    public static final double MAX_DISTANCE = 96.0;
    /** Portals drawn per frame. */
    public static final int MAX_PORTALS = 16;
    /** Outline corners drawn per opening. */
    public static final int MAX_OUTLINE_POINTS = 64;

    private static final double NORMAL_ARROW_LENGTH = 1.2;
    private static final double AXIS_LENGTH = 0.6;

    private static final int COLOR_ENTRY = 0xFF46E0FF;
    private static final int COLOR_DESTINATION = 0xFFFFB300;
    private static final int COLOR_NORMAL = 0xFFFF5CE1;
    private static final int COLOR_RIGHT = 0xFF7CFC6E;
    private static final int COLOR_UP = 0xFFFFE24A;
    private static final int COLOR_BOX = 0x8046E0FF;
    private static final int COLOR_LINK = 0x80777777;
    private static final int COLOR_LABEL = 0xFFDBF6FF;

    private PortalDebugGizmos() {
    }

    /**
     * Emits the portal layer for this frame. Safe to call when no level,
     * camera or portal exists.
     */
    public static void emit() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.gameRenderer == null) {
            return;
        }
        final Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        final List<PortalSnapshot> snapshots = PortalClientCache.effective(System.nanoTime());
        if (snapshots.isEmpty()) {
            return;
        }
        final GizmoStyle boxStyle = GizmoStyle.stroke(COLOR_BOX, 1.0f);
        int drawn = 0;
        for (PortalSnapshot portal : snapshots) {
            if (drawn >= MAX_PORTALS) {
                break;
            }
            if (!portal.dimension().equals(minecraft.level.dimension())) {
                continue;
            }
            final PortalPlane plane = portal.plane();
            if (plane == null) {
                continue;
            }
            final Vector3d origin = plane.origin();
            if (origin.distanceSquared(camera.x, camera.y, camera.z) > MAX_DISTANCE * MAX_DISTANCE) {
                continue;
            }
            drawn++;

            emitOutline(portal.shape(), COLOR_ENTRY, 2.0f);
            Gizmos.line(toVec3(origin),
                    toVec3(new Vector3d(plane.normal()).mul(NORMAL_ARROW_LENGTH).add(origin)),
                    COLOR_NORMAL, 2.0f);
            Gizmos.line(toVec3(origin),
                    toVec3(new Vector3d(plane.right()).mul(AXIS_LENGTH).add(origin)),
                    COLOR_RIGHT, 1.2f);
            Gizmos.line(toVec3(origin),
                    toVec3(new Vector3d(plane.up()).mul(AXIS_LENGTH).add(origin)),
                    COLOR_UP, 1.2f);
            Gizmos.cuboid(traversalBounds(portal.shape()), boxStyle);

            if (portal.destinationPlane() != null
                    && portal.destinationDimension() != null
                    && portal.destinationDimension().equals(minecraft.level.dimension())) {
                emitOutline(portal.destinationShape(), COLOR_DESTINATION, 1.5f);
                final Vector3d exit = portal.destinationPlane().origin();
                Gizmos.line(toVec3(origin), toVec3(exit), COLOR_LINK, 1.0f);
            }

            final Vector3d labelAnchor = new Vector3d(origin)
                    .fma(plane.height() * 0.5 + 0.45, plane.up());
            Gizmos.billboardText(label(portal), toVec3(labelAnchor),
                    TextGizmo.Style.forColorAndCentered(COLOR_LABEL).withScale(0.5f));
        }
    }

    /**
     * Returns the axis-aligned box that encloses the traversal slab of
     * {@code shape}: the opening's bounds grown along the plane normal by
     * {@link #TRAVERSAL_THICKNESS} on both sides.
     */
    public static AABB traversalBounds(PortalShape shape) {
        final Aabb bounds = shape.bounds();
        final Vector3d normal = shape.plane().normal();
        final double dx = Math.abs(normal.x) * TRAVERSAL_THICKNESS;
        final double dy = Math.abs(normal.y) * TRAVERSAL_THICKNESS;
        final double dz = Math.abs(normal.z) * TRAVERSAL_THICKNESS;
        return new AABB(
                bounds.min().x - dx, bounds.min().y - dy, bounds.min().z - dz,
                bounds.max().x + dx, bounds.max().y + dy, bounds.max().z + dz);
    }

    private static void emitOutline(PortalShape shape, int color, float width) {
        if (shape == null) {
            return;
        }
        final List<Vector3d> corners = shape.worldOutline();
        if (corners.size() < 2) {
            return;
        }
        final int limit = Math.min(corners.size(), MAX_OUTLINE_POINTS);
        for (int i = 0; i < limit; i++) {
            final Vector3d from = corners.get(i);
            final Vector3d to = corners.get((i + 1) % limit);
            Gizmos.line(toVec3(from), toVec3(to), color, width);
        }
    }

    /** Label for one portal: name, size, link and carrier. */
    static String label(PortalSnapshot portal) {
        final PortalPlane plane = portal.plane();
        final String size = plane == null
                ? "?"
                : String.format(Locale.ROOT, "%.1fx%.1f", plane.width(), plane.height());
        final StringBuilder text = new StringBuilder(portal.name())
                .append("  ").append(size);
        if (portal.linkedPortalId() == null) {
            text.append("  unlinked");
        } else {
            text.append("  -> ").append(portal.destinationDimension() == null
                    ? "?"
                    : portal.destinationDimension().identifier().toString());
        }
        if (portal.anchor() != null) {
            text.append("  carrier#").append(portal.anchor().carrierBodyId());
        }
        return text.toString();
    }

    private static Vec3 toVec3(Vector3dc vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
