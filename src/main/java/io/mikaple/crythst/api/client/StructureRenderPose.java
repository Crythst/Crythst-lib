package io.mikaple.crythst.api.client;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Client-side render pose of one structure.
 *
 * <p>The pose pair is the same double buffer that a renderer would normally
 * receive from the timestamped render timeline. {@link #pose()} is the sample
 * at the requested frame time, while {@link #previousPose()} and
 * {@link #currentPose()} are the two endpoints that surround it. A caller that
 * already has its own partial-tick interpolation can use the endpoints
 * directly.</p>
 *
 * <p>The pose is the <em>structure</em> pose, not the centre-of-mass body
 * pose. Local voxel coordinates are metres and are mapped through the pose by
 * {@code pose.transformPoint((voxel + 0.5) * blockScale)}.</p>
 *
 * @param bodyId          physics body id, or the stable network snapshot id
 * @param pose            interpolated structure pose at the requested frame
 * @param previousPose    older endpoint of the render timeline
 * @param currentPose     newer endpoint of the render timeline
 * @param intervalSeconds approximate time between the two endpoints, used for
 *                        finite-difference velocity. Always positive.
 */
public record StructureRenderPose(
        long bodyId,
        Transform3d pose,
        Transform3d previousPose,
        Transform3d currentPose,
        double intervalSeconds
) {
    /** Default publish interval at 20 TPS, used when a source cannot report one. */
    public static final double DEFAULT_INTERVAL_SECONDS = 0.05;

    public StructureRenderPose {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        pose = Objects.requireNonNull(pose, "pose");
        previousPose = Objects.requireNonNull(previousPose, "previousPose");
        currentPose = Objects.requireNonNull(currentPose, "currentPose");
        if (!Double.isFinite(intervalSeconds) || intervalSeconds <= 0.0) {
            intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        }
    }

    /**
     * A pose with no interpolation history. Useful for registered providers
     * that already know the exact pose for the requested partial tick.
     */
    public static StructureRenderPose sampled(long bodyId, Transform3d pose) {
        return new StructureRenderPose(bodyId, pose, pose, pose, DEFAULT_INTERVAL_SECONDS);
    }

    /**
     * Whether the timeline has two distinct endpoints. A renderer can still
     * call {@link #velocityAt(Vector3dc)} on a non-interpolated pose; it will
     * simply return zero.
     */
    public boolean interpolated() {
        return !previousPose.equals(currentPose);
    }

    /**
     * World-space linear velocity of the structure, in metres per second.
     *
     * <p>This is a finite difference over the two timestamped render poses.
     * It intentionally does not read a native rigid body, so it is safe on the
     * client render thread and works for network structures too.</p>
     */
    public Vector3d linearVelocity() {
        if (!interpolated() || intervalSeconds <= 1.0e-9) {
            return new Vector3d();
        }
        return new Vector3d(currentPose.position())
                .sub(previousPose.position())
                .mul(1.0 / intervalSeconds);
    }

    /**
     * World-space angular velocity of the structure, in radians per second.
     *
     * <p>Derived from the quaternion delta between the two render poses. This
     * is an approximation suitable for camera/music/riding effects; physics
     * code should use the authoritative body angular velocity instead.</p>
     */
    public Vector3d angularVelocity() {
        if (!interpolated() || intervalSeconds <= 1.0e-9) {
            return new Vector3d();
        }
        final Quaterniond delta = new Quaterniond(currentPose.rotation())
                .mul(new Quaterniond(previousPose.rotation()).conjugate());
        if (delta.w < 0.0) {
            delta.set(-delta.x, -delta.y, -delta.z, -delta.w);
        }
        final double w = Math.clamp(delta.w, -1.0, 1.0);
        final double angle = 2.0 * Math.acos(w);
        final double sinHalf = Math.sqrt(Math.max(0.0, 1.0 - w * w));
        if (angle <= 1.0e-9 || sinHalf <= 1.0e-9) {
            return new Vector3d();
        }
        final double scale = angle / (sinHalf * intervalSeconds);
        return new Vector3d(delta.x * scale, delta.y * scale, delta.z * scale);
    }

    /**
     * Velocity of the material point at {@code worldPoint}, including the
     * angular contribution ({@code v + omega x r}), in metres per second.
     */
    public Vector3d velocityAt(Vector3dc worldPoint) {
        Objects.requireNonNull(worldPoint, "worldPoint");
        final Vector3d linear = linearVelocity();
        final Vector3d angular = angularVelocity();
        if (angular.lengthSquared() <= 1.0e-20) {
            return linear;
        }
        final Vector3d relative = new Vector3d(worldPoint).sub(currentPose.position());
        return new Vector3d(angular).cross(relative).add(linear);
    }
}
