package io.mikaple.crythst.physics.bullet;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.collision.shapes.CompoundCollisionShape;
import com.jme3.bullet.collision.shapes.CylinderCollisionShape;
import com.jme3.bullet.collision.shapes.HullCollisionShape;
import com.jme3.bullet.collision.shapes.PlaneCollisionShape;
import com.jme3.bullet.collision.shapes.SphereCollisionShape;
import com.jme3.math.Matrix3f;
import com.jme3.math.Plane;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import io.mikaple.crythst.physics.shape.BoxShape;
import io.mikaple.crythst.physics.shape.CapsuleShape;
import io.mikaple.crythst.physics.shape.CompoundShape;
import io.mikaple.crythst.physics.shape.ConvexHullShape;
import io.mikaple.crythst.physics.shape.CylinderShape;
import io.mikaple.crythst.physics.shape.PhysicsShape;
import io.mikaple.crythst.physics.shape.PlaneShape;
import io.mikaple.crythst.physics.shape.SphereShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates Crythst shapes into libbulletjme collision shapes.
 */
public final class BulletShapeFactory {
    private BulletShapeFactory() {
    }

    public static CollisionShape create(PhysicsShape shape) {
        if (shape instanceof BoxShape box) {
            final Vector3d half = box.halfExtents();
            return new BoxCollisionShape((float) half.x, (float) half.y, (float) half.z);
        }
        if (shape instanceof SphereShape sphere) {
            return new SphereCollisionShape((float) sphere.radius());
        }
        if (shape instanceof CapsuleShape capsule) {
            return new CapsuleCollisionShape((float) capsule.radius(),
                    (float) capsule.cylinderHeight(), capsule.axis().bulletIndex());
        }
        if (shape instanceof CylinderShape cylinder) {
            return new CylinderCollisionShape((float) cylinder.radius(),
                    (float) cylinder.height(), cylinder.axis().bulletIndex());
        }
        if (shape instanceof ConvexHullShape hull) {
            final List<Vector3f> points = new ArrayList<>();
            for (Vector3dc point : hull.points()) {
                points.add(new Vector3f((float) point.x(), (float) point.y(), (float) point.z()));
            }
            return new HullCollisionShape(points);
        }
        if (shape instanceof CompoundShape compound) {
            final CompoundCollisionShape result = new CompoundCollisionShape(compound.children().size());
            for (CompoundShape.Child child : compound.children()) {
                final CollisionShape childShape = create(child.shape(), child.transform().rotation());
                final Vector3f offset = new Vector3f(
                        (float) child.transform().position().x(),
                        (float) child.transform().position().y(),
                        (float) child.transform().position().z()
                );
                result.addChildShape(childShape, offset);
            }
            return result;
        }
        if (shape instanceof PlaneShape plane) {
            final Vector3f normal = new Vector3f((float) plane.normal().x(),
                    (float) plane.normal().y(), (float) plane.normal().z());
            return new PlaneCollisionShape(new Plane(normal, (float) plane.constant()));
        }
        throw new IllegalArgumentException("Unsupported shape type: " + shape.getClass().getName());
    }

    private static CollisionShape create(PhysicsShape shape, org.joml.Quaterniondc rotation) {
        final CollisionShape result = create(shape);
        final Matrix3f matrix = BulletConversions.toMatrix3f(rotation, new Matrix3f());
        if (!matrix.isIdentity()) {
            // Child collision shapes cannot be rotated in isolation; wrap the
            // child in a one-child compound so the rotation is preserved.
            final CompoundCollisionShape wrapper = new CompoundCollisionShape(1);
            wrapper.addChildShape(result, new Vector3f(), matrix);
            return wrapper;
        }
        return result;
    }
}
