package io.mikaple.crythst.physics.bullet;

import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.objects.PhysicsCharacter;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.character.CharacterController;
import io.mikaple.crythst.physics.character.CharacterDef;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * libbulletjme character controller bridge.
 */
public final class BulletCharacterController implements CharacterController {
    private final BulletPhysicsWorld world;
    private final PhysicsCharacter character;
    private boolean closed;

    BulletCharacterController(BulletPhysicsWorld world, CharacterDef def) {
        this.world = world;
        final double cylinderHeight = Math.max(0.0, def.height() - def.radius() * 2.0);
        final CapsuleCollisionShape shape = new CapsuleCollisionShape(
                (float) def.radius(), (float) cylinderHeight, 1);

        this.character = new PhysicsCharacter(shape, (float) def.stepHeight());
        character.setUp(def.up() != null
                ? BulletConversions.toVector3f(def.up(), new Vector3f())
                : BulletConversions.toVector3f(world.up(), new Vector3f()));
        character.setJumpSpeed((float) def.jumpSpeed());
        character.setFallSpeed((float) def.fallSpeed());
        character.setMaxSlope((float) def.maxSlope());
        character.setLinearDamping((float) def.linearDamping());
        character.setAngularDamping((float) def.angularDamping());
        character.setPhysicsLocation(BulletConversions.toLocal(def.pose().position(),
                world.originRef(), new Vector3f()));
        world.bulletSpace().addCollisionObject(character);
        // As with rigid bodies, gravity must be applied after the character
        // enters the space or Bullet replaces it.
        character.setGravity(BulletConversions.toVector3f(
                new Vector3d(world.gravity()).mul(def.gravityScale()), new Vector3f()));
    }

    public PhysicsCharacter bulletCharacter() {
        return character;
    }

    @Override
    public Transform3d position() {
        final Vector3f location = character.getPhysicsLocation(new Vector3f());
        final Quaternion rotation = character.getPhysicsRotation(new Quaternion());
        return BulletConversions.toTransform3d(location, rotation, world.originRef());
    }

    @Override
    public void teleport(Transform3d transform) {
        character.setPhysicsLocation(BulletConversions.toLocal(transform.position(),
                world.originRef(), new Vector3f()));
        character.setLinearVelocity(new Vector3f());
    }

    @Override
    public void setWalkDirection(Vector3dc direction) {
        character.setWalkDirection(BulletConversions.toVector3f(direction, new Vector3f()));
    }

    @Override
    public Vector3d walkDirection() {
        return BulletConversions.toVector3d(character.getWalkDirection(new Vector3f()), new Vector3d());
    }

    @Override
    public void setLinearVelocity(Vector3dc velocity) {
        character.setLinearVelocity(BulletConversions.toVector3f(velocity, new Vector3f()));
    }

    @Override
    public Vector3d linearVelocity() {
        return BulletConversions.toVector3d(character.getLinearVelocity(new Vector3f()), new Vector3d());
    }

    @Override
    public void jump() {
        character.jump();
    }

    @Override
    public boolean onGround() {
        return character.onGround();
    }

    @Override
    public void setUp(Vector3dc up) {
        character.setUp(BulletConversions.toVector3f(up, new Vector3f()));
    }

    @Override
    public void setStepHeight(double height) {
        character.setStepHeight((float) height);
    }

    @Override
    public void setMaxSlope(double radians) {
        character.setMaxSlope((float) radians);
    }

    @Override
    public void setGravity(Vector3dc gravity) {
        character.setGravity(BulletConversions.toVector3f(gravity, new Vector3f()));
    }

    @Override
    public void setDamping(double linear, double angular) {
        character.setLinearDamping((float) linear);
        character.setAngularDamping((float) angular);
    }

    @Override
    public void warp(Vector3dc position) {
        character.warp(BulletConversions.toLocal(position, world.originRef(), new Vector3f()));
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        world.bulletSpace().removeCollisionObject(character);
    }
}
