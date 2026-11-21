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
}
