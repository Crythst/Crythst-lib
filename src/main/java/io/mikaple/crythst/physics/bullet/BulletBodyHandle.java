package io.mikaple.crythst.physics.bullet;

import com.jme3.bounding.BoundingBox;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodySnapshot;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.shape.PhysicsShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * libbulletjme-backed {@link BodyHandle}.
 */
public final class BulletBodyHandle implements BodyHandle {
    private final BulletPhysicsWorld world;
    private final long id;
    private final PhysicsRigidBody body;

    private PhysicsShape shape;
    private PhysicsMaterial material;
    private BodyType type;
    private double gravityScale;
    private Vector3d gravityOverride;
    private double dynamicMass;
    private boolean ccd;
    private double ccdRadius;
    private Object userData;
    private boolean removeRequested;

    BulletBodyHandle(BulletPhysicsWorld world, long id, PhysicsRigidBody body,
                     PhysicsShape shape, PhysicsMaterial material, BodyType type,
                     double gravityScale, Vector3d gravityOverride,
                     boolean ccd, double ccdRadius, Object userData) {
        this.world = world;
        this.id = id;
        this.body = body;
        this.shape = shape;
        this.material = material;
        this.type = type;
        this.gravityScale = gravityScale;
        this.gravityOverride = gravityOverride;
        this.dynamicMass = body.getMass();
        this.ccd = ccd;
        this.ccdRadius = ccdRadius;
        this.userData = userData;
    }

    public PhysicsRigidBody bulletBody() {
        return body;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public BodyType type() {
        return type;
    }

    @Override
    public void setType(BodyType newType) {
        if (newType == null || newType == type) {
            return;
        }
        switch (newType) {
            case STATIC -> {
                rememberDynamicMass();
                body.setKinematic(false);
                body.setMass(0.0f);
                body.setGravity(new Vector3f());
                body.setLinearVelocity(new Vector3f());
                body.setAngularVelocity(new Vector3f());
            }
            case KINEMATIC -> {
                rememberDynamicMass();
                body.setMass(0.0f);
                body.setKinematic(true);
                body.setGravity(new Vector3f());
            }
            case DYNAMIC -> {
                body.setKinematic(false);
                body.setMass((float) (dynamicMass > 0.0 ? dynamicMass : world.defaultDynamicMass(shape, material)));
                applyGravity();
            }
        }
        type = newType;
        body.activate();
    }

    private void rememberDynamicMass() {
        if (type == BodyType.DYNAMIC && body.getMass() > 0.0f) {
            dynamicMass = body.getMass();
        }
    }

    @Override
    public Transform3d transform() {
        final Vector3f location = body.getPhysicsLocation(new Vector3f());
        final Quaternion rotation = body.getPhysicsRotation(new Quaternion());
        return BulletConversions.toTransform3d(location, rotation, world.originRef());
    }

    @Override
    public void setTransform(Transform3d transform) {
        final Vector3f location = BulletConversions.toLocal(transform.position(), world.originRef(), new Vector3f());
        body.setPhysicsLocation(location);
        body.setPhysicsRotation(BulletConversions.toQuaternion(transform.rotation(), new Quaternion()));
    }

    @Override
    public void teleport(Transform3d transform) {
        setTransform(transform);
        body.clearForces();
        body.setLinearVelocity(new Vector3f());
        body.setAngularVelocity(new Vector3f());
        body.activate();
        world.notifyTeleport(this);
    }

    void shiftLocalOrigin(Vector3dc delta) {
        final Vector3f location = body.getPhysicsLocation(new Vector3f());
        location.subtractLocal((float) delta.x(), (float) delta.y(), (float) delta.z());
        body.setPhysicsLocation(location);
    }

    @Override
    public Aabb aabb() {
        final BoundingBox box = body.boundingBox(new BoundingBox());
        final Vector3f min = box.getMin(new Vector3f());
        final Vector3f max = box.getMax(new Vector3f());
        final Vector3d origin = world.originRef();
        return new Aabb(
                new Vector3d(min.x + origin.x, min.y + origin.y, min.z + origin.z),
                new Vector3d(max.x + origin.x, max.y + origin.y, max.z + origin.z)
        );
    }

    @Override
    public double mass() {
        return body.getMass();
    }

    @Override
    public void setMass(double mass) {
        if (mass < 0.0) {
            throw new IllegalArgumentException("mass must not be negative");
        }
        if (type == BodyType.DYNAMIC) {
            if (mass == 0.0) {
                throw new IllegalArgumentException("A dynamic body must have positive mass");
            }
            dynamicMass = mass;
            body.setMass((float) mass);
        } else {
            dynamicMass = mass;
        }
    }

    @Override
    public PhysicsShape shape() {
        return shape;
    }

    @Override
    public void setShape(PhysicsShape newShape, double mass) {
        this.shape = newShape;
        body.setCollisionShape(BulletShapeFactory.create(newShape));
        if (type == BodyType.DYNAMIC) {
            final double resolved = mass > 0.0 ? mass : world.defaultDynamicMass(newShape, material);
            dynamicMass = resolved;
            body.setMass((float) resolved);
        }
        body.activate();
    }

    @Override
    public PhysicsMaterial material() {
        return material;
    }

    @Override
    public void setMaterial(PhysicsMaterial newMaterial) {
        this.material = newMaterial;
        body.setFriction((float) newMaterial.friction());
        body.setRestitution((float) newMaterial.restitution());
        body.setRollingFriction((float) newMaterial.rollingFriction());
        body.setSpinningFriction((float) newMaterial.spinningFriction());
    }

    @Override
    public void setGravityScale(double scale) {
        this.gravityScale = scale;
        applyGravity();
    }

    @Override
    public double gravityScale() {
        return gravityScale;
    }

    @Override
    public void setGravityOverride(Vector3dc gravity) {
        this.gravityOverride = gravity == null ? null : new Vector3d(gravity);
        applyGravity();
    }

    @Override
    public void clearGravityOverride() {
        this.gravityOverride = null;
        applyGravity();
    }

    @Override
    public Vector3d gravityOverride() {
        return gravityOverride == null ? null : new Vector3d(gravityOverride);
    }

    /**
     * Recomputes the native per-body gravity from the override or world gravity.
     */
    void applyGravity() {
        if (type != BodyType.DYNAMIC) {
            body.setGravity(new Vector3f());
            return;
        }
        if (gravityOverride != null) {
            body.setGravity(BulletConversions.toVector3f(gravityOverride, new Vector3f()));
        } else {
            body.setGravity(world.gravityFor(gravityScale));
        }
    }

    @Override
    public void setDamping(double linear, double angular) {
        body.setDamping((float) linear, (float) angular);
    }

    @Override
    public void setLinearVelocity(Vector3dc velocity) {
        body.setLinearVelocity(BulletConversions.toVector3f(velocity, new Vector3f()));
    }

    @Override
    public void setAngularVelocity(Vector3dc velocity) {
        body.setAngularVelocity(BulletConversions.toVector3f(velocity, new Vector3f()));
    }

    @Override
    public Vector3d linearVelocity() {
        return BulletConversions.toVector3d(body.getLinearVelocity(new Vector3f()), new Vector3d());
    }

    @Override
    public Vector3d angularVelocity() {
        return BulletConversions.toVector3d(body.getAngularVelocity(new Vector3f()), new Vector3d());
    }

    @Override
    public void applyCentralForce(Vector3dc force) {
        body.applyCentralForce(BulletConversions.toVector3f(force, new Vector3f()));
    }

    @Override
    public void applyCentralImpulse(Vector3dc impulse) {
        body.applyCentralImpulse(BulletConversions.toVector3f(impulse, new Vector3f()));
    }

    @Override
    public void applyImpulseAtPoint(Vector3dc impulse, Vector3dc worldPoint) {
        final Vector3f bodyLocation = body.getPhysicsLocation(new Vector3f());
        final Vector3f localPoint = BulletConversions.toLocal(worldPoint, world.originRef(), new Vector3f());
        final Vector3f relative = localPoint.subtract(bodyLocation);
        body.applyImpulse(BulletConversions.toVector3f(impulse, new Vector3f()), relative);
    }

    @Override
    public void applyTorque(Vector3dc torque) {
        body.applyTorque(BulletConversions.toVector3f(torque, new Vector3f()));
    }

    @Override
    public void applyTorqueImpulse(Vector3dc impulse) {
        body.applyTorqueImpulse(BulletConversions.toVector3f(impulse, new Vector3f()));
    }

    @Override
    public void clearForces() {
        body.clearForces();
    }

    @Override
    public boolean active() {
        return body.isActive();
    }

    @Override
    public void activate() {
        body.activate();
    }

    @Override
    public void setSleepingThresholds(double linear, double angular) {
        body.setSleepingThresholds((float) linear, (float) angular);
    }

    @Override
    public void setLinearFactor(Vector3dc factor) {
        body.setLinearFactor(BulletConversions.toVector3f(factor, new Vector3f()));
    }

    @Override
    public void setAngularFactor(Vector3dc factor) {
        body.setAngularFactor(BulletConversions.toVector3f(factor, new Vector3f()));
    }

    @Override
    public void setCcd(boolean enabled, double sweptSphereRadius) {
        this.ccd = enabled;
        this.ccdRadius = sweptSphereRadius;
        if (!enabled) {
            body.setCcdMotionThreshold(0.0f);
            body.setCcdSweptSphereRadius(0.0f);
            return;
        }
        final double radius = sweptSphereRadius > 0.0
                ? sweptSphereRadius
                : Math.max(0.02, minimumHalfExtent(shape) * 0.5);
        this.ccdRadius = radius;
        body.setCcdMotionThreshold((float) (radius * 0.5));
        body.setCcdSweptSphereRadius((float) radius);
    }

    @Override
    public boolean ccd() {
        return ccd;
    }

    @Override
    public boolean removeRequested() {
        return removeRequested;
    }

    @Override
    public void requestRemove() {
        removeRequested = true;
    }

    @Override
    public boolean inWorld() {
        return body.isInWorld();
    }

    @Override
    public Object userData() {
        return userData;
    }

    @Override
    public void setUserData(Object newUserData) {
        this.userData = newUserData;
    }

    @Override
    public BodySnapshot snapshot() {
        return new BodySnapshot(id, type, transform(), linearVelocity(), angularVelocity(),
                mass(), active(), aabb());
    }

    private static double minimumHalfExtent(PhysicsShape shape) {
        final Vector3d half = shape.localBounds().halfExtents();
        return Math.min(half.x, Math.min(half.y, half.z));
    }

    @Override
    public String toString() {
        return "BulletBodyHandle[" + id + ", " + type + "]";
    }
}
