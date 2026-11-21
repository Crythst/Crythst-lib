package io.mikaple.crythst.physics.body;

import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.collision.CollisionFilter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.shape.PhysicsShape;
import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Immutable description of a rigid body. Use {@link #builder(PhysicsShape)} and
 * pass the result to {@code PhysicsWorld#createBody(BodyDef)}.
 */
public final class BodyDef {
    private final PhysicsShape shape;
    private final BodyType type;
    private final double mass;
    private final Transform3d transform;
    private final PhysicsMaterial material;
    private final CollisionFilter filter;
    private final Vector3d linearVelocity;
    private final Vector3d angularVelocity;
    private final double gravityScale;
    private final double linearDamping;
    private final double angularDamping;
    private final boolean ccd;
    private final double ccdRadius;
    private final boolean allowSleep;
    private final Vector3d linearFactor;
    private final Vector3d angularFactor;
    private final Vector3d gravityOverride;
    private final boolean contactResponse;
    private final Object userData;

    private BodyDef(Builder builder) {
        this.shape = builder.shape;
        this.type = builder.type;
        this.mass = builder.mass;
        this.transform = builder.transform;
        this.material = builder.material;
        this.filter = builder.filter;
        this.linearVelocity = new Vector3d(builder.linearVelocity);
        this.angularVelocity = new Vector3d(builder.angularVelocity);
        this.gravityScale = builder.gravityScale;
        this.linearDamping = builder.linearDamping;
        this.angularDamping = builder.angularDamping;
        this.ccd = builder.ccd;
        this.ccdRadius = builder.ccdRadius;
        this.allowSleep = builder.allowSleep;
        this.linearFactor = new Vector3d(builder.linearFactor);
        this.angularFactor = new Vector3d(builder.angularFactor);
        this.gravityOverride = builder.gravityOverride == null ? null : new Vector3d(builder.gravityOverride);
        this.contactResponse = builder.contactResponse;
        this.userData = builder.userData;
    }

    public static Builder builder(PhysicsShape shape) {
        return new Builder(shape, BodyType.DYNAMIC);
    }

    public static Builder builder(PhysicsShape shape, BodyType type) {
        return new Builder(shape, type);
    }

    public static Builder dynamic(PhysicsShape shape) {
        return new Builder(shape, BodyType.DYNAMIC);
    }

    public static Builder kinematic(PhysicsShape shape) {
        return new Builder(shape, BodyType.KINEMATIC);
    }

    public static Builder staticBody(PhysicsShape shape) {
        return new Builder(shape, BodyType.STATIC);
    }

    public PhysicsShape shape() {
        return shape;
    }

    public BodyType type() {
        return type;
    }

    /**
     * @return the explicit mass, or {@code 0} when it should be derived from
     *         shape volume and material density
     */
    public double mass() {
        return mass;
    }

    public double effectiveMass() {
        if (!type.infiniteMass()) {
            if (mass > 0.0) {
                return mass;
            }
            final double derived = shape.volume() * material.density();
            if (derived <= 0.0) {
                throw new IllegalStateException(
                        "A dynamic body needs an explicit mass when its shape has no computable volume");
            }
            return derived;
        }
        return 0.0;
    }

    public Transform3d transform() {
        return transform;
    }

    public PhysicsMaterial material() {
        return material;
    }

    public CollisionFilter filter() {
        return filter;
    }

    public Vector3d linearVelocity() {
        return new Vector3d(linearVelocity);
    }

    public Vector3d angularVelocity() {
        return new Vector3d(angularVelocity);
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

    public boolean ccd() {
        return ccd;
    }

    public double ccdRadius() {
        return ccdRadius;
    }

    public boolean allowSleep() {
        return allowSleep;
    }

    public Vector3d linearFactor() {
        return new Vector3d(linearFactor);
    }

    public Vector3d angularFactor() {
        return new Vector3d(angularFactor);
    }

    /**
     * @return a per-body gravity vector overriding world gravity, or {@code null}
     */
    public Vector3d gravityOverride() {
        return gravityOverride == null ? null : new Vector3d(gravityOverride);
    }

    public boolean contactResponse() {
        return contactResponse;
    }

    public Object userData() {
        return userData;
    }

    public static final class Builder {
        private final PhysicsShape shape;
        private BodyType type;
        private double mass;
        private Transform3d transform = Transform3d.IDENTITY;
        private PhysicsMaterial material = PhysicsMaterial.DEFAULT;
        private CollisionFilter filter = CollisionFilter.DEFAULT;
        private Vector3d linearVelocity = new Vector3d();
        private Vector3d angularVelocity = new Vector3d();
        private double gravityScale = 1.0;
        private double linearDamping;
        private double angularDamping;
        private boolean ccd;
        private double ccdRadius = -1.0;
        private boolean allowSleep = true;
        private Vector3d linearFactor = new Vector3d(1.0);
        private Vector3d angularFactor = new Vector3d(1.0);
        private Vector3d gravityOverride;
        private boolean contactResponse = true;
        private Object userData;

        private Builder(PhysicsShape shape, BodyType type) {
            this.shape = Validate.notNull(shape, "shape");
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder type(BodyType newType) {
            this.type = Objects.requireNonNull(newType, "type");
            return this;
        }

        public Builder mass(double newMass) {
            this.mass = Validate.nonNegative(newMass, "mass");
            return this;
        }

        public Builder transform(Transform3d newTransform) {
            this.transform = Objects.requireNonNull(newTransform, "transform");
            return this;
        }

        public Builder position(Vector3dc position) {
            this.transform = transform.withPosition(position);
            return this;
        }

        public Builder material(PhysicsMaterial newMaterial) {
            this.material = Objects.requireNonNull(newMaterial, "material");
            return this;
        }

        public Builder filter(CollisionFilter newFilter) {
            this.filter = Objects.requireNonNull(newFilter, "filter");
            return this;
        }

        public Builder linearVelocity(Vector3dc velocity) {
            this.linearVelocity = new Vector3d(Validate.notNull(velocity, "velocity"));
            return this;
        }

        public Builder angularVelocity(Vector3dc velocity) {
            this.angularVelocity = new Vector3d(Validate.notNull(velocity, "velocity"));
            return this;
        }

        public Builder gravityScale(double scale) {
            this.gravityScale = Validate.nonNegative(scale, "gravityScale");
            return this;
        }

        public Builder damping(double linear, double angular) {
            this.linearDamping = Validate.nonNegative(linear, "linearDamping");
            this.angularDamping = Validate.nonNegative(angular, "angularDamping");
            return this;
        }

        public Builder ccd(boolean enabled) {
            this.ccd = enabled;
            return this;
        }

        /**
         * @param radius swept-sphere radius; negative values derive it from the
         *               shape bounds
         */
        public Builder ccdRadius(double radius) {
            this.ccdRadius = radius;
            return this;
        }

        public Builder allowSleep(boolean enabled) {
            this.allowSleep = enabled;
            return this;
        }

        public Builder linearFactor(Vector3dc factor) {
            this.linearFactor = new Vector3d(Validate.notNull(factor, "factor"));
            return this;
        }

        public Builder angularFactor(Vector3dc factor) {
            this.angularFactor = new Vector3d(Validate.notNull(factor, "factor"));
            return this;
        }

        /**
         * Gives this body its own gravity direction and magnitude, independent
         * of the world. Useful for local gravity fields, magnets and custom
         * dimensions.
         */
        public Builder gravityOverride(Vector3dc gravity) {
            this.gravityOverride = gravity == null ? null : new Vector3d(gravity);
            return this;
        }

        public Builder contactResponse(boolean enabled) {
            this.contactResponse = enabled;
            return this;
        }

        public Builder userData(Object newUserData) {
            this.userData = newUserData;
            return this;
        }

        public BodyDef build() {
            if (type == BodyType.DYNAMIC && mass == 0.0
                    && shape.volume() * material.density() <= 0.0) {
                throw new IllegalStateException(
                        "Dynamic bodies need either an explicit mass or a shape with computable volume");
            }
            return new BodyDef(this);
        }
    }
}
