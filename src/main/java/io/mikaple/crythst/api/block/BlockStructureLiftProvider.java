package io.mikaple.crythst.api.block;

import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.QueuedForceGroup;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Block-entity hook for lift/thrust forces on the containing structure.
 *
 * <p>This is a convenience specialization of {@link BlockEntityStructureActor}.
 * Discovery, lifecycle and failure isolation are therefore identical: implement
 * this interface on a block entity that is copied into the auxiliary ticking
 * level, and its default {@link #structurePhysicsTick(StructurePhysicsContext)}
 * queues {@link #structureLift(StructurePhysicsContext)} in a force group once
 * per fixed substep. Forces are in newtons and are converted to impulses by the
 * actor runtime, so implementations must not multiply by the time step.</p>
 *
 * <p>By default the force is applied at the structure's centre of mass (no
 * torque). Override {@link #applicationPoint(StructurePhysicsContext)} to apply
 * it at an anchor; map structure-local coordinates through
 * {@link StructurePhysicsContext#structurePose()} first. Override
 * {@link #forceGroup()} to display lift in its own debug group.</p>
 *
 * <p><strong>Threading:</strong> callbacks run on the server thread.
 * <strong>Lifecycle:</strong> the block entity must be present when the
 * structure's actor list is discovered (creation, refresh or persistence
 * restore); call the refresh path after changing an actor implementation.</p>
 */
public interface BlockStructureLiftProvider extends BlockEntityStructureActor {
    /** Default force group used by {@link #forceGroup()}. */
    ForceGroup DEFAULT_LIFT_GROUP = new ForceGroup(
            Component.literal("structure_lift"), null, 0x66CCFF, false);

    /**
     * Lift/thrust force for one substep, in newtons, in world space.
     *
     * @param context ephemeral structure context
     * @return force in newtons; may be zero but must not be {@code null}
     */
    Vector3dc structureLift(StructurePhysicsContext context);

    /**
     * World-space application point for the lift force.
     *
     * <p>The default is the centre of mass, which produces no torque.</p>
     *
     * @param context ephemeral structure context
     * @return application point in world metres
     */
    default Vector3dc applicationPoint(StructurePhysicsContext context) {
        return context.centerOfMassWorld();
    }

    /** Force group used for debug/display and accumulator lookup. */
    default ForceGroup forceGroup() {
        return DEFAULT_LIFT_GROUP;
    }

    @Override
    default void structurePhysicsTick(StructurePhysicsContext context) {
        final Vector3dc lift = structureLift(context);
        if (lift == null || lift.lengthSquared() == 0.0) {
            return;
        }
        final QueuedForceGroup group = context.forceGroup(forceGroup());
        group.applyPointForce(applicationPoint(context), lift);
    }

    /**
     * Convenience: projects a structure-local point to world space using the
     * current substep pose.
     *
     * @param context ephemeral structure context
     * @param local   structure-local metres
     * @return world-space metres
     */
    static Vector3d localToWorld(StructurePhysicsContext context, Vector3dc local) {
        if (context == null) {
            throw new NullPointerException("context");
        }
        if (local == null) {
            throw new NullPointerException("local");
        }
        return context.structurePose().transformPoint(local);
    }
}
