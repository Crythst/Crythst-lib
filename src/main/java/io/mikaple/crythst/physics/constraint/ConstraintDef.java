package io.mikaple.crythst.physics.constraint;

/**
 * Backend-independent joint description. Instances are created through the
 * record sub-types and passed to {@code PhysicsWorld#addConstraint}.
 */
public interface ConstraintDef {
    ConstraintType type();
}
