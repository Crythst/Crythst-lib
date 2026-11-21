package io.mikaple.crythst.physics.bullet;

import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.query.RayHit;
import io.mikaple.crythst.physics.query.RayQuery;
import io.mikaple.crythst.physics.shape.PlaneShape;
import io.mikaple.crythst.physics.shape.Shapes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulletPhysicsSmokeTest {
    @Test
    void boxFallsAndRestsOnPlane() {
        final PhysicsEngine engine = BulletPhysicsEngine.create();
        assertNotNull(engine.version());

        try (PhysicsWorld world = engine.createWorld(WorldSettings.defaults())) {
            world.createBody(BodyDef.staticBody(PlaneShape.horizontal(0.0)).build());
            final BodyHandle box = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(0.0, 5.0, 0.0))
                    .build());

            for (int i = 0; i < 300; i++) {
                world.step(1.0 / 60.0);
            }

            final Vector3d position = box.transform().position();
            assertTrue(position.y < 0.8, "box should have fallen, y=" + position.y);
            assertTrue(position.y > 0.3, "box should rest above the plane, y=" + position.y);
            assertTrue(box.obb().toAabb().size().y > 0.9);
            assertEquals(2, world.bodyCount());
        }
    }

    @Test
    void arbitraryGravityDirectionMovesBodySideways() {
        final PhysicsEngine engine = BulletPhysicsEngine.create();
        final WorldSettings settings = WorldSettings.defaults()
                .withGravity(new Vector3d(9.81, 0.0, 0.0));

        try (PhysicsWorld world = engine.createWorld(settings)) {
            assertEquals(-1.0, world.up().x, 1.0e-9);
            final BodyHandle box = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(0.0, 20.0, 0.0))
                    .build());
            for (int i = 0; i < 60; i++) {
                world.step(1.0 / 60.0);
            }
            final Vector3d position = box.transform().position();
            assertTrue(position.x > 3.0, "box should accelerate along +X, x=" + position.x);
            assertEquals(20.0, position.y, 0.3);
        }
    }

    @Test
    void perBodyGravityOverrideWins() {
        final PhysicsEngine engine = BulletPhysicsEngine.create();
        try (PhysicsWorld world = engine.createWorld(WorldSettings.defaults())) {
            final BodyHandle box = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(0.0, 20.0, 0.0))
                    .gravityOverride(new Vector3d(0.0, 0.0, 9.81))
                    .build());
            for (int i = 0; i < 60; i++) {
                world.step(1.0 / 60.0);
            }
            final Vector3d position = box.transform().position();
            assertTrue(position.z > 3.0, "gravity override should pull along +Z, z=" + position.z);
            assertEquals(20.0, position.y, 0.3);
        }
    }

    @Test
    void raycastAndTiltedObbQuery() {
        final PhysicsEngine engine = BulletPhysicsEngine.create();
        try (PhysicsWorld world = engine.createWorld(WorldSettings.defaults())) {
            final BodyHandle box = world.createBody(BodyDef.dynamic(Shapes.box(2.0, 1.0, 1.0))
                    .position(new Vector3d(0.0, 10.0, 0.0))
                    .gravityScale(0.0)
                    .build());
            box.setTransform(io.mikaple.crythst.math.Transform3d.of(
                    new Vector3d(0.0, 10.0, 0.0), new Quaterniond().rotateZ(Math.PI / 4.0)));

            final List<RayHit> hits = world.raycast(RayQuery.fromDirection(
                    new Vector3d(0.0, 20.0, 0.0), new Vector3d(0.0, -1.0, 0.0), 30.0));
            assertFalse(hits.isEmpty());
            assertEquals(box.id(), hits.get(0).body().id());

            final OrientedBox query = new OrientedBox(new Vector3d(0.0, 10.0, 0.0),
                    new Vector3d(1.0, 1.0, 1.0), new Quaterniond());
            assertEquals(1, world.query(query).size());
            assertEquals(1, world.debugSnapshot().bodies().size());
        }
    }
}
