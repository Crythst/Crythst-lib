package io.mikaple.crythst.integration.minecraft.player;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import org.joml.Vector3d;

import java.util.List;

/**
 * Swept (time-of-impact) test between the rotated player box and terrain
 * boxes.
 *
 * <p>{@link PlayerObbCollision} runs at the end of the player tick, i.e. it
 * corrects a pose that has already been integrated. A single such correction
 * cannot see a thin obstacle the tick's motion passed through completely: both
 * the old and the new box are clear, so nothing reports a penetration. That is
 * how a player under rotated gravity can end up on the far side of a floor
 * while still being "not colliding".</p>
 *
 * <p>This class answers "when did the motion first touch?" instead, using the
 * separating-axis formulation of a swept box: for every face normal of the
 * Minkowski sum of the two boxes (the three axes of each box plus their nine
 * pairwise cross products) the projections overlap during one time interval.
 * The body touches when all intervals overlap at the same instant, so the
 * contact time is the <em>largest</em> entry time and the contact normal is the
 * axis that produced it. The caller clamps the tick's motion to the impact and
 * projects velocity out of the contact plane.</p>
 *
 * <p>Stateless and player-agnostic on purpose: the caller supplies the box, the
 * motion vector of this tick and the terrain boxes near the swept volume.</p>
 */
public final class PlayerObbSweep {
    /** Below this length a cross product is treated as degenerate. */
    private static final double EPSILON = 1.0e-9;

    /** An impact leaves the body this far off the surface to avoid re-contact. */
    public static final double SKIN = 1.0e-4;

    private PlayerObbSweep() {
    }

    /**
     * First contact of a swept box.
     *
     * @param time     fraction of {@code motion} in {@code [0, 1]}
     * @param normal   unit vector pointing out of the obstacle, toward the box
     * @param obstacle the box that was hit
     */
    public record Impact(double time, Vector3d normal, Aabb obstacle) {
    }

    /**
     * First impact of {@code from} translated along {@code motion}.
     *
     * <p>Obstacles that already intersect {@code from} are skipped: those are
     * recovery cases for the penetration pass, not tunnelling, and resolving
     * them again here would drag the player backwards every tick.</p>
     *
     * @return the earliest impact within this tick, or {@code null} when the
     *         whole motion is free
     */
    public static Impact firstImpact(OrientedBox from, Vector3d motion, List<Aabb> obstacles) {
        if (obstacles.isEmpty() || motion.lengthSquared() < EPSILON * EPSILON) {
            return null;
        }

        final Vector3d[] boxAxes = from.axes();
        final Vector3d half = from.halfExtents();
        final Vector3d center = from.center();

        Impact best = null;
        double bestTime = Double.POSITIVE_INFINITY;

        for (Aabb obstacle : obstacles) {
            final OrientedBox other = OrientedBox.fromAabb(obstacle);
            final Vector3d[] otherAxes = other.axes();
            final Vector3d otherHalf = other.halfExtents();
            final Vector3d delta = new Vector3d(center).sub(other.center());

            final Contact contact = contact(
                    boxAxes, half, otherAxes, otherHalf, delta, motion);
            if (contact == null || contact.time() <= 0.0 || contact.time() > 1.0) {
                continue;
            }
            if (contact.time() >= bestTime) {
                continue;
            }
            best = new Impact(contact.time(), contact.normal(), obstacle);
            bestTime = contact.time();
        }
        return best;
    }

    /**
     * Contact of one swept pair, or {@code null} when the motion never closes
     * every axis at once (already overlapping, or a separating axis never
     * closes).
     */
    private static Contact contact(Vector3d[] a, Vector3d ah, Vector3d[] b, Vector3d bh,
                                   Vector3d delta, Vector3d motion) {
        double latestEntry = Double.NEGATIVE_INFINITY;
        Vector3d latestAxis = null;

        final int axisCount = 3 + 3 + 9;
        final Vector3d[] axes = new Vector3d[axisCount];
        int count = 0;
        for (int i = 0; i < 3; i++) {
            axes[count++] = a[i];
        }
        for (int j = 0; j < 3; j++) {
            axes[count++] = b[j];
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final Vector3d cross = new Vector3d(a[i]).cross(b[j]);
                final double length = cross.length();
                if (length < EPSILON) {
                    continue;
                }
                axes[count++] = cross.div(length);
            }
        }

        for (int index = 0; index < count; index++) {
            final Vector3d axis = axes[index];
            final double projection = delta.dot(axis);
            final double separated = Math.abs(projection) - radius(axis, a, ah) - radius(axis, b, bh);
            if (separated <= 0.0) {
                // Already overlapping along this axis, so it imposes no entry
                // time; the remaining axes decide when the pair touches.
                continue;
            }
            final double sign = projection >= 0.0 ? 1.0 : -1.0;
            final double closing = -motion.dot(axis) * sign;
            if (closing <= EPSILON) {
                // Moving apart (or not at all) along this axis, so the gap
                // never closes: the pair cannot touch during this tick.
                return null;
            }
            final double entry = separated / closing;
            if (entry > latestEntry) {
                latestEntry = entry;
                latestAxis = axis;
            }
        }

        if (latestAxis == null || latestEntry <= 0.0) {
            // Every axis overlaps now: recovery, not a swept contact.
            return null;
        }
        final double sign = delta.dot(latestAxis) >= 0.0 ? 1.0 : -1.0;
        return new Contact(latestEntry, new Vector3d(latestAxis).mul(sign).normalize());
    }

    private record Contact(double time, Vector3d normal) {
    }

    private static double radius(Vector3d axis, Vector3d[] axes, Vector3d half) {
        return Math.abs(axis.dot(axes[0])) * half.x
                + Math.abs(axis.dot(axes[1])) * half.y
                + Math.abs(axis.dot(axes[2])) * half.z;
    }

    /**
     * Feet position just before the impact, backed off along the contact normal
     * by {@link #SKIN} so the next tick does not start in contact.
     */
    public static Vector3d positionBeforeImpact(Vector3d fromPosition, Vector3d motion,
                                                Impact impact) {
        final double clamped = Math.max(0.0, Math.min(1.0, impact.time()));
        final Vector3d position = new Vector3d(fromPosition).fma(clamped, motion);
        return position.fma(SKIN, impact.normal());
    }
}
