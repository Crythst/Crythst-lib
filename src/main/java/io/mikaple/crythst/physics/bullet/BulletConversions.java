package io.mikaple.crythst.physics.bullet;

import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Conversions between Crythst (JOML double precision, world space) and
 * libbulletjme (jME single precision, local space).
 */
public final class BulletConversions {
    private BulletConversions() {
    }

    public static Vector3f toLocal(Vector3dc world, Vector3dc origin, Vector3f dest) {
        return dest.set(
                (float) (world.x() - origin.x()),
                (float) (world.y() - origin.y()),
                (float) (world.z() - origin.z())
        );
    }

    public static Vector3f toVector3f(Vector3dc value, Vector3f dest) {
        return dest.set((float) value.x(), (float) value.y(), (float) value.z());
    }

    public static Vector3d toVector3d(Vector3f value, Vector3d dest) {
        return dest.set(value.x, value.y, value.z);
    }

    public static Vector3d toWorld(Vector3f local, Vector3dc origin, Vector3d dest) {
        return dest.set(
                origin.x() + local.x,
                origin.y() + local.y,
                origin.z() + local.z
        );
    }

    public static Quaternion toQuaternion(Quaterniondc rotation, Quaternion dest) {
        return dest.set((float) rotation.x(), (float) rotation.y(),
                (float) rotation.z(), (float) rotation.w());
    }

    public static Quaterniond toQuaterniond(Quaternion rotation, Quaterniond dest) {
        return dest.set(rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW());
    }

    public static Matrix3f toMatrix3f(Quaterniondc rotation, Matrix3f dest) {
        final Quaternion q = new Quaternion(
                (float) rotation.x(), (float) rotation.y(),
                (float) rotation.z(), (float) rotation.w());
        return q.toRotationMatrix(dest);
    }

    public static Transform3d toTransform3d(Vector3f location, Quaternion rotation, Vector3dc origin) {
        final Vector3d position = toWorld(location, origin, new Vector3d());
        final Quaterniond quaternion = toQuaterniond(rotation, new Quaterniond());
        return Transform3d.of(position, quaternion);
    }

    public static com.jme3.math.Transform toJmeTransform(Transform3d transform, Vector3dc origin) {
        final com.jme3.math.Transform result = new com.jme3.math.Transform();
        result.setTranslation(toLocal(transform.position(), origin, new Vector3f()));
        result.setRotation(toQuaternion(transform.rotation(), new Quaternion()));
        return result;
    }
}
