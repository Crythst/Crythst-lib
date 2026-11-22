package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Binds a portal plane to a moving physics structure.
 *
 * <p>A static portal stores an absolute world plane. A portal that rides on a
 * physics structure cannot: the carrier moves and rotates every tick, and the
 * portal has to follow it. The anchor therefore stores the plane in the
 * carrier's <em>local</em> space together with the carrier body id, and the
 * world plane is sampled on demand from the carrier pose.</p>
 *
 * <p>Sampling is split per side on purpose:</p>
 * <ul>
 *     <li>the server resolves the plane from the authoritative
 *     {@code PhysicsStructure#pose()} at teleport time, so a crossing is tested
 *     against the same pose the solver just produced,</li>
 *     <li>the client resolves the plane from
 *     {@code ClientStructureCache#interpolatedPose(long, long)}, so the rendered
 *     opening moves smoothly instead of stepping at the 20 Hz pose rate.</li>
 * </ul>
 *
 * @param carrierBodyId the physics body id of the carrying structure
 * @param localPlane    the portal plane expressed in the carrier's local space
 */
public record PortalAnchor(long carrierBodyId, PortalPlane localPlane) {
    public PortalAnchor {
        if (carrierBodyId <= 0L) {
            throw new IllegalArgumentException("carrierBodyId must be positive: " + carrierBodyId);
        }
        Objects.requireNonNull(localPlane, "localPlane");
    }

    /**
     * Captures {@code worldPlane} relative to a carrier at {@code carrierPose}.
     *
     * @param carrierBodyId the carrying body id
     * @param worldPlane    the portal plane in world space right now
     * @param carrierPose   the carrier pose that goes with {@code worldPlane}
     */
    public static PortalAnchor at(long carrierBodyId, PortalPlane worldPlane,
                                  Transform3d carrierPose) {
        Objects.requireNonNull(worldPlane, "worldPlane");
        Objects.requireNonNull(carrierPose, "carrierPose");
        final Vector3d origin = carrierPose.inverseTransformPoint(worldPlane.origin());
        final Vector3d normal = carrierPose.inverseTransformDirection(worldPlane.normal());
        final Vector3d right = carrierPose.inverseTransformDirection(worldPlane.right());
        return new PortalAnchor(carrierBodyId, new PortalPlane(
                origin, normal, right, worldPlane.width(), worldPlane.height()));
    }

    /**
     * The plane in world space for a carrier currently at {@code carrierPose}.
     */
    public PortalPlane worldPlane(Transform3d carrierPose) {
        Objects.requireNonNull(carrierPose, "carrierPose");
        return new PortalPlane(
                carrierPose.transformPoint(localPlane.origin()),
                carrierPose.transformDirection(localPlane.normal()),
                carrierPose.transformDirection(localPlane.right()),
                localPlane.width(), localPlane.height());
    }

    /** Re-expresses this anchor around a different local plane. */
    public PortalAnchor withLocalPlane(PortalPlane plane) {
        return new PortalAnchor(carrierBodyId, plane);
    }

    /** Re-expresses this anchor for a different carrying body. */
    public PortalAnchor withCarrier(long bodyId) {
        return new PortalAnchor(bodyId, localPlane);
    }

    /** True when the given body carries this anchor. */
    public boolean matches(long bodyId) {
        return carrierBodyId == bodyId;
    }
}
