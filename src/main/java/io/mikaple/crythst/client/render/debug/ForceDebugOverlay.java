package io.mikaple.crythst.client.render.debug;

import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.ForceTotals;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.physics.debug.ForceDebugSnapshot;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Locale;

/**
 * Force/torque debug layer for the {@code crythst:physics_forces} F3 + F6
 * entry.
 *
 * <p>The layer draws a world-space arrow for each structure's total force from
 * its centre of mass, then shorter arrows for each force group. A billboard
 * label reports the body id, total force, total torque and collider mass. All
 * values come from the immutable {@link ForceDebugSnapshot} published by
 * {@code LevelPhysics}; this class never touches a live body or Bullet.</p>
 *
 * <p>Rendering is bounded: at most {@value #MAX_STRUCTURES} structures and
 * {@value #MAX_ARROWS} arrows per frame, with at most
 * {@value #MAX_GROUPS_PER_STRUCTURE} group arrows per structure. The layer is
 * disabled unless the player enables it in F3 + F6 (or the
 * {@code crythst:physics} master entry is on), and it never logs.</p>
 */
public final class ForceDebugOverlay {
    /** Structures drawn per frame. */
    public static final int MAX_STRUCTURES = 64;
    /** Total arrows (structure + group) drawn per frame. */
    public static final int MAX_ARROWS = 256;
    /** Group arrows drawn for one structure. */
    public static final int MAX_GROUPS_PER_STRUCTURE = 8;
    /** Group labels drawn for one structure. */
    public static final int MAX_GROUP_LABELS = 3;

    /** Force that maps to half of the maximum arrow length. */
    private static final double REFERENCE_FORCE_NEWTONS = 20_000.0;
    private static final double MAX_ARROW_METRES = 3.0;
    private static final double MAX_GROUP_ARROW_METRES = 2.0;

    private static final int COLOR_TOTAL_FORCE = 0xFFFFD54A;
    private static final int COLOR_LABEL = 0xFFFFF3C4;
    private static final int COLOR_ZERO_LABEL = 0xFFB8B8B8;

    private ForceDebugOverlay() {
    }

    /**
     * Emits the force layer for this frame. Safe to call when no physics world
     * or snapshot exists.
     */
    public static void emit() {
        final List<ForceDebugSnapshot> snapshots = ClientPhysicsView.forceSnapshots();
        int structures = 0;
        int arrows = 0;
        for (ForceDebugSnapshot snapshot : snapshots) {
            if (structures >= MAX_STRUCTURES) {
                break;
            }
            structures++;
            final ForceTotals totals = snapshot.totals();
            final Vector3d centerOfMass = snapshot.centerOfMass();

            if (totals.forceMagnitude() > 0.0 && arrows < MAX_ARROWS) {
                final double length = arrowLengthMetres(totals.forceMagnitude(),
                        REFERENCE_FORCE_NEWTONS, MAX_ARROW_METRES);
                final Vector3d tip = new Vector3d(centerOfMass)
                        .add(arrowVector(totals.force(), length));
                Gizmos.arrow(toVec3(centerOfMass), toVec3(tip), COLOR_TOTAL_FORCE, 2.0f);
                arrows++;
            }

            int groups = 0;
            int labels = 0;
            for (ForceTotals.GroupForce groupForce : totals.groups()) {
                if (groups >= MAX_GROUPS_PER_STRUCTURE || arrows >= MAX_ARROWS) {
                    break;
                }
                final double magnitude = groupForce.forceNewtons().length();
                if (magnitude <= 0.0) {
                    continue;
                }
                // Offset starts slightly so coincident group forces remain
                // distinguishable without changing their direction.
                final Vector3d start = new Vector3d(centerOfMass)
                        .add(0.0, groups * 0.04, 0.0);
                final double length = arrowLengthMetres(magnitude,
                        REFERENCE_FORCE_NEWTONS, MAX_GROUP_ARROW_METRES);
                final Vector3d tip = new Vector3d(start)
                        .add(arrowVector(groupForce.forceNewtons(), length));
                Gizmos.arrow(toVec3(start), toVec3(tip), colorOf(groupForce.group()), 1.2f);
                arrows++;
                if (labels < MAX_GROUP_LABELS) {
                    Gizmos.billboardText(groupLabel(groupForce),
                            toVec3(new Vector3d(tip).add(0.0, 0.15, 0.0)),
                            TextGizmo.Style.forColorAndCentered(colorOf(groupForce.group()))
                                    .withScale(0.42f));
                    labels++;
                }
                groups++;
            }

            final Vector3d labelPosition = new Vector3d(centerOfMass)
                    .add(0.0, 0.65 + groups * 0.04, 0.0);
            Gizmos.billboardText(forceLabel(snapshot), toVec3(labelPosition),
                    TextGizmo.Style.forColorAndCentered(
                                    totals.isZero() ? COLOR_ZERO_LABEL : COLOR_LABEL)
                            .withScale(0.5f));
        }
    }

    /**
     * Maps a force magnitude to a bounded arrow length.
     *
     * <p>The mapping is monotonic and asymptotic: a force equal to
     * {@code referenceNewtons} produces half of {@code maxLengthMetres}, and
     * the result never reaches {@code maxLengthMetres}. Zero, negative,
     * non-finite and non-positive reference inputs return {@code 0}.</p>
     *
     * @param magnitudeNewtons  force magnitude; negative values are treated as
     *                          zero
     * @param referenceNewtons  force mapped to half the arrow length
     * @param maxLengthMetres   asymptote, also the hard upper bound
     * @return arrow length in metres
     */
    public static double arrowLengthMetres(double magnitudeNewtons, double referenceNewtons,
                                           double maxLengthMetres) {
        if (!(magnitudeNewtons > 0.0) || !Double.isFinite(magnitudeNewtons)
                || !(referenceNewtons > 0.0) || !Double.isFinite(referenceNewtons)
                || !(maxLengthMetres > 0.0) || !Double.isFinite(maxLengthMetres)) {
            return 0.0;
        }
        final double ratio = magnitudeNewtons / referenceNewtons;
        return maxLengthMetres * (ratio / (1.0 + ratio));
    }

    /**
     * Returns a vector with the force's direction and the requested length.
     * Zero, non-finite or zero-length inputs return a zero vector.
     */
    public static Vector3d arrowVector(Vector3dc force, double lengthMetres) {
        if (force == null || !(lengthMetres > 0.0) || !Double.isFinite(lengthMetres)) {
            return new Vector3d();
        }
        final double length = force.length();
        if (!(length > 0.0) || !Double.isFinite(length)) {
            return new Vector3d();
        }
        return new Vector3d(force).mul(lengthMetres / length);
    }

    /**
     * Total label for one structure.
     */
    static String forceLabel(ForceDebugSnapshot snapshot) {
        final ForceTotals totals = snapshot.totals();
        return "crythst #" + snapshot.bodyId()
                + "  F " + formatForce(totals.forceMagnitude())
                + "  |T| " + formatTorque(totals.torqueMagnitude())
                + "  m " + formatMass(snapshot.massKg());
    }

    /**
     * Label for one force group.
     */
    static String groupLabel(ForceTotals.GroupForce groupForce) {
        final String name = groupForce.group().label().getString();
        return name
                + ": F " + formatForce(groupForce.forceNewtons().length())
                + "  |T| " + formatTorque(groupForce.torqueNewtonMetres().length());
    }

    static String formatForce(double newtons) {
        final double value = Double.isFinite(newtons) ? newtons : 0.0;
        final double magnitude = Math.abs(value);
        if (magnitude >= 1_000_000.0) {
            return String.format(Locale.ROOT, "%.2f MN", value / 1_000_000.0);
        }
        if (magnitude >= 1_000.0) {
            return String.format(Locale.ROOT, "%.2f kN", value / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.1f N", value);
    }

    static String formatTorque(double newtonMetres) {
        final double value = Double.isFinite(newtonMetres) ? newtonMetres : 0.0;
        final double magnitude = Math.abs(value);
        if (magnitude >= 1_000_000.0) {
            return String.format(Locale.ROOT, "%.2f MN·m", value / 1_000_000.0);
        }
        if (magnitude >= 1_000.0) {
            return String.format(Locale.ROOT, "%.2f kN·m", value / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.1f N·m", value);
    }

    static String formatMass(double kilograms) {
        final double value = Double.isFinite(kilograms) ? Math.max(0.0, kilograms) : 0.0;
        if (value >= 1_000_000.0) {
            return String.format(Locale.ROOT, "%.2f kt", value / 1_000_000.0);
        }
        if (value >= 1_000.0) {
            return String.format(Locale.ROOT, "%.2f t", value / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.1f kg", value);
    }

    private static int colorOf(ForceGroup group) {
        final int rgb = group == null ? 0xFFFFFF : group.color() & 0xFFFFFF;
        return 0xFF000000 | rgb;
    }

    private static Vec3 toVec3(Vector3dc vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
