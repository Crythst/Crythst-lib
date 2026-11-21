package io.mikaple.crythst.physics.debug;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;

/**
 * Immutable per-body debug information.
 */
public record BodyDebugInfo(
        long id,
        BodyType type,
        Transform3d pose,
        OrientedBox obb,
        Aabb aabb,
        boolean active,
        boolean terrain
) {
}
