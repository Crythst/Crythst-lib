package io.mikaple.crythst.physics.query;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.shape.PhysicsShape;
import io.mikaple.crythst.util.Validate;

/**
 * Shape-cast query in world space.
 */
public record SweepQuery(
        PhysicsShape shape,
        Transform3d from,
        Transform3d to,
        BodyFilter filter,
        double tolerance
) {
    public SweepQuery {
        shape = Validate.notNull(shape, "shape");
        from = Validate.notNull(from, "from");
        to = Validate.notNull(to, "to");
        filter = filter == null ? BodyFilter.ALL : filter;
        tolerance = tolerance <= 0.0 ? 0.01 : tolerance;
    }

    public static SweepQuery between(PhysicsShape shape, Transform3d from, Transform3d to) {
        return new SweepQuery(shape, from, to, BodyFilter.ALL, 0.01);
    }

    public SweepQuery withFilter(BodyFilter newFilter) {
        return new SweepQuery(shape, from, to, newFilter, tolerance);
    }
}
