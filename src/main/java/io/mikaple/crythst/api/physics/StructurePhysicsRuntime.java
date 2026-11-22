package io.mikaple.crythst.api.physics;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.block.BlockEntityStructureActor;
import io.mikaple.crythst.api.block.StructureActorDispatch;
import io.mikaple.crythst.api.block.StructurePhysicsContext;
import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.ForceTotals;
import io.mikaple.crythst.api.physics.force.QueuedForceGroup;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Server-side dispatcher that runs {@link BlockEntityStructureActor} callbacks
 * and applies their queued force groups for one level.
 *
 * <p>One runtime belongs to a {@code LevelPhysics}. Crythst invokes
 * {@link #runSubstep(PhysicsStructure, double)} from the world's
 * {@code StepListener#beforeStep} hook, so callbacks run once per fixed
 * substep, before Bullet integrates that substep, with the real substep
 * length. It is never called from the render thread or on a client level.</p>
 *
 * <p>Per-structure state (body handle, cached mass data, force-group
 * accumulators and the set of actor classes that already failed) is reused
 * across substeps. Actor lists themselves are cached by
 * {@link PhysicsStructure} and rebuilt by the auxiliary level when blocks
 * change; this runtime only walks that snapshot.</p>
 *
 * <p><strong>Units:</strong> force accumulators are in newtons and
 * {@code N·m}; {@link #runSubstep} converts them to impulses with the supplied
 * substep length. <strong>Threading:</strong> server thread only.</p>
 */
public final class StructurePhysicsRuntime {
    private final ServerLevel level;
    private final Map<PhysicsStructure, StructureState> states = new IdentityHashMap<>();

    /**
     * Creates a runtime for one host level.
     *
     * @param level real server level owning the structures; must not be
     *              {@code null}
     */
    public StructurePhysicsRuntime(ServerLevel level) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        this.level = level;
    }

    /**
     * Runs every actor for one structure and applies the accumulated forces.
     *
     * <p>If this structure has no cached actors, any stale per-structure state
     * is dropped and the call returns without allocating. Actor failures are
     * logged once per failing class for this structure; remaining actors and
     * the force application still run.</p>
     *
     * @param structure         structure to simulate; must not be {@code null}
     * @param timeStepSeconds   fixed substep length in seconds, strictly
     *                          positive
     * @throws NullPointerException     when {@code structure} is {@code null}
     * @throws IllegalArgumentException when {@code timeStepSeconds} is not
     *                                  finite and positive
     */
    public void runSubstep(PhysicsStructure structure, double timeStepSeconds) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (!(timeStepSeconds > 0.0) || !Double.isFinite(timeStepSeconds)) {
            throw new IllegalArgumentException("timeStepSeconds must be finite and positive: "
                    + timeStepSeconds);
        }
        if (structure.closed()) {
            forget(structure);
            return;
        }
        final List<BlockEntityStructureActor> actors = structure.blockEntityActors();
        if (actors.isEmpty()) {
            // Drop references to structures whose actor set was invalidated or
            // whose auxiliary level is unavailable.
            forget(structure);
            return;
        }

        final StructureState state = states.computeIfAbsent(structure, key -> new StructureState(key));
        state.prepare(timeStepSeconds);
        StructureActorDispatch.dispatch(actors, state.context, state.failureHandler);
        state.applyQueuedForces();
    }

    /**
     * Returns the force/torque totals recorded for a structure during the most
     * recent fixed substep.
     *
     * <p>The value is recorded before the per-group accumulators are converted
     * to impulses, so it is expressed in newtons and {@code N·m}. A structure
     * with no actor state reports {@link ForceTotals#empty(long)}. The returned
     * object is immutable.</p>
     *
     * @param structure structure to query; may be {@code null}
     * @return the last substep totals, never {@code null}
     */
    public ForceTotals lastTotals(@Nullable PhysicsStructure structure) {
        if (structure == null) {
            return ForceTotals.empty(0L);
        }
        final StructureState state = states.get(structure);
        return state == null ? ForceTotals.empty(structure.body().id()) : state.lastTotals;
    }

    /**
     * Drops cached state for a structure.
     *
     * <p>Called when a structure is removed or refreshed. Safe to call for an
     * unknown structure.</p>
     *
     * @param structure structure whose state should be discarded
     */
    public void forget(PhysicsStructure structure) {
        if (structure != null) {
            states.remove(structure);
        }
    }

    /**
     * Drops all cached per-structure state.
     */
    public void clear() {
        states.clear();
    }

    /**
     * Number of structures currently holding runtime state (normally only
     * structures with at least one actor).
     *
     * @return cached structure count
     */
    public int cachedStructureCount() {
        return states.size();
    }

    /**
     * Number of force-group accumulators currently allocated across all
     * structures. Diagnostic only.
     *
     * @return total allocated group accumulators
     */
    public int cachedForceGroupCount() {
        int count = 0;
        for (StructureState state : states.values()) {
            count += state.groups.size();
        }
        return count;
    }

    /**
     * Number of structures with a recorded force snapshot from the most recent
     * substep. Diagnostic only.
     */
    public int structuresWithForceTotals() {
        int count = 0;
        for (StructureState state : states.values()) {
            if (state.lastTotals.isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sum of the last-substep total-force magnitudes across all structures, in
     * newtons. Diagnostic only.
     */
    public double totalLastForceMagnitude() {
        double magnitude = 0.0;
        for (StructureState state : states.values()) {
            magnitude += state.lastTotals.forceMagnitude();
        }
        return magnitude;
    }

    private final class StructureState {
        private final PhysicsStructure structure;
        private final RigidBodyHandle handle;
        private final StructurePhysicsContext context = new LiveContext();
        private final Consumer<Throwable> failureHandler = this::reportFailure;
        private final Map<ForceGroup, QueuedForceGroup> groups = new HashMap<>();
        private final Set<FailureType> failedFailureTypes = new HashSet<>();
        private final Vector3d centerOfMass = new Vector3d();

        private Transform3d pose = Transform3d.IDENTITY;
        private double timeStepSeconds;
        private MassData massData;
        private VoxelColliderData massDataSource;
        private ForceTotals lastTotals = ForceTotals.empty(0L);

        private StructureState(PhysicsStructure structure) {
            this.structure = structure;
            this.handle = RigidBodyHandle.of(structure);
        }

        private void prepare(double timeStepSeconds) {
            this.timeStepSeconds = timeStepSeconds;
            this.pose = structure.pose();
            this.centerOfMass.set(handle.getCenterOfMass());
            for (QueuedForceGroup group : groups.values()) {
                group.reset(centerOfMass);
            }
        }

        private void applyQueuedForces() {
            if (groups.isEmpty()) {
                lastTotals = ForceTotals.empty(structure.body().id());
                return;
            }
            final Vector3d totalForce = new Vector3d();
            final Vector3d totalTorque = new Vector3d();
            final Map<ForceGroup, ForceTotals.GroupForce> perGroup = new LinkedHashMap<>();
            for (QueuedForceGroup group : groups.values()) {
                final Vector3d force = group.accumulatedForce(new Vector3d());
                final Vector3d torque = group.accumulatedTorque(new Vector3d());
                totalForce.add(force);
                totalTorque.add(torque);
                if (force.lengthSquared() != 0.0 || torque.lengthSquared() != 0.0) {
                    perGroup.put(group.group(), new ForceTotals.GroupForce(
                            group.group(), force, torque));
                }
                group.applyTo(handle, timeStepSeconds);
            }
            lastTotals = new ForceTotals(structure.body().id(), timeStepSeconds,
                    totalForce, totalTorque, perGroup);
        }

        private MassData massData() {
            final VoxelColliderData collider = structure.collider();
            if (massData == null || massDataSource != collider) {
                massData = MassData.forStructure(structure);
                massDataSource = collider;
            }
            return massData;
        }

        private QueuedForceGroup forceGroup(ForceGroup group) {
            if (group == null) {
                throw new NullPointerException("group");
            }
            QueuedForceGroup queued = groups.get(group);
            if (queued == null) {
                queued = new QueuedForceGroup(group);
                queued.reset(centerOfMass);
                groups.put(group, queued);
            }
            return queued;
        }

        private void reportFailure(Throwable failure) {
            // The dispatch helper does not tell us which actor failed, so log
            // keyed on the throwing class while keeping the once-per-structure
            // guard below. Repeated failures from the same class are folded.
            final String failureType = failure.getClass().getName();
            if (failedFailureTypes.add(new FailureType(failureType))) {
                Crythst.LOGGER.warn("Structure physics actor failed for body #{} ({}); "
                                + "its contribution for this substep may be incomplete",
                        structure.body().id(), failureType, failure);
            }
        }

        private final class LiveContext implements StructurePhysicsContext {
            @Override
            public PhysicsStructure structure() {
                return structure;
            }

            @Override
            public ServerLevel level() {
                return level;
            }

            @Override
            public Transform3d structurePose() {
                return pose;
            }

            @Override
            public RigidBodyHandle body() {
                return handle;
            }

            @Override
            public MassData massData() {
                return StructureState.this.massData();
            }

            @Override
            public long bodyId() {
                return structure.body().id();
            }

            @Override
            public Vector3dc centerOfMassWorld() {
                return centerOfMass;
            }

            @Override
            public QueuedForceGroup forceGroup(ForceGroup group) {
                return StructureState.this.forceGroup(group);
            }
        }
    }

    /**
     * Actor identities are not available to {@code StructureActorDispatch}, so
     * failures are folded by exception type. This keeps the log to one line per
     * failure type per structure without retaining actor instances.
     */
    private record FailureType(String failureType) {
    }
}
