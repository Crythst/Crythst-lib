package io.mikaple.crythst.physics.bullet;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.math.Vector3f;

/**
 * PhysicsSpace subclass used to intercept contact manifolds.
 */
final class CrythstPhysicsSpace extends PhysicsSpace {
    private final BulletPhysicsWorld world;

    CrythstPhysicsSpace(BulletPhysicsWorld world, BroadphaseType broadphase) {
        super(new Vector3f(-100_000.0f, -100_000.0f, -100_000.0f),
                new Vector3f(100_000.0f, 100_000.0f, 100_000.0f), broadphase);
        this.world = world;
    }

    @Override
    public void onContactProcessed(PhysicsCollisionObject objectA, PhysicsCollisionObject objectB, long manifoldId) {
        world.collectContact(objectA, objectB, manifoldId);
    }
}
