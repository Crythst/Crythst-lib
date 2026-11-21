package io.mikaple.crythst.physics.shape;

/**
 * Local axis selector. Ordinals match Bullet's axis indices (0=X, 1=Y, 2=Z).
 */
public enum Axis {
    X,
    Y,
    Z;

    public int bulletIndex() {
        return ordinal();
    }
}
