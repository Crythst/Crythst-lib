package io.mikaple.crythst.physics.bullet;

import com.jme3.bullet.joints.PhysicsJoint;
import com.jme3.bullet.objects.PhysicsRigidBody;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.constraint.ConstraintHandle;
import io.mikaple.crythst.physics.constraint.ConstraintType;

/**
 * libbulletjme-backed joint handle.
 */
public final class BulletConstraintHandle implements ConstraintHandle {
    private final BulletPhysicsWorld world;
    private final long id;
    private final ConstraintType type;
    private final PhysicsJoint joint;
    private final BodyHandle bodyA;
    private final BodyHandle bodyB;
    private boolean enabled;

    BulletConstraintHandle(BulletPhysicsWorld world, long id, ConstraintType type,
                           PhysicsJoint joint, BodyHandle bodyA, BodyHandle bodyB) {
        this.world = world;
        this.id = id;
        this.type = type;
        this.joint = joint;
        this.bodyA = bodyA;
        this.bodyB = bodyB;
        this.enabled = true;
    }

    public PhysicsJoint bulletJoint() {
        return joint;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public ConstraintType type() {
        return type;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean value) {
        if (value == enabled) {
            return;
        }
        if (value) {
            world.bulletSpace().addJoint(joint);
        } else {
            world.bulletSpace().removeJoint(joint);
        }
        enabled = value;
    }

    @Override
    public int bodyCount() {
        return bodyB == null ? 1 : 2;
    }

    @Override
    public boolean involves(BodyHandle body) {
        return bodyA == body || bodyB == body
                || (body != null && (body.id() == bodyA.id()
                || (bodyB != null && body.id() == bodyB.id())));
    }

    boolean involves(PhysicsRigidBody body) {
        return joint.getBodyA() == body || joint.getBodyB() == body;
    }

    @Override
    public String toString() {
        return "BulletConstraintHandle[" + id + ", " + type + "]";
    }
}
