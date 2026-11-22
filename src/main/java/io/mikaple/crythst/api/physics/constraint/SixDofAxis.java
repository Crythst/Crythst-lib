package io.mikaple.crythst.api.physics.constraint;

/**
 * The six degrees of freedom of a {@link SixDofConstraintConfiguration}, in
 * Bullet's index order.
 */
public enum SixDofAxis {
    LINEAR_X,
    LINEAR_Y,
    LINEAR_Z,
    ANGULAR_X,
    ANGULAR_Y,
    ANGULAR_Z;

    /** Bullet DOF index in {@code [0, 5]}. */
    public int index() {
        return ordinal();
    }

    public boolean linear() {
        return ordinal() < 3;
    }

    /** Resolves an index, rejecting values outside {@code [0, 5]}. */
    public static SixDofAxis ofIndex(int index) {
        if (index < 0 || index >= values().length) {
            throw new IllegalArgumentException("DOF index must be in [0, 5]: " + index);
        }
        return values()[index];
    }
}
