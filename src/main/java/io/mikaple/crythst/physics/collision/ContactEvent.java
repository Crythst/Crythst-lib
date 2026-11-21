package io.mikaple.crythst.physics.collision;

import io.mikaple.crythst.physics.body.BodyHandle;

import java.util.List;

/**
 * A pair of touching bodies and the contact points of the manifold.
 */
public record ContactEvent(BodyHandle bodyA, BodyHandle bodyB, List<ContactPoint> points, double timeStep) {
    public ContactEvent {
        points = List.copyOf(points);
    }

    public double totalImpulse() {
        double total = 0.0;
        for (ContactPoint point : points) {
            total += point.impulse();
        }
        return total;
    }
}
