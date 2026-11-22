package io.mikaple.crythst.api.physics.rope;

import io.mikaple.crythst.api.CrythstEvents;
import io.mikaple.crythst.api.event.PhysicsTickEvent;
import io.mikaple.crythst.api.physics.PhysicsPipeline;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Minimal native rope between two anchors.
 *
 * <p>A rope is a substep-rate tension element driven by Crythst's public
 * pre-physics-tick event, not a Bullet joint. The current endpoint distance is
 * compared with {@link #length()}. While the rope is longer than its rest
 * length it applies equal and opposite impulses at the two anchor points,
 * including the torque implied by applying at those points. The opposite
 * endpoint can be a second structure or a fixed world anchor.</p>
 *
 * <p><strong>Limitations.</strong> This is a stiff impulse rope, not a
 * constraint-solver rope: it can stretch under load, its behaviour depends on
 * substep length, and it has no collision or self-collision. Use a
 * structure-to-structure point constraint when a perfectly rigid link is
 * required.</p>
 *
 * <p><strong>Units and frames.</strong> Anchors are structure-local metres for
 * a structure endpoint and world-space metres for a world endpoint. Length is
 * metres, stiffness {@code N/m}, damping {@code N·s/m}, maximum tension
 * newtons. <strong>Threading:</strong> construct, mutate and close on the
 * server thread. The rope registers one global listener and removes it on
 * {@link #close()} or when an endpoint structure disappears.</p>
 */
public final class StructureRope implements AutoCloseable {
    /** Default stiffness in newtons per metre. */
    public static final double DEFAULT_STIFFNESS = 20_000.0;
    /** Default damping in newton-seconds per metre. */
    public static final double DEFAULT_DAMPING = 1_000.0;
    /** Default maximum tension in newtons. */
    public static final double DEFAULT_MAX_TENSION = 1_000_000.0;

    private final PhysicsPipeline pipeline;
    private final PhysicsStructure structureA;
    private final @Nullable PhysicsStructure structureB;
    private final Vector3d anchorA;
    private final Vector3d anchorB;
    private final boolean worldAnchored;

    private double length;
    private double stiffness = DEFAULT_STIFFNESS;
    private double damping = DEFAULT_DAMPING;
    private double maxTension = DEFAULT_MAX_TENSION;
    private final Vector3d lastTension = new Vector3d();
    private final AutoCloseable listener;
    private boolean closed;

    private final Vector3d worldA = new Vector3d();
    private final Vector3d worldB = new Vector3d();
    private final Vector3d delta = new Vector3d();
    private final Vector3d axis = new Vector3d();
    private final Vector3d velocityA = new Vector3d();
    private final Vector3d velocityB = new Vector3d();
    private final Vector3d impulse = new Vector3d();

    private StructureRope(PhysicsPipeline pipeline, PhysicsStructure structureA,
                          Vector3dc anchorA, @Nullable PhysicsStructure structureB,
                          Vector3dc anchorB, boolean worldAnchored, double length) {
        if (pipeline == null) {
            throw new NullPointerException("pipeline");
        }
        if (structureA == null) {
            throw new NullPointerException("structureA");
        }
        if (anchorA == null) {
            throw new NullPointerException("anchorA");
        }
        if (anchorB == null) {
            throw new NullPointerException("anchorB");
        }
        if (structureB != null && structureB == structureA) {
            throw new IllegalArgumentException("A rope needs two distinct structures");
        }
        if (!(length >= 0.0) || !Double.isFinite(length)) {
            throw new IllegalArgumentException("length must be finite and non-negative: " + length);
        }
        this.pipeline = pipeline;
        this.structureA = structureA;
        this.anchorA = new Vector3d(anchorA);
        this.structureB = structureB;
        this.anchorB = new Vector3d(anchorB);
        this.worldAnchored = worldAnchored;
        this.length = length;
        this.listener = CrythstEvents.registerPrePhysicsTick(this::tick);
    }

    /**
     * Creates a rope between two structure-local anchors. The rest length starts
     * at the current world distance between the anchors.
     */
    public static StructureRope between(PhysicsPipeline pipeline,
                                        PhysicsStructure structureA, Vector3dc anchorA,
                                        PhysicsStructure structureB, Vector3dc anchorB) {
        if (structureB == null) {
            throw new NullPointerException("structureB");
        }
        final double length = distance(pipeline, structureA, anchorA, structureB, anchorB);
        return new StructureRope(pipeline, structureA, anchorA, structureB, anchorB, false, length);
    }

    /**
     * Creates a rope between one structure-local anchor and a fixed world
     * anchor. The rest length starts at the current world distance.
     */
    public static StructureRope toWorld(PhysicsPipeline pipeline,
                                        PhysicsStructure structureA, Vector3dc localAnchor,
                                        Vector3dc worldAnchor) {
        final double length = structureA.pose().transformPoint(localAnchor)
                .distance(worldAnchor);
        return new StructureRope(pipeline, structureA, localAnchor, null, worldAnchor,
                true, length);
    }

    private static double distance(PhysicsPipeline pipeline, PhysicsStructure a,
                                   Vector3dc anchorA, PhysicsStructure b,
                                   Vector3dc anchorB) {
        if (pipeline == null) {
            throw new NullPointerException("pipeline");
        }
        if (a == null || b == null) {
            throw new NullPointerException("structure");
        }
        return a.pose().transformPoint(anchorA).distance(b.pose().transformPoint(anchorB));
    }

    /** Underlying pipeline whose substeps drive this rope. */
    public PhysicsPipeline pipeline() {
        return pipeline;
    }

    /** Structure A (always present). */
    public PhysicsStructure structureA() {
        return structureA;
    }

    /** Structure B, or {@code null} for a world-anchored rope. */
    public @Nullable PhysicsStructure structureB() {
        return structureB;
    }

    /** Whether the second endpoint is fixed in world space. */
    public boolean isWorldAnchored() {
        return worldAnchored;
    }

    /** Current rest length in metres. */
    public double length() {
        return length;
    }

    /** Sets the rest length in metres. */
    public void setLength(double metres) {
        if (!(metres >= 0.0) || !Double.isFinite(metres)) {
            throw new IllegalArgumentException("length must be finite and non-negative: " + metres);
        }
        this.length = metres;
    }

    /** Current stiffness in {@code N/m}. */
    public double stiffness() {
        return stiffness;
    }

    /** Sets the stiffness in {@code N/m}; must be finite and non-negative. */
    public void setStiffness(double newtonsPerMetre) {
        this.stiffness = requireNonNegative(newtonsPerMetre, "stiffness");
    }

    /** Current damping in {@code N·s/m}. */
    public double damping() {
        return damping;
    }

    /** Sets the damping in {@code N·s/m}; must be finite and non-negative. */
    public void setDamping(double newtonSecondsPerMetre) {
        this.damping = requireNonNegative(newtonSecondsPerMetre, "damping");
    }

    /** Current maximum tension in newtons. */
    public double maxTension() {
        return maxTension;
    }

    /** Sets the maximum tension in newtons; must be finite and non-negative. */
    public void setMaxTension(double newtons) {
        this.maxTension = requireNonNegative(newtons, "maxTension");
    }

    /** Tension applied during the most recent substep, in newtons. */
    public Vector3d tension() {
        return new Vector3d(lastTension);
    }

    /** Magnitude of {@link #tension()} in newtons. */
    public double tensionMagnitude() {
        return lastTension.length();
    }

    /** Whether the endpoints are currently farther apart than {@link #length()}. */
    public boolean isTaut() {
        updateEndpoints(new Vector3d(), new Vector3d());
        return worldA.distance(worldB) > length;
    }

    /** World-space position of the first anchor. */
    public Vector3d endpointA() {
        final Vector3d dest = new Vector3d();
        final Vector3d ignored = new Vector3d();
        updateEndpoints(dest, ignored);
        return dest;
    }

    /** World-space position of the second anchor. */
    public Vector3d endpointB() {
        final Vector3d ignored = new Vector3d();
        final Vector3d dest = new Vector3d();
        updateEndpoints(ignored, dest);
        return dest;
    }

    /** Whether both endpoints still exist. */
    public boolean isValid() {
        if (closed || !pipeline.isOpen() || structureA.closed()) {
            return false;
        }
        return worldAnchored || (structureB != null && !structureB.closed());
    }

    /**
     * Releases the listener. Idempotent. The rope is no longer safe to use after
     * this call except for read-only metadata such as {@link #length()}.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            listener.close();
        } catch (Exception ignored) {
            // The event bus close handle does not throw in practice.
        }
    }

    private void tick(PhysicsTickEvent event) {
        if (closed) {
            return;
        }
        if (event.level() != pipeline.level() || !isValid()) {
            close();
            return;
        }
        updateEndpoints(worldA, worldB);
        delta.set(worldB).sub(worldA);
        final double distance = delta.length();
        if (distance <= length || distance <= 1.0e-9) {
            lastTension.zero();
            return;
        }
        axis.set(delta).mul(1.0 / distance);

        velocityA.zero();
        velocityB.zero();
        velocityA.set(structureA.velocityAt(worldA));
        if (!worldAnchored && structureB != null) {
            velocityB.set(structureB.velocityAt(worldB));
        }
        // Positive when the endpoints are separating.
        final double separationSpeed = velocityB.sub(velocityA).dot(axis);
        final double tension = computeTension(distance, length, stiffness, damping,
                separationSpeed, maxTension);
        lastTension.set(axis).mul(tension);
        if (tension <= 0.0) {
            return;
        }

        impulse.set(axis).mul(tension * event.timeStepSeconds());
        structureA.applyImpulseAt(impulse, worldA);
        if (!worldAnchored && structureB != null) {
            structureB.applyImpulseAt(new Vector3d(impulse).negate(), worldB);
        }
    }

    /**
     * Pure tension rule used by {@link #tick(PhysicsTickEvent)}.
     *
     * <p>A rope produces force only while stretched. The result is clamped to
     * {@code [0, maxTension]} and non-finite inputs are folded to zero.</p>
     *
     * @param distance       current anchor distance in metres
     * @param restLength     rest length in metres
     * @param stiffness      spring stiffness in {@code N/m}
     * @param damping        damping in {@code N·s/m}
     * @param separationSpeed positive when the anchors move apart
     * @param maxTension     maximum tension in newtons
     * @return tension in newtons
     */
    static double computeTension(double distance, double restLength, double stiffness,
                                 double damping, double separationSpeed, double maxTension) {
        if (!Double.isFinite(distance) || !Double.isFinite(restLength)
                || !Double.isFinite(stiffness) || !Double.isFinite(damping)
                || !Double.isFinite(separationSpeed) || !Double.isFinite(maxTension)) {
            return 0.0;
        }
        final double extension = distance - restLength;
        if (extension <= 0.0 || maxTension <= 0.0) {
            return 0.0;
        }
        final double tension = Math.max(0.0, stiffness) * extension
                + Math.max(0.0, damping) * Math.max(0.0, separationSpeed);
        return Math.min(tension, Math.max(0.0, maxTension));
    }

    private void updateEndpoints(Vector3d outA, Vector3d outB) {
        final Transform3d poseA = structureA.closed() ? Transform3d.IDENTITY : structureA.pose();
        poseA.transformPoint(anchorA, outA);
        if (worldAnchored) {
            outB.set(anchorB);
        } else if (structureB != null && !structureB.closed()) {
            structureB.pose().transformPoint(anchorB, outB);
        } else {
            outB.set(anchorB);
        }
    }

    private static double requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative: " + value);
        }
        return value;
    }

    @Override
    public String toString() {
        return "StructureRope[bodyA=" + structureA.body().id()
                + (structureB == null ? ", world" : ", bodyB=" + structureB.body().id())
                + ", length=" + length + ", closed=" + closed + "]";
    }
}
