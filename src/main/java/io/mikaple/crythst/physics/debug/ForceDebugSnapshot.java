package io.mikaple.crythst.physics.debug;

import io.mikaple.crythst.api.physics.force.ForceTotals;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Immutable per-structure force data published for debug rendering.
 *
 * <p>The server thread builds this once per tick from
 * {@code StructurePhysicsRuntime#lastTotals} and
 * {@code StructureMassTracker}; the render thread only reads the immutable
 * vectors. Forces and torques are in newtons and {@code N·m}, in world space.</p>
 *
 * @param bodyId         physics body id
 * @param massKg         collider mass in kilograms, or {@code 0} while unknown
 * @param origin         structure pose origin in world metres
 * @param centerOfMass   world-space centre of mass in metres
 * @param totals         last fixed-substep force totals
 */
public record ForceDebugSnapshot(
        long bodyId,
        double massKg,
        Vector3d origin,
        Vector3d centerOfMass,
        ForceTotals totals
) {
    public ForceDebugSnapshot {
        massKg = Double.isFinite(massKg) && massKg > 0.0 ? massKg : 0.0;
        origin = origin == null ? new Vector3d() : new Vector3d(origin);
        centerOfMass = centerOfMass == null ? new Vector3d() : new Vector3d(centerOfMass);
        Objects.requireNonNull(totals, "totals");
    }

    /** @return a defensive copy of the structure origin */
    @Override
    public Vector3d origin() {
        return new Vector3d(origin);
    }

    /** @return a defensive copy of the world-space centre of mass */
    @Override
    public Vector3d centerOfMass() {
        return new Vector3d(centerOfMass);
    }
}
