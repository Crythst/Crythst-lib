package io.mikaple.crythst.physics.world;

import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.collision.ContactListener;
import io.mikaple.crythst.physics.constraint.ConstraintDef;
import io.mikaple.crythst.physics.constraint.ConstraintHandle;
import io.mikaple.crythst.physics.query.RayHit;
import io.mikaple.crythst.physics.query.RayQuery;
import io.mikaple.crythst.physics.query.SweepHit;
import io.mikaple.crythst.physics.query.SweepQuery;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collection;
import java.util.List;

/**
 * A simulation universe. Implementations are not thread-safe: call all methods
 * from one thread (normally the Minecraft server thread).
 */
public interface PhysicsWorld extends AutoCloseable {
    PhysicsEngine engine();

    WorldSettings settings();

    String backendName();

    BodyHandle createBody(BodyDef definition);

    void removeBody(BodyHandle body);

    BodyHandle body(long id);

    Collection<BodyHandle> bodies();

    int bodyCount();

    ConstraintHandle addConstraint(ConstraintDef definition);

    void removeConstraint(ConstraintHandle constraint);

    /**
     * Creates a kinematic character capsule. Backends that cannot support
     * characters throw {@link UnsupportedOperationException}.
     */
    default io.mikaple.crythst.physics.character.CharacterController createCharacter(
            io.mikaple.crythst.physics.character.CharacterDef definition) {
        throw new UnsupportedOperationException(backendName() + " does not implement character controllers");
    }

    Collection<ConstraintHandle> constraints();

    int constraintCount();

    /**
     * Advances exactly one fixed step.
     */
    void step(double fixedTimeStep);

    /**
     * Consumes real elapsed time and runs up to {@code settings.maxSubSteps()}
     * fixed steps. This keeps simulation speed independent of frame rate.
     *
     * @return the number of fixed steps that ran
     */
    int advance(double realSeconds);

    /**
     * Fraction in [0, 1) between the previous and current fixed step. Render
     * code uses this to interpolate poses.
     */
    double interpolationAlpha();

    void setPaused(boolean paused);

    boolean paused();

    void setGravity(Vector3dc gravity);

    Vector3d gravity();

    /**
     * Unit vector opposite to gravity. Defaults to +Y for zero-gravity worlds.
     * Character controllers, "downhill" logic and placement helpers should use
     * this instead of assuming -Y.
     */
    Vector3d up();

    double gravityMagnitude();

    Vector3d origin();

    /**
     * Moves the internal single-precision anchor. Body world poses are
     * preserved. Call this when the simulation region drifts far from the
     * original origin (Minecraft coordinates can be millions of blocks away).
     */
    void rebaseOrigin(Vector3dc newOrigin);

    List<RayHit> raycast(RayQuery query);

    List<SweepHit> sweep(SweepQuery query);

    /**
     * Returns bodies whose oriented bounds overlap the given tilted box.
     *
     * @param box    world-space OBB
     * @param filter additional predicate
     */
    List<BodyHandle> query(io.mikaple.crythst.math.OrientedBox box,
                           io.mikaple.crythst.physics.query.BodyFilter filter);

    default List<BodyHandle> query(io.mikaple.crythst.math.OrientedBox box) {
        return query(box, io.mikaple.crythst.physics.query.BodyFilter.ALL);
    }

    void addContactListener(ContactListener listener);

    void removeContactListener(ContactListener listener);

    void addStepListener(StepListener listener);

    void removeStepListener(StepListener listener);

    /**
     * When enabled, contact manifolds are retained in the next debug snapshot.
     * Disabled by default to avoid per-step manifold extraction cost.
     */
    default void setDebugContactCapture(boolean enabled) {
    }

    StepStats lastStepStats();

    /**
     * Immutable debug view of the world. Safe to hand to a debug renderer or
     * command output.
     */
    default io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot debugSnapshot() {
        throw new UnsupportedOperationException(backendName() + " does not implement debug snapshots");
    }

    boolean closed();

    @Override
    void close();
}
