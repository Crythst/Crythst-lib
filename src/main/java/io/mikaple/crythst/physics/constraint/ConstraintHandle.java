package io.mikaple.crythst.physics.constraint;

/**
 * A live joint owned by a world.
 */
public interface ConstraintHandle {
    long id();

    ConstraintType type();

    boolean enabled();

    void setEnabled(boolean enabled);

    int bodyCount();

    /**
     * Returns true when the joint references the given body.
     */
    default boolean involves(io.mikaple.crythst.physics.body.BodyHandle body) {
        return false;
    }

    /**
     * Opaque metadata attached by a higher layer (for example the API-layer
     * configuration used to create the joint). Backends never interpret it.
     */
    default Object metadata() {
        return null;
    }

    /** Replaces the opaque metadata; see {@link #metadata()}. */
    default void setMetadata(Object metadata) {
    }

    /**
     * Total impulse applied by the solver during the most recent step, in
     * newton-seconds. Returns zero when the backend/constraint does not expose
     * feedback. Per-axis components are not exposed by Bullet.
     */
    default double appliedImpulse() {
        return 0.0;
    }

    /** Whether the backend has removed this joint from its world. */
    default boolean removed() {
        return false;
    }

    /** Whether solver feedback is enabled for {@link #appliedImpulse()}. */
    default boolean feedbackEnabled() {
        return false;
    }

    /** Enables or disables solver force/impulse feedback. */
    default void setFeedbackEnabled(boolean enabled) {
    }
}
