package io.mikaple.crythst.physics.query;

import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A segment ray query in world space.
 */
public record RayQuery(Vector3d from, Vector3d to, BodyFilter filter) {
    public RayQuery {
        from = new Vector3d(Validate.notNull(from, "from"));
        to = new Vector3d(Validate.notNull(to, "to"));
        filter = filter == null ? BodyFilter.ALL : filter;
    }

    public static RayQuery between(Vector3dc from, Vector3dc to) {
        return new RayQuery(new Vector3d(from), new Vector3d(to), BodyFilter.ALL);
    }

    public static RayQuery fromDirection(Vector3dc origin, Vector3dc direction, double maxDistance) {
        Validate.positive(maxDistance, "maxDistance");
        final Vector3d end = new Vector3d(origin)
                .add(new Vector3d(direction).normalize(maxDistance));
        return new RayQuery(new Vector3d(origin), end, BodyFilter.ALL);
    }

    public RayQuery withFilter(BodyFilter newFilter) {
        return new RayQuery(from, to, newFilter);
    }

    public Vector3d direction() {
        return new Vector3d(to).sub(from);
    }
}
