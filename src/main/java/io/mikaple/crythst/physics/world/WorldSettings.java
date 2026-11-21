package io.mikaple.crythst.physics.world;

import io.mikaple.crythst.util.Validate;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Immutable world configuration.
 */
public record WorldSettings(
        Vector3d gravity,
        double fixedTimeStep,
        int maxSubSteps,
        int solverIterations,
        Broadphase broadphase,
        Vector3d origin,
        boolean allowSleep,
        boolean deterministic,
        double contactTolerance
) {
    public static final int DEFAULT_SOLVER_ITERATIONS = 10;

    public WorldSettings {
        gravity = new Vector3d(Validate.notNull(gravity, "gravity"));
        Validate.finite(gravity.x, "gravity.x");
        Validate.finite(gravity.y, "gravity.y");
        Validate.finite(gravity.z, "gravity.z");
        fixedTimeStep = Validate.positive(fixedTimeStep, "fixedTimeStep");
        Validate.atLeast(maxSubSteps, 1, "maxSubSteps");
        Validate.atLeast(solverIterations, 1, "solverIterations");
        broadphase = broadphase == null ? Broadphase.DBVT : broadphase;
        origin = new Vector3d(Validate.notNull(origin, "origin"));
        Validate.nonNegative(contactTolerance, "contactTolerance");
    }

    public static WorldSettings defaults(Vector3dc origin) {
        return new WorldSettings(
                new Vector3d(0.0, -9.81, 0.0),
                1.0 / 60.0,
                4,
                DEFAULT_SOLVER_ITERATIONS,
                Broadphase.DBVT,
                new Vector3d(origin),
                true,
                true,
                0.04
        );
    }

    public static WorldSettings defaults() {
        return defaults(new Vector3d());
    }

    @Override
    public Vector3d gravity() {
        return new Vector3d(gravity);
    }

    @Override
    public Vector3d origin() {
        return new Vector3d(origin);
    }

    /**
     * Builds settings from a gravity direction and magnitude. Any direction is
     * allowed; {@code up()} is derived as the opposite direction.
     */
    public static WorldSettings withGravity(Vector3dc direction, double magnitude) {
        Validate.nonNegative(magnitude, "magnitude");
        final Vector3d gravity = magnitude == 0.0
                ? new Vector3d()
                : new Vector3d(Validate.notNull(direction, "direction")).normalize(magnitude);
        return defaults().withGravity(gravity);
    }

    /**
     * Unit vector opposite to gravity, or +Y in a zero-gravity world.
     */
    public Vector3d up() {
        if (gravity.lengthSquared() < 1.0e-12) {
            return new Vector3d(0.0, 1.0, 0.0);
        }
        return new Vector3d(gravity).negate().normalize();
    }

    public WorldSettings withGravity(Vector3dc newGravity) {
        return new WorldSettings(new Vector3d(newGravity), fixedTimeStep, maxSubSteps, solverIterations,
                broadphase, origin, allowSleep, deterministic, contactTolerance);
    }

    public WorldSettings withFixedTimeStep(double newStep) {
        return new WorldSettings(gravity, newStep, maxSubSteps, solverIterations,
                broadphase, origin, allowSleep, deterministic, contactTolerance);
    }

    public WorldSettings withMaxSubSteps(int newMax) {
        return new WorldSettings(gravity, fixedTimeStep, newMax, solverIterations,
                broadphase, origin, allowSleep, deterministic, contactTolerance);
    }

    public WorldSettings withSolverIterations(int iterations) {
        return new WorldSettings(gravity, fixedTimeStep, maxSubSteps, iterations,
                broadphase, origin, allowSleep, deterministic, contactTolerance);
    }

    public WorldSettings withBroadphase(Broadphase newBroadphase) {
        return new WorldSettings(gravity, fixedTimeStep, maxSubSteps, solverIterations,
                newBroadphase, origin, allowSleep, deterministic, contactTolerance);
    }

    public WorldSettings withOrigin(Vector3dc newOrigin) {
        return new WorldSettings(gravity, fixedTimeStep, maxSubSteps, solverIterations,
                broadphase, new Vector3d(newOrigin), allowSleep, deterministic, contactTolerance);
    }

    public WorldSettings withSleep(boolean enabled) {
        return new WorldSettings(gravity, fixedTimeStep, maxSubSteps, solverIterations,
                broadphase, origin, enabled, deterministic, contactTolerance);
    }

    public WorldSettings withDeterministic(boolean enabled) {
        return new WorldSettings(gravity, fixedTimeStep, maxSubSteps, solverIterations,
                broadphase, origin, allowSleep, enabled, contactTolerance);
    }
}
