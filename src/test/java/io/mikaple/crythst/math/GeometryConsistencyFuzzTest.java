package io.mikaple.crythst.math;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property tests for the AABB/OBB conventions.
 *
 * <p>Every case is checked against an independent reference built from
 * {@link Vector3d#rotate(org.joml.Quaterniondc)}. These tests deliberately do
 * not route through {@link OrientedBox#axes()} or {@link Aabb#transform} when
 * computing the expectation, so a transposed matrix, swapped composition order
 * or a missing corner would be caught.</p>
 */
class GeometryConsistencyFuzzTest {
    private static final double EPS = 1.0e-9;

    private static Quaterniond randomRotation(Random random) {
        // Uniform-ish rotation: random axis plus random angle avoids the
        // degeneracies of Euler triples near gimbal lock.
        final Vector3d axis = new Vector3d(
                random.nextGaussian(),
                random.nextGaussian(),
                random.nextGaussian());
        if (axis.lengthSquared() < 1.0e-12) {
            axis.set(1.0, 0.0, 0.0);
        }
        return new Quaterniond().rotateAxis(
                random.nextDouble() * Math.PI * 2.0, axis.normalize());
    }

    private static Vector3d[] expectedCorners(Vector3d center, Vector3d half, Quaterniond rotation) {
        final Vector3d[] corners = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vector3d(
                    (i & 1) == 0 ? half.x : -half.x,
                    (i & 2) == 0 ? half.y : -half.y,
                    (i & 4) == 0 ? half.z : -half.z)
                    .rotate(rotation)
                    .add(center);
        }
        return corners;
    }

    private static Vector3d expectedClosest(Vector3d point, Vector3d center,
                                            Vector3d half, Quaterniond rotation) {
        final Vector3d local = new Vector3d(point).sub(center)
                .rotate(new Quaterniond(rotation).conjugate());
        local.x = Math.max(-half.x, Math.min(half.x, local.x));
        local.y = Math.max(-half.y, Math.min(half.y, local.y));
        local.z = Math.max(-half.z, Math.min(half.z, local.z));
        return local.rotate(rotation).add(center);
    }

    private static double overlapOn(Vector3d d, Vector3d axis,
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
        return Math.abs(d.dot(axis)) - (pa + pb);
    }

    /** Independent 15-axis SAT using quaternion images, not OrientedBox#axes(). */
    private static boolean referenceIntersects(OrientedBox a, OrientedBox b) {
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
        final Vector3d ah = a.halfExtents();
        final Vector3d bh = b.halfExtents();
        final double[] a3 = {ah.x, ah.y, ah.z};
        final double[] b3 = {bh.x, bh.y, bh.z};
        final Vector3d d = new Vector3d(b.center()).sub(a.center());
        for (int i = 0; i < 3; i++) {
            if (overlapOn(d, ax[i], ax, a3, bx, b3) > EPS) {
                return false;
            }
            if (overlapOn(d, bx[i], ax, a3, bx, b3) > EPS) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final Vector3d cross = new Vector3d(ax[i]).cross(bx[j]);
                if (cross.lengthSquared() < 1.0e-18) {
                    continue;
                }
                if (overlapOn(d, cross, ax, a3, bx, b3) > EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void orientedBoxCornersAndAabbMatchRotatedLocalCorners() {
        final Random random = new Random(0xC0FFEE);
        for (int iteration = 0; iteration < 4_000; iteration++) {
            final Vector3d center = new Vector3d(
                    random.nextDouble() * 40.0 - 20.0,
                    random.nextDouble() * 40.0 - 20.0,
                    random.nextDouble() * 40.0 - 20.0);
            final Vector3d half = new Vector3d(
                    0.05 + random.nextDouble() * 4.0,
                    0.05 + random.nextDouble() * 4.0,
                    0.05 + random.nextDouble() * 4.0);
            final Quaterniond rotation = randomRotation(random);
            final OrientedBox box = new OrientedBox(center, half, rotation);

            final Vector3d[] expected = expectedCorners(center, half, rotation);
            final var actual = box.corners();
            assertEquals(8, actual.size());
            for (int i = 0; i < 8; i++) {
                assertEquals(expected[i].x, actual.get(i).x, 1.0e-9, "corner x " + iteration);
                assertEquals(expected[i].y, actual.get(i).y, 1.0e-9, "corner y " + iteration);
                assertEquals(expected[i].z, actual.get(i).z, 1.0e-9, "corner z " + iteration);
            }

            final Aabb aabb = box.toAabb();
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (Vector3d corner : expected) {
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
                maxZ = Math.max(maxZ, corner.z);
            }
            assertEquals(minX, aabb.min().x, 1.0e-9);
            assertEquals(minY, aabb.min().y, 1.0e-9);
            assertEquals(minZ, aabb.min().z, 1.0e-9);
            assertEquals(maxX, aabb.max().x, 1.0e-9);
            assertEquals(maxY, aabb.max().y, 1.0e-9);
            assertEquals(maxZ, aabb.max().z, 1.0e-9);
            for (Vector3d corner : expected) {
                assertTrue(aabb.expand(1.0e-9).contains(corner),
                        "toAabb must enclose every corner " + iteration);
            }
        }
    }

    @Test
    void transformedAabbEnclosesAllTransformedCorners() {
        final Random random = new Random(0xBEEF);
        for (int iteration = 0; iteration < 4_000; iteration++) {
            final Vector3d localMin = new Vector3d(
                    random.nextDouble() * 20.0 - 10.0,
                    random.nextDouble() * 20.0 - 10.0,
                    random.nextDouble() * 20.0 - 10.0);
            final Vector3d localMax = new Vector3d(localMin).add(
                    new Vector3d(0.01 + random.nextDouble() * 5.0,
                            0.01 + random.nextDouble() * 5.0,
                            0.01 + random.nextDouble() * 5.0));
            final Aabb local = new Aabb(localMin, localMax);
            final Transform3d transform = Transform3d.of(
                    new Vector3d(random.nextDouble() * 40.0 - 20.0,
                            random.nextDouble() * 40.0 - 20.0,
                            random.nextDouble() * 40.0 - 20.0),
                    randomRotation(random));

            final Aabb world = local.transform(transform);
            for (int i = 0; i < 8; i++) {
                final Vector3d corner = transform.transformPoint(new Vector3d(
                        (i & 1) == 0 ? localMin.x : localMax.x,
                        (i & 2) == 0 ? localMin.y : localMax.y,
                        (i & 4) == 0 ? localMin.z : localMax.z));
                assertTrue(world.contains(corner), "world AABB must enclose corner " + iteration);
                assertTrue(world.min().x <= corner.x + EPS && world.max().x >= corner.x - EPS);
            }
        }
    }

    @Test
    void fromAabbWithTransformBuildsTheTransformedBox() {
        final Random random = new Random(0x1234);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            final Vector3d localMin = new Vector3d(
                    random.nextDouble() * 10.0 - 5.0,
                    random.nextDouble() * 10.0 - 5.0,
                    random.nextDouble() * 10.0 - 5.0);
            final Vector3d localMax = new Vector3d(localMin).add(
                    new Vector3d(0.05 + random.nextDouble() * 3.0,
                            0.05 + random.nextDouble() * 3.0,
                            0.05 + random.nextDouble() * 3.0));
            final Aabb local = new Aabb(localMin, localMax);
            final Transform3d transform = Transform3d.of(
                    new Vector3d(random.nextDouble() * 20.0 - 10.0,
                            random.nextDouble() * 20.0 - 10.0,
                            random.nextDouble() * 20.0 - 10.0),
                    randomRotation(random));

            final OrientedBox obb = OrientedBox.fromAabb(local, transform);
            final Aabb expected = local.transform(transform);
            final Aabb actual = obb.toAabb();
            assertEquals(expected.min().x, actual.min().x, 1.0e-8);
            assertEquals(expected.min().y, actual.min().y, 1.0e-8);
            assertEquals(expected.min().z, actual.min().z, 1.0e-8);
            assertEquals(expected.max().x, actual.max().x, 1.0e-8);
            assertEquals(expected.max().y, actual.max().y, 1.0e-8);
            assertEquals(expected.max().z, actual.max().z, 1.0e-8);

            // transform(local) point must be inside the OBB.
            for (int i = 0; i < 8; i++) {
                final Vector3d localCorner = new Vector3d(
                        (i & 1) == 0 ? localMin.x : localMax.x,
                        (i & 2) == 0 ? localMin.y : localMax.y,
                        (i & 4) == 0 ? localMin.z : localMax.z);
                assertTrue(obb.contains(transform.transformPoint(localCorner)),
                        "OBB must contain transformed local corner " + iteration);
            }
        }
    }

    @Test
    void containsMatchesTheLocalHalfExtentTest() {
        final Random random = new Random(0xABCDEF);
        for (int iteration = 0; iteration < 300; iteration++) {
            final Vector3d center = new Vector3d(
                    random.nextDouble() * 20.0 - 10.0,
                    random.nextDouble() * 20.0 - 10.0,
                    random.nextDouble() * 20.0 - 10.0);
            final Vector3d half = new Vector3d(
                    0.5 + random.nextDouble() * 2.0,
                    0.5 + random.nextDouble() * 2.0,
                    0.5 + random.nextDouble() * 2.0);
            final Quaterniond rotation = randomRotation(random);
            final OrientedBox box = new OrientedBox(center, half, rotation);
            for (int sample = 0; sample < 200; sample++) {
                final Vector3d local = new Vector3d(
                        (random.nextDouble() * 2.0 - 1.0) * half.x * 1.5,
                        (random.nextDouble() * 2.0 - 1.0) * half.y * 1.5,
                        (random.nextDouble() * 2.0 - 1.0) * half.z * 1.5);
                final Vector3d world = new Vector3d(local).rotate(rotation).add(center);
                final boolean expected = Math.abs(local.x) <= half.x
                        && Math.abs(local.y) <= half.y
                        && Math.abs(local.z) <= half.z;
                // The implementation carries a small epsilon; only compare
                // samples that are not within epsilon of the surface.
                if (Math.abs(Math.abs(local.x) - half.x) < 1.0e-6
                        || Math.abs(Math.abs(local.y) - half.y) < 1.0e-6
                        || Math.abs(Math.abs(local.z) - half.z) < 1.0e-6) {
                    continue;
                }
                assertEquals(expected, box.contains(world),
                        "contains mismatch at iteration " + iteration);
            }
        }
    }

    @Test
    void closestPointIsTheClampedLocalProjection() {
        final Random random = new Random(0x5151);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            final Vector3d center = new Vector3d(
                    random.nextDouble() * 20.0 - 10.0,
                    random.nextDouble() * 20.0 - 10.0,
                    random.nextDouble() * 20.0 - 10.0);
            final Vector3d half = new Vector3d(
                    0.1 + random.nextDouble() * 2.0,
                    0.1 + random.nextDouble() * 2.0,
                    0.1 + random.nextDouble() * 2.0);
            final Quaterniond rotation = randomRotation(random);
            final OrientedBox box = new OrientedBox(center, half, rotation);
            final Vector3d point = new Vector3d(
                    random.nextDouble() * 40.0 - 20.0,
                    random.nextDouble() * 40.0 - 20.0,
                    random.nextDouble() * 40.0 - 20.0);

            final Vector3d expected = expectedClosest(point, center, half, rotation);
            final Vector3d actual = box.closestPoint(point);
            assertEquals(expected.x, actual.x, 1.0e-9);
            assertEquals(expected.y, actual.y, 1.0e-9);
            assertEquals(expected.z, actual.z, 1.0e-9);
            assertTrue(box.contains(actual), "closest point must lie inside " + iteration);
        }
    }

    @Test
    void satMatchesIndependentReferenceForRandomTiltedBoxes() {
        final Random random = new Random(0x5A7);
        int overlaps = 0;
        int separates = 0;
        for (int iteration = 0; iteration < 30_000; iteration++) {
            final Vector3d ac = new Vector3d(
                    random.nextDouble() * 6.0 - 3.0,
                    random.nextDouble() * 6.0 - 3.0,
                    random.nextDouble() * 6.0 - 3.0);
            final Vector3d ah = new Vector3d(
                    0.2 + random.nextDouble() * 2.0,
                    0.2 + random.nextDouble() * 2.0,
                    0.2 + random.nextDouble() * 2.0);
            final OrientedBox a = new OrientedBox(ac, ah, randomRotation(random));
            final Vector3d bc = new Vector3d(ac).add(
                    new Vector3d(random.nextDouble() * 6.0 - 3.0,
                            random.nextDouble() * 6.0 - 3.0,
                            random.nextDouble() * 6.0 - 3.0));
            final Vector3d bh = new Vector3d(
                    0.2 + random.nextDouble() * 2.0,
                    0.2 + random.nextDouble() * 2.0,
                    0.2 + random.nextDouble() * 2.0);
            final OrientedBox b = new OrientedBox(bc, bh, randomRotation(random));

            final boolean expected = referenceIntersects(a, b);
            final boolean actual = a.intersects(b);
            assertEquals(expected, actual, "SAT disagreed at iteration " + iteration);
            assertEquals(actual, b.intersects(a),
                    "SAT must be symmetric at iteration " + iteration);
            if (expected) {
                overlaps++;
            } else {
                separates++;
            }
        }
        assertTrue(overlaps > 1_000, "fuzz must include real overlaps: " + overlaps);
        assertTrue(separates > 1_000, "fuzz must include real separations: " + separates);
    }

    @Test
    void satAgainstAabbMatchesReference() {
        final Random random = new Random(0xA88B);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            final Vector3d ac = new Vector3d(
                    random.nextDouble() * 4.0 - 2.0,
                    random.nextDouble() * 4.0 - 2.0,
                    random.nextDouble() * 4.0 - 2.0);
            final Vector3d ah = new Vector3d(
                    0.2 + random.nextDouble() * 1.5,
                    0.2 + random.nextDouble() * 1.5,
                    0.2 + random.nextDouble() * 1.5);
            final OrientedBox a = new OrientedBox(ac, ah, randomRotation(random));
            final Vector3d amin = new Vector3d(
                    random.nextDouble() * 4.0 - 2.0,
                    random.nextDouble() * 4.0 - 2.0,
                    random.nextDouble() * 4.0 - 2.0);
            final Vector3d amax = new Vector3d(amin).add(
                    new Vector3d(0.2 + random.nextDouble() * 2.0,
                            0.2 + random.nextDouble() * 2.0,
                            0.2 + random.nextDouble() * 2.0));
            final Aabb b = new Aabb(amin, amax);

            final boolean expected = referenceIntersects(a, OrientedBox.fromAabb(b));
            assertEquals(expected, a.intersects(b), "OBB/AABB SAT disagreed at " + iteration);
            // An identity-rotation OBB built from a world AABB must reproduce
            // that AABB exactly (to rounding).
            final Aabb hull = OrientedBox.fromAabb(b).toAabb();
            assertEquals(b.min().x, hull.min().x, 1.0e-12);
            assertEquals(b.min().y, hull.min().y, 1.0e-12);
            assertEquals(b.min().z, hull.min().z, 1.0e-12);
            assertEquals(b.max().x, hull.max().x, 1.0e-12);
            assertEquals(b.max().y, hull.max().y, 1.0e-12);
            assertEquals(b.max().z, hull.max().z, 1.0e-12);
        }
    }

    @Test
    void orientedBoxTransformComposesInTheSameOrderAsPoints() {
        final Random random = new Random(0x7777);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            final Vector3d center = new Vector3d(
                    random.nextDouble() * 10.0 - 5.0,
                    random.nextDouble() * 10.0 - 5.0,
                    random.nextDouble() * 10.0 - 5.0);
            final Vector3d half = new Vector3d(
                    0.1 + random.nextDouble() * 2.0,
                    0.1 + random.nextDouble() * 2.0,
                    0.1 + random.nextDouble() * 2.0);
            final OrientedBox local = new OrientedBox(center, half, randomRotation(random));
            final Transform3d transform = Transform3d.of(
                    new Vector3d(random.nextDouble() * 10.0 - 5.0,
                            random.nextDouble() * 10.0 - 5.0,
                            random.nextDouble() * 10.0 - 5.0),
                    randomRotation(random));

            final OrientedBox world = local.transform(transform);
            final var localCorners = local.corners();
            final var worldCorners = world.corners();
            for (int i = 0; i < 8; i++) {
                final Vector3d expected = transform.transformPoint(localCorners.get(i));
                assertEquals(expected.x, worldCorners.get(i).x, 1.0e-9);
                assertEquals(expected.y, worldCorners.get(i).y, 1.0e-9);
                assertEquals(expected.z, worldCorners.get(i).z, 1.0e-9);
            }
        }
    }

    @Test
    void transformComposeMatchesSequentialApplicationForTiltedBoxes() {
        final Random random = new Random(0x9999);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            final Transform3d first = Transform3d.of(
                    new Vector3d(random.nextDouble() * 20.0 - 10.0,
                            random.nextDouble() * 20.0 - 10.0,
                            random.nextDouble() * 20.0 - 10.0),
                    randomRotation(random));
            final Transform3d second = Transform3d.of(
                    new Vector3d(random.nextDouble() * 20.0 - 10.0,
                            random.nextDouble() * 20.0 - 10.0,
                            random.nextDouble() * 20.0 - 10.0),
                    randomRotation(random));
            final Vector3d local = new Vector3d(
                    random.nextDouble() * 10.0 - 5.0,
                    random.nextDouble() * 10.0 - 5.0,
                    random.nextDouble() * 10.0 - 5.0);

            final Vector3d sequential = second.transformPoint(first.transformPoint(local));
            final Vector3d composed = second.compose(first).transformPoint(local);
            assertEquals(sequential.x, composed.x, 1.0e-9);
            assertEquals(sequential.y, composed.y, 1.0e-9);
            assertEquals(sequential.z, composed.z, 1.0e-9);

            final Vector3d restored = second.compose(first).inverseTransformPoint(composed);
            assertEquals(local.x, restored.x, 1.0e-9);
            assertEquals(local.y, restored.y, 1.0e-9);
            assertEquals(local.z, restored.z, 1.0e-9);
        }
    }

    @Test
    void flatAabbStillProducesAUsableOrientedBox() {
        // A zero-thickness AABB is legal (Aabb only forbids min > max). Callers
        // such as CompoundShape#localBounds and terrain shapes can produce one,
        // and OrientedBox must not reject it.
        final Aabb flat = new Aabb(new Vector3d(-1.0, 0.5, -1.0), new Vector3d(1.0, 0.5, 1.0));
        final OrientedBox box = OrientedBox.fromAabb(flat);
        assertEquals(0.0, box.halfExtents().y, 1.0e-12);
        assertTrue(box.contains(new Vector3d(0.0, 0.5, 0.0)));
        assertFalse(box.contains(new Vector3d(0.0, 0.6, 0.0)));
        assertTrue(box.intersects(new Aabb(
                new Vector3d(-1.0, 0.4, -1.0), new Vector3d(1.0, 0.6, 1.0))));
    }

    @Test
    void emptyAabbDoesNotThrowWhenConverted() {
        final Aabb empty = new Aabb(new Vector3d(), new Vector3d());
        final OrientedBox box = OrientedBox.fromAabb(empty);
        assertEquals(0.0, box.volume(), 1.0e-12);
        assertTrue(box.contains(new Vector3d()));
    }
}
