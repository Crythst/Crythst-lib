package io.mikaple.crythst.physics.world;

import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.shape.Shapes;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcdMotionGuardTest {
    @Test
    void fastBodyGetsCcdAndSlowBodyDoesNot() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final BodyHandle slow = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(0, 0, 0))
                    .build());
            final BodyHandle fast = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .position(new Vector3d(10, 0, 0))
                    .linearVelocity(new Vector3d(100, 0, 0))
                    .build());

            final int changed = CcdMotionGuard.update(world, 1.0 / 20.0);

            assertTrue(changed >= 1);
            assertTrue(fast.ccd(), "a body travelling 5 m/tick must get CCD");
            assertFalse(slow.ccd(), "a resting body must stay on the cheap path");
        }
    }
}
