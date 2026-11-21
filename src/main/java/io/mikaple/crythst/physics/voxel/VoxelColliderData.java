package io.mikaple.crythst.physics.voxel;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.CrythstMath;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.shape.BoxShape;
import io.mikaple.crythst.physics.shape.CompoundShape;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.List;

/**
 * Collision and mass data extracted from a {@link VoxelVolume}.
 *
 * <p>Boxes stay in unscaled voxel coordinates; {@link #toCompoundShape()} emits
 * metre-scaled children centred on the centre of mass. {@link #inertia()} is the
 * full 3x3 tensor about the centre of mass, so bodies made from asymmetric
 * structures tumble correctly.</p>
 */
public final class VoxelColliderData {
    private final List<VoxelBox> boxes;
    private final double blockScale;
    private final double mass;
    private final Vector3d centerOfMass;
    private final Matrix3d inertia;
    private final Vector3d size;

    public VoxelColliderData(List<VoxelBox> boxes, double blockScale,
                             double mass, Vector3d centerOfMass,
                             Matrix3d inertia, Vector3d size) {
        this.boxes = List.copyOf(boxes);
        this.blockScale = blockScale;
        this.mass = mass;
        this.centerOfMass = new Vector3d(centerOfMass);
        this.inertia = new Matrix3d(inertia);
        this.size = new Vector3d(size);
    }

    public List<VoxelBox> boxes() {
        return boxes;
    }

    public double blockScale() {
        return blockScale;
    }

    public double mass() {
        return mass;
    }

    public Vector3d centerOfMass() {
        return new Vector3d(centerOfMass);
    }

    public Matrix3d inertia() {
        return new Matrix3d(inertia);
    }

    public Vector3d size() {
        return new Vector3d(size);
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    public Aabb localBounds() {
        if (boxes.isEmpty()) {
            return new Aabb(new Vector3d(), new Vector3d());
        }
        final Vector3d min = new Vector3d(Double.POSITIVE_INFINITY);
        final Vector3d max = new Vector3d(Double.NEGATIVE_INFINITY);
        final Vector3d comOffset = new Vector3d(centerOfMass).mul(blockScale);
        for (VoxelBox box : boxes) {
            min.min(new Vector3d(box.minX(), box.minY(), box.minZ()).mul(blockScale).sub(comOffset));
            max.max(new Vector3d(box.minX() + box.sizeX(), box.minY() + box.sizeY(), box.minZ() + box.sizeZ())
                    .mul(blockScale).sub(comOffset));
        }
        return Aabb.of(min, max);
    }

    /**
     * Builds a compound shape whose origin coincides with the centre of mass.
     */
    public CompoundShape toCompoundShape() {
        if (boxes.isEmpty()) {
            throw new IllegalStateException("Cannot build a shape from an empty voxel collider");
        }
        final CompoundShape.Builder builder = CompoundShape.builder();
        for (VoxelBox box : boxes) {
            final Vector3d offset = box.center().sub(centerOfMass).mul(blockScale);
            final Vector3d halfExtents = new Vector3d(box.sizeX(), box.sizeY(), box.sizeZ()).mul(blockScale * 0.5);
            builder.add(new BoxShape(halfExtents), Transform3d.of(offset));
        }
        return builder.build();
    }

    /**
     * Converts a point in structure voxel coordinates into body-local metres,
     * relative to the centre of mass.
     */
    public Vector3d voxelToBodyLocal(Vector3d dest, double x, double y, double z) {
        final Vector3d scaled = new Vector3d(x, y, z).mul(blockScale).sub(new Vector3d(centerOfMass).mul(blockScale));
        return dest.set(scaled);
    }

    public Vector3d bodyLocalToVoxel(Vector3d dest, double x, double y, double z) {
        return dest.set(x, y, z)
                .add(new Vector3d(centerOfMass).mul(blockScale))
                .mul(1.0 / blockScale);
    }

    public Matrix3d inverseInertia() {
        return CrythstMath.invertInertia(inertia);
    }
}
