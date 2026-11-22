package io.mikaple.crythst.physics.bullet;

import com.jme3.bullet.joints.Constraint;
import com.jme3.bullet.joints.HingeJoint;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.bullet.joints.PhysicsJoint;
import com.jme3.bullet.joints.motors.MotorParam;
import com.jme3.bullet.objects.PhysicsRigidBody;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.MotorConstraintHandle;
import io.mikaple.crythst.physics.constraint.SixDofConstraintHandle;
import io.mikaple.crythst.util.Validate;

/**
 * libbulletjme-backed joint handle.
 */
public final class BulletConstraintHandle implements MotorConstraintHandle, SixDofConstraintHandle {
    private final BulletPhysicsWorld world;
    private final long id;
    private final ConstraintType type;
    private final PhysicsJoint joint;
    private final BodyHandle bodyA;
    private final BodyHandle bodyB;
    private Object metadata;
    private boolean enabled;
    private boolean removed;

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
    public boolean removed() {
        return removed;
    }

    void markRemoved() {
        removed = true;
        enabled = false;
    }

    @Override
    public Object metadata() {
        return metadata;
    }

    @Override
    public void setMetadata(Object metadata) {
        this.metadata = metadata;
    }

    @Override
    public double appliedImpulse() {
        return joint instanceof Constraint constraint ? constraint.getAppliedImpulse() : 0.0;
    }

    @Override
    public boolean feedbackEnabled() {
        return joint instanceof Constraint constraint && constraint.isFeedback();
    }

    @Override
    public void setFeedbackEnabled(boolean value) {
        if (joint instanceof Constraint constraint) {
            constraint.setFeedback(value);
        }
    }

    private New6Dof dof(String operation) {
        if (!(joint instanceof New6Dof sixDof)) {
            throw new UnsupportedOperationException(
                    operation + " is only supported for 6-DOF joints, not " + type);
        }
        return sixDof;
    }

    private static void checkDof(int dofIndex) {
        if (dofIndex < 0 || dofIndex >= 6) {
            throw new IllegalArgumentException("DOF index must be in [0, 5]: " + dofIndex);
        }
    }

    @Override
    public boolean isAxisFree(int dofIndex) {
        checkDof(dofIndex);
        final New6Dof sixDof = dof("isAxisFree");
        return sixDof.get(MotorParam.LowerLimit, dofIndex)
                > sixDof.get(MotorParam.UpperLimit, dofIndex);
    }

    @Override
    public void setAxisFree(int dofIndex, boolean free) {
        checkDof(dofIndex);
        final New6Dof sixDof = dof("setAxisFree");
        if (free) {
            // Bullet's convention for an unconstrained axis.
            sixDof.set(MotorParam.LowerLimit, dofIndex, 1.0f);
            sixDof.set(MotorParam.UpperLimit, dofIndex, -1.0f);
        } else {
            sixDof.set(MotorParam.LowerLimit, dofIndex, 0.0f);
            sixDof.set(MotorParam.UpperLimit, dofIndex, 0.0f);
        }
    }

    @Override
    public double lowerLimit(int dofIndex) {
        checkDof(dofIndex);
        return dof("lowerLimit").get(MotorParam.LowerLimit, dofIndex);
    }

    @Override
    public double upperLimit(int dofIndex) {
        checkDof(dofIndex);
        return dof("upperLimit").get(MotorParam.UpperLimit, dofIndex);
    }

    @Override
    public void setLimits(int dofIndex, double lowerLimit, double upperLimit) {
        checkDof(dofIndex);
        Validate.finite(lowerLimit, "lowerLimit");
        Validate.finite(upperLimit, "upperLimit");
        if (lowerLimit > upperLimit) {
            throw new IllegalArgumentException(
                    "lowerLimit must not exceed upperLimit; use setAxisFree for an unconstrained axis");
        }
        final New6Dof sixDof = dof("setLimits");
        sixDof.set(MotorParam.LowerLimit, dofIndex, (float) lowerLimit);
        sixDof.set(MotorParam.UpperLimit, dofIndex, (float) upperLimit);
    }

    @Override
    public boolean isMotorEnabled(int dofIndex) {
        checkDof(dofIndex);
        return dof("isMotorEnabled").isMotorEnabled(dofIndex);
    }

    @Override
    public void setMotorEnabled(int dofIndex, boolean value) {
        checkDof(dofIndex);
        final New6Dof sixDof = dof("setMotorEnabled");
        if (dofIndex >= 3) {
            sixDof.getRotationMotor(dofIndex - 3).setMotorEnabled(value);
        } else {
            sixDof.getTranslationMotor().setMotorEnabled(dofIndex, value);
        }
    }

    @Override
    public double motorTargetVelocity(int dofIndex) {
        checkDof(dofIndex);
        return dof("motorTargetVelocity").get(MotorParam.TargetVelocity, dofIndex);
    }

    @Override
    public void setMotorTargetVelocity(int dofIndex, double velocity) {
        checkDof(dofIndex);
        Validate.finite(velocity, "velocity");
        dof("setMotorTargetVelocity").set(MotorParam.TargetVelocity, dofIndex, (float) velocity);
    }

    @Override
    public double maxMotorForce(int dofIndex) {
        checkDof(dofIndex);
        return dof("maxMotorForce").get(MotorParam.MaxMotorForce, dofIndex);
    }

    @Override
    public void setMaxMotorForce(int dofIndex, double maxForce) {
        checkDof(dofIndex);
        Validate.nonNegative(maxForce, "maxForce");
        dof("setMaxMotorForce").set(MotorParam.MaxMotorForce, dofIndex, (float) maxForce);
    }

    @Override
    public boolean isSpringEnabled(int dofIndex) {
        checkDof(dofIndex);
        return dof("isSpringEnabled").isSpringEnabled(dofIndex);
    }

    @Override
    public void setSpringEnabled(int dofIndex, boolean value) {
        checkDof(dofIndex);
        final New6Dof sixDof = dof("setSpringEnabled");
        sixDof.enableSpring(dofIndex, value);
        if (value) {
            // Anchor the spring at the constraint's current relative pose, not
            // at a stale equilibrium left over from construction.
            sixDof.setEquilibriumPoint(dofIndex);
        }
    }

    @Override
    public double stiffness(int dofIndex) {
        checkDof(dofIndex);
        return dof("stiffness").get(MotorParam.Stiffness, dofIndex);
    }

    @Override
    public void setStiffness(int dofIndex, double stiffness) {
        checkDof(dofIndex);
        Validate.nonNegative(stiffness, "stiffness");
        dof("setStiffness").setStiffness(dofIndex, (float) stiffness, true);
    }

    @Override
    public double damping(int dofIndex) {
        checkDof(dofIndex);
        return dof("damping").get(MotorParam.Damping, dofIndex);
    }

    @Override
    public void setDamping(int dofIndex, double damping) {
        checkDof(dofIndex);
        Validate.nonNegative(damping, "damping");
        dof("setDamping").setDamping(dofIndex, (float) damping, true);
    }

    private HingeJoint hinge(String operation) {
        if (!(joint instanceof HingeJoint hinge)) {
            throw new UnsupportedOperationException(
                    operation + " is only supported for hinge joints, not " + type);
        }
        return hinge;
    }

    @Override
    public boolean motorEnabled() {
        return hinge("motorEnabled").getEnableMotor();
    }

    @Override
    public void setMotorEnabled(boolean value) {
        final HingeJoint hinge = hinge("setMotorEnabled");
        hinge.enableMotor(value, hinge.getMotorTargetVelocity(), hinge.getMaxMotorImpulse());
    }

    @Override
    public double motorTargetVelocity() {
        return hinge("motorTargetVelocity").getMotorTargetVelocity();
    }

    @Override
    public void setMotorTargetVelocity(double velocity) {
        Validate.finite(velocity, "velocity");
        final HingeJoint hinge = hinge("setMotorTargetVelocity");
        hinge.enableMotor(hinge.getEnableMotor(), (float) velocity, hinge.getMaxMotorImpulse());
    }

    @Override
    public double maxMotorImpulse() {
        return hinge("maxMotorImpulse").getMaxMotorImpulse();
    }

    @Override
    public void setMaxMotorImpulse(double maxImpulse) {
        Validate.nonNegative(maxImpulse, "maxImpulse");
        final HingeJoint hinge = hinge("setMaxMotorImpulse");
        hinge.enableMotor(hinge.getEnableMotor(), hinge.getMotorTargetVelocity(), (float) maxImpulse);
    }

    @Override
    public String toString() {
        return "BulletConstraintHandle[" + id + ", " + type + "]";
    }
}
