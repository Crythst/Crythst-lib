package io.mikaple.crythst.client.render.portal;

/**
 * Pure policy shared by the GPU mask/depth passes and their unit tests.
 *
 * <p>Minecraft 26.3 uses reverse-Z: near is {@code 1.0} and far is {@code 0.0}.
 * A portal-plane fragment is visible when its depth is greater than or equal to
 * the scene depth at that pixel (the mask pipeline uses
 * {@code CompareOp.GREATER_THAN_OR_EQUAL}). Recursion combines the child and
 * parent masks multiplicatively; the same product decides whether the final
 * depth restore keeps the destination depth (inside) or restores the snapshot
 * depth (outside).</p>
 */
public final class PortalMaskPolicy {
    private PortalMaskPolicy() {
    }

    /** Reverse-Z depth test used by the mask pipeline. */
    public static boolean depthTestPasses(float portalNdcZ, float sceneNdcZ) {
        return portalNdcZ >= sceneNdcZ;
    }

    /** Layer 2 mask = own * parent; layer 1 passes {@code own == parent}. */
    public static float combinedMask(float ownMask, float parentMask) {
        return ownMask * parentMask;
    }

    /** True when the destination pixel is kept (mask product covers it). */
    public static boolean keepsDestination(float ownMask, float parentMask) {
        return combinedMask(ownMask, parentMask) >= 0.5f;
    }

    /**
     * True when the snapshot depth must be restored: outside the final mask
     * only, so destination depth survives inside the opening for deeper layers
     * and later depth consumers.
     */
    public static boolean restoresSnapshotDepth(float ownMask, float parentMask) {
        return !keepsDestination(ownMask, parentMask);
    }

    /**
     * True when the portal polygon's depth may be cleared to far before drawing
     * the destination world. If the layer has no parent constraint, only the
     * own mask matters; outside the final mask the snapshot depth is restored
     * afterwards.
     */
    public static boolean clearsDestinationDepth(float ownMask) {
        return ownMask >= 0.5f;
    }
}
