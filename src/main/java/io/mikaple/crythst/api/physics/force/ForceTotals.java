package io.mikaple.crythst.api.physics.force;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable force/torque totals for one structure at the end of a fixed
 * physics substep.
 *
 * <p>The totals are the values contributed through {@link QueuedForceGroup}
 * during the most recent fixed substep. They are recorded <em>before</em> the
 * accumulator is converted to an impulse, so values stay in newtons and
 * newton-metres rather than in impulse units. A structure with no block-entity
 * physics actors, or one whose actors queued no forces, reports
 * {@link #empty(long)}.</p>
 *
 * <p><strong>Units and frames:</strong> forces are newtons, torques are
 * {@code N·m}, and everything is expressed in world space. The per-point
 * torque contribution is {@code (point - centreOfMass) × force}, using the
 * substep's centre of mass.</p>
 *
 * <p><strong>Threading:</strong> immutable and safe to read after
 * {@code LevelPhysics#tick}. Obtain the live value through
 * {@link io.mikaple.crythst.api.physics.PhysicsPipeline#forceTotals(io.mikaple.crythst.physics.structure.PhysicsStructure)}.</p>
 */
public final class ForceTotals {
    private static final ForceTotals EMPTY = new ForceTotals(0L, 0.0, new Vector3d(),
            new Vector3d(), Map.of());

    /**
     * One force group's contribution within a substep.
     *
     * @param group             immutable group key
     * @param forceNewtons      world-space force in newtons
     * @param torqueNewtonMetres world-space torque in {@code N·m}
     */
    public record GroupForce(ForceGroup group,
                             Vector3d forceNewtons,
                             Vector3d torqueNewtonMetres) {
        public GroupForce {
            if (group == null) {
                throw new NullPointerException("group");
            }
            forceNewtons = new Vector3d(forceNewtons == null ? new Vector3d() : forceNewtons);
            torqueNewtonMetres = new Vector3d(
                    torqueNewtonMetres == null ? new Vector3d() : torqueNewtonMetres);
        }

        /** @return a defensive copy of the force vector in newtons */
        @Override
        public Vector3d forceNewtons() {
            return new Vector3d(forceNewtons);
        }

        /** @return a defensive copy of the torque vector in {@code N·m} */
        @Override
        public Vector3d torqueNewtonMetres() {
            return new Vector3d(torqueNewtonMetres);
        }
    }

    private final long bodyId;
    private final double timeStepSeconds;
    private final Vector3d force;
    private final Vector3d torque;
    private final Map<ForceGroup, GroupForce> groups;

    /**
     * Creates a totals snapshot.
     *
     * @param bodyId           body id the totals belong to
     * @param timeStepSeconds  substep length used to convert the totals to
     *                         impulses, or {@code 0} for an empty snapshot
     * @param force            total world-space force in newtons
     * @param torque           total world-space torque in {@code N·m}
     * @param groups           per-group contributions; order is preserved
     */
    public ForceTotals(long bodyId, double timeStepSeconds, Vector3dc force, Vector3dc torque,
                       Map<ForceGroup, GroupForce> groups) {
        if (!(timeStepSeconds >= 0.0) || !Double.isFinite(timeStepSeconds)) {
            throw new IllegalArgumentException(
                    "timeStepSeconds must be finite and non-negative: " + timeStepSeconds);
        }
        this.bodyId = bodyId;
        this.timeStepSeconds = timeStepSeconds;
        this.force = force == null ? new Vector3d() : new Vector3d(force);
        this.torque = torque == null ? new Vector3d() : new Vector3d(torque);
        this.groups = groups == null || groups.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    }

    /** Totals for a body with no actor contribution. */
    public static ForceTotals empty(long bodyId) {
        return bodyId == 0L ? EMPTY : new ForceTotals(bodyId, 0.0, new Vector3d(),
                new Vector3d(), Map.of());
    }

    /** Body id these totals belong to. */
    public long bodyId() {
        return bodyId;
    }

    /**
     * Fixed substep length in seconds used for the accumulation, or {@code 0}
     * for an empty snapshot.
     */
    public double timeStepSeconds() {
        return timeStepSeconds;
    }

    /** @return a defensive copy of the total world force in newtons */
    public Vector3d force() {
        return new Vector3d(force);
    }

    /** @return a defensive copy of the total world torque in {@code N·m} */
    public Vector3d torque() {
        return new Vector3d(torque);
    }

    /** Magnitude of the total force in newtons. */
    public double forceMagnitude() {
        return force.length();
    }

    /** Magnitude of the total torque in {@code N·m}. */
    public double torqueMagnitude() {
        return torque.length();
    }

    /** Whether both totals are exactly zero. */
    public boolean isZero() {
        return force.lengthSquared() == 0.0 && torque.lengthSquared() == 0.0;
    }

    /** Whether this snapshot came from a real substep rather than {@link #empty(long)}. */
    public boolean isPresent() {
        return timeStepSeconds > 0.0;
    }

    /** Immutable per-group contributions in stable group order. */
    public List<GroupForce> groups() {
        return List.copyOf(groups.values());
    }

    /** Per-group contribution for a key, or {@code null} when the group contributed nothing. */
    public @Nullable GroupForce group(ForceGroup group) {
        return group == null ? null : groups.get(group);
    }

    /** Number of distinct force groups that participated in this substep. */
    public int groupCount() {
        return groups.size();
    }

    /**
     * Total force in newtons for one group, or a zero vector when the group did
     * not contribute.
     */
    public Vector3d groupForce(ForceGroup group) {
        final GroupForce entry = group(group);
        return entry == null ? new Vector3d() : entry.forceNewtons();
    }

    /**
     * Total torque in {@code N·m} for one group, or a zero vector when the group
     * did not contribute.
     */
    public Vector3d groupTorque(ForceGroup group) {
        final GroupForce entry = group(group);
        return entry == null ? new Vector3d() : entry.torqueNewtonMetres();
    }

    @Override
    public String toString() {
        return "ForceTotals[body=" + bodyId
                + ", F=" + force
                + ", T=" + torque
                + ", groups=" + groups.size()
                + ", dt=" + timeStepSeconds + "]";
    }
}
