package io.mikaple.crythst.integration.minecraft.entity;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityStructureSolverFuzzTest {
    @Test
    void randomRotatedBoxesAreSeparatedOrReportedCorrectly() {
        final Random random = new Random(0x5EED);
        for (int i = 0; i < 20_000; i++) {
            final double scale = 0.25 + random.nextDouble() * 1.5;
            final VoxelBox box = new VoxelBox(0, 0, 0,
                    1 + random.nextInt(4), 1 + random.nextInt(3), 1 + random.nextInt(4),
                    PhysicsMaterial.STONE, 1.0);
            final Quaterniond rotation = new Quaterniond()
                    .rotateX(random.nextDouble() * Math.PI * 2.0)
                    .rotateY(random.nextDouble() * Math.PI * 2.0)
                    .rotateZ(random.nextDouble() * Math.PI * 2.0);
            final Vector3d posePos = new Vector3d(
                    random.nextDouble() * 20.0 - 10.0,
                    random.nextDouble() * 10.0 - 5.0,
                    random.nextDouble() * 20.0 - 10.0);
            final Transform3d pose = Transform3d.of(posePos, rotation);

            final Vector3d boxCenter = pose.transformPoint(new Vector3d(
                    (box.minX() + box.sizeX() * 0.5) * scale,
                    (box.minY() + box.sizeY() * 0.5) * scale,
                    (box.minZ() + box.sizeZ() * 0.5) * scale));
            final Vector3d boxHalf = new Vector3d(box.sizeX(), box.sizeY(), box.sizeZ()).mul(scale * 0.5);
            final OrientedBox boxObb = new OrientedBox(boxCenter, boxHalf, rotation);

            final double hx = 0.1 + random.nextDouble() * 0.5;
            final double hy = 0.1 + random.nextDouble() * 1.0;
            final double hz = 0.1 + random.nextDouble() * 0.5;
            final Vector3d entityCenter = new Vector3d(
                    boxCenter.x + (random.nextDouble() - 0.5) * (boxHalf.x + hx) * 3.0,
                    boxCenter.y + (random.nextDouble() - 0.5) * (boxHalf.y + hy) * 3.0,
                    boxCenter.z + (random.nextDouble() - 0.5) * (boxHalf.z + hz) * 3.0);
            final Aabb entityBox = Aabb.fromCenterHalfExtents(entityCenter, new Vector3d(hx, hy, hz));
            final OrientedBox entityObb = OrientedBox.fromAabb(entityBox);
            final boolean referenceOverlap = correctIntersects(entityObb, boxObb);
            assertEquals(referenceOverlap, boxObb.intersects(entityObb),
                    "OrientedBox SAT disagreed with the independent SAT at iteration " + i);

            final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                    entityBox, pose, List.of(box), scale, 0.02, 8);

            if (referenceOverlap) {
                assertNotNull(hit, "missed overlap at iteration " + i);
            }
            if (hit != null) {
                final Aabb moved = entityBox.offset(hit.offset());
                assertFalse(correctIntersects(OrientedBox.fromAabb(moved), boxObb),
                        "offset did not separate at iteration " + i
                                + " normal=" + hit.normal() + " depth=" + hit.penetration());
                assertTrue(Math.abs(hit.normal().length() - 1.0) < 1.0e-9);
            }
        }
    }

    /**
     * Independent 15-axis SAT using the actual JOML rotation images of the
     * local axes. Deliberately does not call OrientedBox#intersects so this
     * test can catch a transposed-axes bug in that helper.
     */
    private static boolean correctIntersects(OrientedBox a, OrientedBox b) {
        final Vector3d[] ax = {
                new Vector3d(1, 0, 0).rotate(a.rotation()),
                new Vector3d(0, 1, 0).rotate(a.rotation()),
                new Vector3d(0, 0, 1).rotate(a.rotation())
        };
        final Vector3d[] bx = {
                new Vector3d(1, 0, 0).rotate(b.rotation()),
                new Vector3d(0, 1, 0).rotate(b.rotation()),
                new Vector3d(0, 0, 1).rotate(b.rotation())
        };
        final double[] ah = {a.halfExtents().x, a.halfExtents().y, a.halfExtents().z};
        final double[] bh = {b.halfExtents().x, b.halfExtents().y, b.halfExtents().z};
        final Vector3d d = new Vector3d(b.center()).sub(a.center());
        for (int i = 0; i < 3; i++) {
            if (!overlapOn(d, ax[i], ax, ah, bx, bh)) {
                return false;
            }
            if (!overlapOn(d, bx[i], ax, ah, bx, bh)) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final Vector3d cross = new Vector3d(ax[i]).cross(bx[j]);
                if (cross.lengthSquared() < 1.0e-18) {
                    continue;
                }
                if (!overlapOn(d, cross, ax, ah, bx, bh)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean overlapOn(Vector3d d, Vector3d axis,
                                     Vector3d[] ax, double[] ah,
                                     Vector3d[] bx, double[] bh) {
        double pa = 0.0;
        for (int i = 0; i < 3; i++) {
            pa += ah[i] * Math.abs(ax[i].dot(axis));
        }
        double pb = 0.0;
        for (int j = 0; j < 3; j++) {
            pb += bh[j] * Math.abs(bx[j].dot(axis));
        }
        return Math.abs(d.dot(axis)) <= pa + pb + 1.0e-9;
    }
}
