package io.mikaple.crythst.api.block;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Propeller-style lift hook.
 *
 * <p>A propeller reports a scalar thrust and a structure-local thrust axis.
 * The default lift implementation rotates the axis through the current
 * structure pose and returns {@code axis * thrust} in newtons. Positive thrust
 * pushes along the axis; negative thrust reverses it. This is deliberately
 * simpler than a full aerodynamic model: implementations can account for
 * airflow, throttle, damage or neighbouring blocks in
 * {@link #thrustNewtons(StructurePhysicsContext)}.</p>
 *
 * <p>Like every {@link BlockStructureLiftProvider}, the block entity must be
 * discoverable in the auxiliary ticking level. Callbacks run once per fixed
 * physics substep on the server thread.</p>
 */
public interface BlockStructurePropeller extends BlockStructureLiftProvider {
    /**
     * Thrust for the current substep in newtons.
     *
     * @param context ephemeral structure context
     * @return thrust in newtons; may be negative or zero
     */
    double thrustNewtons(StructurePhysicsContext context);

    /**
     * Thrust direction in structure-local space. It need not be normalized;
     * the returned vector is normalized before use.
     *
     * @return a non-zero structure-local direction
     */
    Vector3dc thrustAxisLocal();

    @Override
    default Vector3dc structureLift(StructurePhysicsContext context) {
        final Vector3d axis = new Vector3d(thrustAxisLocal());
        if (axis.lengthSquared() < 1.0e-20) {
            return new Vector3d();
        }
        axis.normalize();
        return context.structurePose().transformDirection(axis)
                .mul(thrustNewtons(context));
    }
}
