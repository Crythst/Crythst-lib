package io.mikaple.crythst.physics.character;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;

/**
 * Description of a kinematic character controller.
 */
public final class CharacterDef {
    private final double radius;
    private final double height;
    private final Transform3d pose;
    private final Vector3d up;
    private final double stepHeight;
    private final double maxSlope;
    private final double jumpSpeed;
    private final double fallSpeed;
    private final double gravityScale;
    private final double linearDamping;
    private final double angularDamping;

    private CharacterDef(Builder builder) {
        this.radius = builder.radius;
        this.height = builder.height;
        this.pose = builder.pose;
        this.up = builder.up == null ? null : new Vector3d(builder.up);
        this.stepHeight = builder.stepHeight;
        this.maxSlope = builder.maxSlope;
        this.jumpSpeed = builder.jumpSpeed;
        this.fallSpeed = builder.fallSpeed;
        this.gravityScale = builder.gravityScale;
        this.linearDamping = builder.linearDamping;
        this.angularDamping = builder.angularDamping;
    }

    public static Builder builder(double radius, double totalHeight) {
        return new Builder(radius, totalHeight);
    }

    public double radius() {
        return radius;
    }

    public double height() {
        return height;
    }

    public Transform3d pose() {
        return pose;
    }

    /**
     * @return explicit up vector, or {@code null} to use the world's up vector
     */
    public Vector3d up() {
        return up == null ? null : new Vector3d(up);
    }

    public double stepHeight() {
        return stepHeight;
    }

    public double maxSlope() {
        return maxSlope;
    }

    public double jumpSpeed() {
        return jumpSpeed;
    }

    public double fallSpeed() {
        return fallSpeed;
    }

    public double gravityScale() {
        return gravityScale;
    }

    public double linearDamping() {
        return linearDamping;
    }

    public double angularDamping() {
        return angularDamping;
    }

    public static final class Builder {
        private final double radius;
        private final double height;
        private Transform3d pose = Transform3d.IDENTITY;
        private Vector3d up;
        private double stepHeight = 0.6;
        private double maxSlope = Math.toRadians(50.0);
        private double jumpSpeed = 8.0;
        private double fallSpeed = 55.0;
        private double gravityScale = 1.0;
        private double linearDamping;
        private double angularDamping;

        private Builder(double radius, double totalHeight) {
            this.radius = Validate.positive(radius, "radius");
            this.height = Validate.positive(totalHeight, "totalHeight");
            if (totalHeight < radius * 2.0) {
                throw new IllegalArgumentException("totalHeight must be at least the capsule diameter");
            }
        }

        public Builder pose(Transform3d newPose) {
            this.pose = Validate.notNull(newPose, "pose");
            return this;
        }

        public Builder up(org.joml.Vector3dc newUp) {
            this.up = new Vector3d(Validate.notNull(newUp, "up")).normalize();
            return this;
        }

        public Builder stepHeight(double value) {
            this.stepHeight = Validate.nonNegative(value, "stepHeight");
            return this;
        }

        public Builder maxSlope(double radians) {
            this.maxSlope = Validate.nonNegative(radians, "maxSlope");
            return this;
        }

        public Builder jumpSpeed(double value) {
            this.jumpSpeed = Validate.nonNegative(value, "jumpSpeed");
            return this;
        }

        public Builder fallSpeed(double value) {
            this.fallSpeed = Validate.positive(value, "fallSpeed");
            return this;
        }

        public Builder gravityScale(double value) {
            this.gravityScale = Validate.nonNegative(value, "gravityScale");
            return this;
        }

        public Builder damping(double linear, double angular) {
            this.linearDamping = Validate.nonNegative(linear, "linearDamping");
            this.angularDamping = Validate.nonNegative(angular, "angularDamping");
            return this;
        }

        public CharacterDef build() {
            return new CharacterDef(this);
        }
    }
}
