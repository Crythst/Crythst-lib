package io.mikaple.crythst.physics.constraint;

/**
 * Supported joint families.
 */
public enum ConstraintType {
    FIXED,
    HINGE,
    SLIDER,
    POINT,
    /** Full per-axis 6-DOF joint (Bullet generic 6-DOF spring2). */
    SIX_DOF
}
