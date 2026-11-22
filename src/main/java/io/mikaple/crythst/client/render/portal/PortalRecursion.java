package io.mikaple.crythst.client.render.portal;

import java.util.Set;
import java.util.UUID;

/**
 * Pure recursion-layer policy shared by extraction and rendering.
 *
 * <p>Layer {@code 1} is the world seen through a portal in the main camera.
 * Layer {@code 2} is a portal seen inside that destination view. The renderer
 * allocates one mask/snapshot attachment set per layer and stops before
 * {@code limit}. Ancestor portal ids are excluded so a link cannot recurse
 * through itself.</p>
 */
public final class PortalRecursion {
    public static final int MIN_LAYER = 1;

    private PortalRecursion() {
    }

    /** The next layer when a visible child portal is drawn, or 0 when denied. */
    public static int nextLayer(int parentLayer, int limit, UUID childId, Set<UUID> ancestors) {
        if (parentLayer < MIN_LAYER || limit < MIN_LAYER) {
            return 0;
        }
        final int next = parentLayer + 1;
        if (next > limit) {
            return 0;
        }
        if (childId != null && ancestors != null && ancestors.contains(childId)) {
            return 0;
        }
        return next;
    }

    public static boolean permits(int layer, int limit) {
        return layer >= MIN_LAYER && layer <= limit;
    }
}
