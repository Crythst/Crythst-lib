package io.mikaple.crythst.physics.bullet;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.ManifoldPoints;
import com.jme3.bullet.collision.PersistentManifolds;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.PhysicsRayTestResult;
import com.jme3.bullet.collision.PhysicsSweepTestResult;
import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.collision.shapes.ConvexShape;
import com.jme3.bullet.joints.HingeJoint;
import com.jme3.bullet.joints.JointEnd;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.bullet.joints.PhysicsJoint;
import com.jme3.bullet.joints.Point2PointJoint;
import com.jme3.bullet.joints.SixDofJoint;
import com.jme3.bullet.joints.SliderJoint;
import com.jme3.bullet.joints.motors.MotorParam;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.bullet.RotationOrder;
import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.collision.ContactEvent;
import io.mikaple.crythst.physics.collision.ContactListener;
import io.mikaple.crythst.physics.collision.ContactPoint;
import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintHandle;
import io.mikaple.crythst.physics.constraint.ConstraintType;
import io.mikaple.crythst.physics.constraint.FixedConstraintDef;
import io.mikaple.crythst.physics.constraint.HingeConstraintDef;
import io.mikaple.crythst.physics.constraint.PointConstraintDef;
import io.mikaple.crythst.physics.constraint.SixDofConstraintDef;
import io.mikaple.crythst.physics.constraint.SliderConstraintDef;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.query.RayHit;
import io.mikaple.crythst.physics.query.BodyFilter;
import io.mikaple.crythst.physics.query.RayQuery;
import io.mikaple.crythst.physics.query.SweepHit;
import io.mikaple.crythst.physics.query.SweepQuery;
import io.mikaple.crythst.physics.shape.PhysicsShape;
import io.mikaple.crythst.physics.tick.FixedStepClock;
import io.mikaple.crythst.physics.world.Broadphase;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.StepListener;
import io.mikaple.crythst.physics.world.StepStats;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * libbulletjme-backed physics world.
 *
 * <p>All public positions are world-space doubles. Internally the world keeps a
 * rebasable origin so that structures can travel far from spawn without losing
 * single-precision accuracy.</p>
 */
public final class BulletPhysicsWorld implements PhysicsWorld {
    private final BulletPhysicsEngine engine;
    private final CrythstPhysicsSpace space;
    private final FixedStepClock clock;
    private final Map<Long, BulletBodyHandle> bodies = new LinkedHashMap<>();
    private final Map<Long, BulletConstraintHandle> constraints = new LinkedHashMap<>();
    private final List<ContactListener> contactListeners = new CopyOnWriteArrayList<>();
    private final List<StepListener> stepListeners = new CopyOnWriteArrayList<>();
    private final List<ContactEvent> pendingContacts = new ArrayList<>();
    private List<ContactEvent> lastContacts = List.of();
    private boolean captureDebugContacts;

    private WorldSettings settings;
    private Vector3d origin;
    private StepStats lastStats = StepStats.EMPTY;
    private boolean paused;
    private boolean closed;
    private long nextBodyId = 1L;
    private long nextConstraintId = 1L;
    private double currentStep;

    BulletPhysicsWorld(BulletPhysicsEngine engine, WorldSettings settings) {
        this.engine = engine;
        this.settings = settings;
        this.origin = settings.origin();
        this.clock = new FixedStepClock(settings.fixedTimeStep(), settings.maxSubSteps());
        this.space = new CrythstPhysicsSpace(this, broadphase(settings.broadphase()));
        this.space.setGravity(BulletConversions.toVector3f(settings.gravity(), new Vector3f()));
        this.space.setMaxSubSteps(0);
        this.space.getSolverInfo().setNumIterations(settings.solverIterations());
        this.space.setCcdWithStaticOnly(false);
        if (settings.deterministic()) {
            this.space.useDeterministicDispatch(true);
            this.space.setForceUpdateAllAabbs(true);
        }
    }

    private static PhysicsSpace.BroadphaseType broadphase(Broadphase broadphase) {
        return switch (broadphase) {
            case SIMPLE -> PhysicsSpace.BroadphaseType.SIMPLE;
            case AXIS_SWEEP_3 -> PhysicsSpace.BroadphaseType.AXIS_SWEEP_3;
            case DBVT -> PhysicsSpace.BroadphaseType.DBVT;
        };
    }

    PhysicsSpace bulletSpace() {
        return space;
    }

    Vector3d originRef() {
        return origin;
    }

    Vector3f gravityFor(double scale) {
        final Vector3d gravity = settings.gravity();
        return new Vector3f((float) (gravity.x * scale),
                (float) (gravity.y * scale), (float) (gravity.z * scale));
    }

    double defaultDynamicMass(PhysicsShape shape, PhysicsMaterial material) {
        final double mass = shape.volume() * material.density();
        if (mass <= 0.0) {
            throw new IllegalArgumentException(
                    "Cannot derive mass from a shape with no volume; set an explicit mass");
        }
        return mass;
    }

    @Override
    public PhysicsEngine engine() {
        return engine;
    }

    @Override
    public WorldSettings settings() {
        return settings;
    }

    @Override
    public String backendName() {
        return engine.name();
    }

    @Override
    public BodyHandle createBody(BodyDef definition) {
        ensureOpen();
        Objects.requireNonNull(definition, "definition");

        final CollisionShape collisionShape = BulletShapeFactory.create(definition.shape());
        final float mass = (float) definition.effectiveMass();
        final PhysicsRigidBody body = new PhysicsRigidBody(collisionShape, mass);
        final long id = nextBodyId++;
        final BulletBodyHandle handle = new BulletBodyHandle(this, id, body, definition.shape(),
                definition.material(), definition.type(), definition.gravityScale(),
                definition.gravityOverride(), definition.ccd(), definition.ccdRadius(),
                definition.userData());

        body.setUserObject(handle);
        body.setPhysicsLocation(BulletConversions.toLocal(definition.transform().position(), origin, new Vector3f()));
        body.setPhysicsRotation(BulletConversions.toQuaternion(definition.transform().rotation(), new Quaternion()));
        body.setFriction((float) definition.material().friction());
        body.setRestitution((float) definition.material().restitution());
        body.setRollingFriction((float) definition.material().rollingFriction());
        body.setSpinningFriction((float) definition.material().spinningFriction());
        body.setLinearVelocity(BulletConversions.toVector3f(definition.linearVelocity(), new Vector3f()));
        body.setAngularVelocity(BulletConversions.toVector3f(definition.angularVelocity(), new Vector3f()));
        body.setLinearFactor(BulletConversions.toVector3f(definition.linearFactor(), new Vector3f()));
        body.setAngularFactor(BulletConversions.toVector3f(definition.angularFactor(), new Vector3f()));
        body.setDamping((float) definition.linearDamping(), (float) definition.angularDamping());
        body.setEnableSleep(definition.allowSleep());
        body.setContactResponse(definition.contactResponse());
        if (definition.type() == BodyType.KINEMATIC) {
            body.setKinematic(true);
        }

        handle.setCcd(definition.ccd(), definition.ccdRadius());

        space.addCollisionObject(body);
        // Bullet creates the broadphase proxy on insertion and resets its group
        // masks to defaults, so both gravity and collision filtering must be
        // applied after insertion.
        body.setCollisionGroup(definition.filter().group());
        body.setCollideWithGroups(definition.filter().collidesWith());
        handle.applyGravity();
        bodies.put(id, handle);
        return handle;
    }

    @Override
    public void removeBody(BodyHandle body) {
        ensureOpen();
        if (!(body instanceof BulletBodyHandle handle)) {
            throw new IllegalArgumentException("Body does not belong to this backend: " + body);
        }
        final BulletBodyHandle removed = bodies.remove(handle.id());
        if (removed == null) {
            return;
        }

        final List<ConstraintHandle> affected = new ArrayList<>();
        for (BulletConstraintHandle constraint : constraints.values()) {
            if (constraint.involves(handle.bulletBody())) {
                affected.add(constraint);
            }
        }
        for (ConstraintHandle constraint : affected) {
            removeConstraint(constraint);
        }

        space.removeCollisionObject(handle.bulletBody());
    }

    @Override
    public BodyHandle body(long id) {
        return bodies.get(id);
    }

    @Override
    public Collection<BodyHandle> bodies() {
        return List.copyOf(bodies.values());
    }

    @Override
    public int bodyCount() {
        return bodies.size();
    }

    @Override
    public ConstraintHandle addConstraint(ConstraintDef definition) {
        ensureOpen();
        Objects.requireNonNull(definition, "definition");
        final BodyHandle rawA = switch (definition) {
            case FixedConstraintDef fixed -> fixed.bodyA();
            case HingeConstraintDef hinge -> hinge.bodyA();
            case SliderConstraintDef slider -> slider.bodyA();
            case PointConstraintDef point -> point.bodyA();
            case SixDofConstraintDef sixDof -> sixDof.bodyA();
            default -> throw new IllegalArgumentException("Unsupported constraint definition: " + definition);
        };
        final BodyHandle rawB = switch (definition) {
            case FixedConstraintDef fixed -> fixed.bodyB();
            case HingeConstraintDef hinge -> hinge.bodyB();
            case SliderConstraintDef slider -> slider.bodyB();
            case PointConstraintDef point -> point.bodyB();
            case SixDofConstraintDef sixDof -> sixDof.bodyB();
            default -> throw new IllegalArgumentException("Unsupported constraint definition: " + definition);
        };
        final BulletBodyHandle a = requireBody(rawA);
        final BulletBodyHandle b = rawB == null ? null : requireBody(rawB);
        if (b != null && b == a) {
            throw new IllegalArgumentException("A joint needs two distinct bodies");
        }

        final long id = nextConstraintId++;
        final PhysicsJoint joint = switch (definition.type()) {
            case FIXED -> createFixed((FixedConstraintDef) definition, a, b);
            case HINGE -> createHinge((HingeConstraintDef) definition, a, b);
            case SLIDER -> createSlider((SliderConstraintDef) definition, a, b);
            case POINT -> createPoint((PointConstraintDef) definition, a, b);
            case SIX_DOF -> createSixDof((SixDofConstraintDef) definition, a, b);
        };

        space.addJoint(joint);
        final BulletConstraintHandle handle = new BulletConstraintHandle(this, id,
                definition.type(), joint, a, b);
        constraints.put(id, handle);
        return handle;
    }

    private BulletBodyHandle requireBody(BodyHandle body) {
        if (!(body instanceof BulletBodyHandle handle) || !bodies.containsKey(handle.id())) {
            throw new IllegalArgumentException("Body does not belong to this world: " + body);
        }
        return handle;
    }

    private static Vector3f pivot(Transform3d frame) {
        return BulletConversions.toVector3f(frame.position(), new Vector3f());
    }

    private static Matrix3f basis(Transform3d frame) {
        return BulletConversions.toMatrix3f(frame.rotation(), new Matrix3f());
    }

    private SixDofJoint createFixed(FixedConstraintDef def, BulletBodyHandle a, BulletBodyHandle b) {
        final Vector3f pivotA = pivot(def.frameA());
        final Matrix3f basisA = basis(def.frameA());
        final SixDofJoint joint;
        if (b == null) {
            final Vector3f pivotWorld = BulletConversions.toLocal(def.frameB().position(), origin, new Vector3f());
            joint = new SixDofJoint(a.bulletBody(), pivotA, pivotWorld, basisA,
                    basis(def.frameB()), JointEnd.B);
        } else {
            joint = new SixDofJoint(a.bulletBody(), b.bulletBody(), pivotA, pivot(def.frameB()),
                    basisA, basis(def.frameB()), true);
        }
        final Vector3f zero = new Vector3f();
        joint.setLinearLowerLimit(zero);
        joint.setLinearUpperLimit(zero);
        joint.setAngularLowerLimit(zero);
        joint.setAngularUpperLimit(zero);
        return joint;
    }

    private HingeJoint createHinge(HingeConstraintDef def, BulletBodyHandle a, BulletBodyHandle b) {
        final Vector3f pivotA = pivot(Transform3d.of(def.pivotA()));
        final Vector3f axisA = BulletConversions.toVector3f(def.axisA(), new Vector3f());
        final HingeJoint joint;
        if (b == null) {
            final Vector3f pivotWorld = BulletConversions.toLocal(def.pivotB(), origin, new Vector3f());
            final Vector3f axisWorld = BulletConversions.toVector3f(def.axisB(), new Vector3f());
            joint = new HingeJoint(a.bulletBody(), pivotA, pivotWorld, axisA, axisWorld, JointEnd.B);
        } else {
            joint = new HingeJoint(a.bulletBody(), b.bulletBody(), pivotA,
                    BulletConversions.toVector3f(def.pivotB(), new Vector3f()),
                    axisA, BulletConversions.toVector3f(def.axisB(), new Vector3f()));
        }
        joint.setLimit((float) def.lowerLimit(), (float) def.upperLimit());
        if (def.enableMotor()) {
            joint.enableMotor(true, (float) def.motorTargetVelocity(), (float) def.maxMotorImpulse());
        }
        return joint;
    }

    private SliderJoint createSlider(SliderConstraintDef def, BulletBodyHandle a, BulletBodyHandle b) {
        final Vector3f pivotA = pivot(def.frameA());
        final Vector3f pivotB = pivot(def.frameB());
        final SliderJoint joint;
        if (b == null) {
            final Vector3f pivotWorld = BulletConversions.toLocal(def.frameB().position(), origin, new Vector3f());
            joint = new SliderJoint(a.bulletBody(), pivotA, pivotWorld, JointEnd.B);
        } else {
            joint = new SliderJoint(a.bulletBody(), b.bulletBody(), pivotA, pivotB,
                    basis(def.frameA()), basis(def.frameB()), true);
        }
        joint.setLowerLinLimit((float) def.lowerLinearLimit());
        joint.setUpperLinLimit((float) def.upperLinearLimit());
        joint.setLowerAngLimit((float) def.lowerAngularLimit());
        joint.setUpperAngLimit((float) def.upperAngularLimit());
        joint.setPoweredLinMotor(def.enableLinearMotor());
        joint.setTargetLinMotorVelocity((float) def.targetLinearVelocity());
        joint.setMaxLinMotorForce((float) def.maxLinearForce());
        return joint;
    }

    private Point2PointJoint createPoint(PointConstraintDef def, BulletBodyHandle a, BulletBodyHandle b) {
        final Vector3f pivotA = BulletConversions.toVector3f(def.pivotA(), new Vector3f());
        if (b == null) {
            final Vector3f pivotWorld = BulletConversions.toLocal(def.pivotB(), origin, new Vector3f());
            return new Point2PointJoint(a.bulletBody(), pivotA, pivotWorld);
        }
        return new Point2PointJoint(a.bulletBody(), b.bulletBody(), pivotA,
                BulletConversions.toVector3f(def.pivotB(), new Vector3f()));
    }

    private New6Dof createSixDof(SixDofConstraintDef def, BulletBodyHandle a, BulletBodyHandle b) {
        final Vector3f pivotA = pivot(def.frameA());
        final Matrix3f basisA = basis(def.frameA());
        final New6Dof joint;
        if (b == null) {
            final Vector3f pivotWorld = BulletConversions.toLocal(def.frameB().position(), origin, new Vector3f());
            // libbulletjme's single-ended constructor takes the constrained body
            // as the B end and the missing side as physics/world space.
            joint = new New6Dof(a.bulletBody(), pivotA, pivotWorld, basisA,
                    basis(def.frameB()), RotationOrder.XYZ);
        } else {
            joint = new New6Dof(a.bulletBody(), b.bulletBody(), pivotA, pivot(def.frameB()),
                    basisA, basis(def.frameB()), RotationOrder.XYZ);
        }
        joint.setFeedback(true);
        for (int dofIndex = 0; dofIndex < 6; dofIndex++) {
            configureDof(joint, dofIndex, def.axis(dofIndex));
        }
        return joint;
    }

    private static void configureDof(New6Dof joint, int dofIndex,
                                     io.mikaple.crythst.physics.constraint.DofAxisDef axis) {
        if (axis.freeAxis()) {
            // Bullet represents a free axis as lowerLimit > upperLimit.
            joint.set(MotorParam.LowerLimit, dofIndex, 1.0f);
            joint.set(MotorParam.UpperLimit, dofIndex, -1.0f);
        } else {
            joint.set(MotorParam.LowerLimit, dofIndex, (float) axis.lowerLimit());
            joint.set(MotorParam.UpperLimit, dofIndex, (float) axis.upperLimit());
        }
        joint.set(MotorParam.TargetVelocity, dofIndex, (float) axis.motorTargetVelocity());
        joint.set(MotorParam.MaxMotorForce, dofIndex, (float) axis.maxMotorForce());
        if (dofIndex >= 3) {
            joint.getRotationMotor(dofIndex - 3).setMotorEnabled(axis.enableMotor());
        } else {
            joint.getTranslationMotor().setMotorEnabled(dofIndex, axis.enableMotor());
        }
        joint.enableSpring(dofIndex, axis.enableSpring());
        if (axis.enableSpring()) {
            joint.setStiffness(dofIndex, (float) axis.stiffness(), true);
            joint.setDamping(dofIndex, (float) axis.damping(), true);
            joint.setEquilibriumPoint(dofIndex);
        }
    }

    @Override
    public void removeConstraint(ConstraintHandle constraint) {
        ensureOpen();
        if (!(constraint instanceof BulletConstraintHandle handle)) {
            throw new IllegalArgumentException("Constraint does not belong to this backend: " + constraint);
        }
        if (constraints.remove(handle.id()) != null) {
            handle.markRemoved();
            space.removeJoint(handle.bulletJoint());
        }
    }

    @Override
    public io.mikaple.crythst.physics.character.CharacterController createCharacter(
            io.mikaple.crythst.physics.character.CharacterDef definition) {
        ensureOpen();
        return new BulletCharacterController(this, definition);
    }

    @Override
    public Collection<ConstraintHandle> constraints() {
        return List.copyOf(constraints.values());
    }

    @Override
    public int constraintCount() {
        return constraints.size();
    }

    @Override
    public void step(double fixedTimeStep) {
        ensureOpen();
        if (paused || fixedTimeStep <= 0.0) {
            return;
        }

        final long started = System.nanoTime();
        currentStep = fixedTimeStep;
        for (StepListener listener : stepListeners) {
            listener.beforeStep(this, fixedTimeStep);
        }

        space.update((float) fixedTimeStep, 0, false,
                !contactListeners.isEmpty() || captureDebugContacts, false);

        if (!pendingContacts.isEmpty()) {
            final List<ContactEvent> contacts = List.copyOf(pendingContacts);
            pendingContacts.clear();
            if (captureDebugContacts) {
                lastContacts = contacts;
            }
            for (ContactEvent event : contacts) {
                for (ContactListener listener : contactListeners) {
                    try {
                        listener.onContact(event);
                    } catch (Throwable t) {
                        Crythst.LOGGER.error("Contact listener failed", t);
                    }
                }
            }
        } else if (captureDebugContacts) {
            lastContacts = List.of();
        }

        int awake = 0;
        for (BulletBodyHandle body : bodies.values()) {
            if (body.active()) {
                awake++;
            }
        }

        lastStats = new StepStats(
                bodies.size(),
                constraints.size(),
                awake,
                space.countManifolds(),
                1,
                fixedTimeStep,
                (System.nanoTime() - started) / 1_000_000.0
        );

        for (StepListener listener : stepListeners) {
            listener.afterStep(this, fixedTimeStep, lastStats);
        }
    }

    @Override
    public int advance(double realSeconds) {
        ensureOpen();
        if (paused) {
            return 0;
        }
        final int steps = clock.advance(realSeconds);
        for (int i = 0; i < steps; i++) {
            step(settings.fixedTimeStep());
        }
        return steps;
    }

    @Override
    public double interpolationAlpha() {
        return clock.alpha();
    }

    @Override
    public void setPaused(boolean value) {
        if (paused == value) {
            return;
        }
        paused = value;
        clock.reset();
    }

    @Override
    public boolean paused() {
        return paused;
    }

    @Override
    public void setGravity(Vector3dc gravity) {
        ensureOpen();
        settings = settings.withGravity(gravity);
        space.setGravity(BulletConversions.toVector3f(gravity, new Vector3f()));
        for (BulletBodyHandle body : bodies.values()) {
            if (body.type() == BodyType.DYNAMIC) {
                body.applyGravity();
                body.activate();
            }
        }
    }

    @Override
    public Vector3d gravity() {
        return settings.gravity();
    }

    @Override
    public Vector3d up() {
        final Vector3d gravity = settings.gravity();
        if (gravity.lengthSquared() < 1.0e-12) {
            return new Vector3d(0.0, 1.0, 0.0);
        }
        return gravity.negate().normalize();
    }

    @Override
    public double gravityMagnitude() {
        return settings.gravity().length();
    }

    @Override
    public List<BodyHandle> query(OrientedBox box, BodyFilter filter) {
        ensureOpen();
        final List<BodyHandle> result = new ArrayList<>();
        for (BulletBodyHandle body : bodies.values()) {
            if (filter.test(body) && body.obb().intersects(box)) {
                result.add(body);
            }
        }
        return result;
    }

    @Override
    public Vector3d origin() {
        return new Vector3d(origin);
    }

    @Override
    public void rebaseOrigin(Vector3dc newOrigin) {
        ensureOpen();
        final Vector3d target = new Vector3d(newOrigin);
        final Vector3d delta = new Vector3d(target).sub(origin);
        if (delta.lengthSquared() == 0.0) {
            return;
        }
        for (BulletBodyHandle body : bodies.values()) {
            body.shiftLocalOrigin(delta);
        }
        origin = target;
        settings = settings.withOrigin(target);
    }

    @Override
    public List<RayHit> raycast(RayQuery query) {
        ensureOpen();
        final Vector3f from = BulletConversions.toLocal(query.from(), origin, new Vector3f());
        final Vector3f to = BulletConversions.toLocal(query.to(), origin, new Vector3f());
        final List<PhysicsRayTestResult> results = space.rayTest(from, to);
        final List<RayHit> hits = new ArrayList<>(results.size());
        final Vector3d delta = new Vector3d(query.to()).sub(query.from());
        final double length = delta.length();
        final Vector3d direction = length > 0.0 ? delta.div(length) : new Vector3d();

        for (PhysicsRayTestResult result : results) {
            if (!(result.getCollisionObject().getUserObject() instanceof BulletBodyHandle body)) {
                continue;
            }
            if (!query.filter().test(body)) {
                continue;
            }
            final double fraction = result.getHitFraction();
            final Vector3f normal = result.getHitNormalLocal(new Vector3f());
            final Vector3d point = new Vector3d(query.from()).add(new Vector3d(direction).mul(length * fraction));
            hits.add(new RayHit(body, fraction, point,
                    new Vector3d(normal.x, normal.y, normal.z), length * fraction));
        }

        hits.sort((left, right) -> Double.compare(left.fraction(), right.fraction()));
        return hits;
    }

    @Override
    public List<SweepHit> sweep(SweepQuery query) {
        ensureOpen();
        final CollisionShape shape = BulletShapeFactory.create(query.shape());
        if (!(shape instanceof ConvexShape convex)) {
            throw new IllegalArgumentException("sweep requires a convex shape; got " + query.shape().type());
        }
        final com.jme3.math.Transform from = BulletConversions.toJmeTransform(query.from(), origin);
        final com.jme3.math.Transform to = BulletConversions.toJmeTransform(query.to(), origin);
        final List<PhysicsSweepTestResult> results = space.sweepTest(convex, from, to,
                new ArrayList<>(), (float) query.tolerance());

        final Vector3d delta = new Vector3d(query.to().position()).sub(query.from().position());
        final double length = delta.length();
        final List<SweepHit> hits = new ArrayList<>(results.size());
        for (PhysicsSweepTestResult result : results) {
            if (!(result.getCollisionObject().getUserObject() instanceof BulletBodyHandle body)) {
                continue;
            }
            if (!query.filter().test(body)) {
                continue;
            }
            final double fraction = result.getHitFraction();
            final Vector3f normal = result.getHitNormalLocal(new Vector3f());
            final Vector3d point = new Vector3d(query.from().position()).add(new Vector3d(delta).mul(fraction));
            hits.add(new SweepHit(body, fraction, new Vector3d(normal.x, normal.y, normal.z),
                    point, length * fraction));
        }
        hits.sort((left, right) -> Double.compare(left.fraction(), right.fraction()));
        return hits;
    }

    void collectContact(PhysicsCollisionObject objectA, PhysicsCollisionObject objectB, long manifoldId) {
        if (contactListeners.isEmpty() && !captureDebugContacts) {
            return;
        }
        final Object userA = objectA.getUserObject();
        final Object userB = objectB.getUserObject();
        if (!(userA instanceof BulletBodyHandle a) || !(userB instanceof BulletBodyHandle b)) {
            return;
        }
        if (a == b) {
            return;
        }

        // Defensive: a native callback can hand us a stale/foreign manifold id
        // (some Bullet builds use 0 for "no manifold"). countPoints is a cheap
        // int read; cap it before listPointIds allocates a long[] from native
        // memory.
        final int pointCount = PersistentManifolds.countPoints(manifoldId);
        if (pointCount <= 0 || pointCount > 64) {
            return;
        }
        final long[] pointIds = PersistentManifolds.listPointIds(manifoldId);
        if (pointIds.length == 0) {
            return;
        }
        final List<ContactPoint> points = new ArrayList<>(pointIds.length);
        for (long pointId : pointIds) {
            final Vector3f position = new Vector3f();
            final Vector3f normal = new Vector3f();
            ManifoldPoints.getPositionWorldOnB(pointId, position);
            ManifoldPoints.getNormalWorldOnB(pointId, normal);
            final Vector3d worldPosition = BulletConversions.toWorld(position, origin, new Vector3d());
            points.add(new ContactPoint(worldPosition,
                    new Vector3d(normal.x, normal.y, normal.z),
                    ManifoldPoints.getAppliedImpulse(pointId),
                    ManifoldPoints.getDistance1(pointId)));
        }
        pendingContacts.add(new ContactEvent(a, b, points, currentStep));
    }

    void notifyTeleport(BulletBodyHandle body) {
        // Hook for structure interpolation bookkeeping. Intentionally a no-op for
        // plain bodies; structures listen through their own body wrapper.
    }

    @Override
    public void addContactListener(ContactListener listener) {
        contactListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeContactListener(ContactListener listener) {
        contactListeners.remove(listener);
    }

    @Override
    public void addStepListener(StepListener listener) {
        stepListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeStepListener(StepListener listener) {
        stepListeners.remove(listener);
    }

    @Override
    public void setDebugContactCapture(boolean enabled) {
        if (captureDebugContacts == enabled) {
            return;
        }
        captureDebugContacts = enabled;
        if (!enabled) {
            lastContacts = List.of();
        }
    }

    @Override
    public StepStats lastStepStats() {
        return lastStats;
    }

    @Override
    public io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot debugSnapshot() {
        final List<io.mikaple.crythst.physics.debug.BodyDebugInfo> infos = new ArrayList<>(bodies.size());
        for (BulletBodyHandle body : bodies.values()) {
            infos.add(new io.mikaple.crythst.physics.debug.BodyDebugInfo(
                    body.id(), body.type(), body.transform(), body.obb(), body.aabb(), body.active(),
                    body.userData() == io.mikaple.crythst.physics.world.TerrainBody.INSTANCE));
        }
        return new io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot(
                engine.name(), engine.version(), origin, settings.gravity(), up(), lastStats, infos,
                lastContacts);
    }

    @Override
    public boolean closed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics world is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        bodies.clear();
        constraints.clear();
        pendingContacts.clear();
        space.destroy();
    }
}
