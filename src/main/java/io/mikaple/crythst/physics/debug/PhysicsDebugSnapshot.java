package io.mikaple.crythst.physics.debug;

import io.mikaple.crythst.physics.collision.ContactEvent;
import io.mikaple.crythst.physics.world.StepStats;
import org.joml.Vector3d;

import java.util.List;

/**
 * Snapshot suitable for debug overlays, commands and crash reports.
 */
public record PhysicsDebugSnapshot(
        String backend,
        String backendVersion,
        Vector3d origin,
        Vector3d gravity,
        Vector3d up,
        StepStats stats,
        List<BodyDebugInfo> bodies,
        List<ContactEvent> contacts
) {
    public PhysicsDebugSnapshot {
        origin = new Vector3d(origin);
        gravity = new Vector3d(gravity);
        up = new Vector3d(up);
        bodies = List.copyOf(bodies);
        contacts = List.copyOf(contacts);
    }

    @Override
    public Vector3d origin() {
        return new Vector3d(origin);
    }

    @Override
    public Vector3d gravity() {
        return new Vector3d(gravity);
    }

    @Override
    public Vector3d up() {
        return new Vector3d(up);
    }
}
