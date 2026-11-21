package io.mikaple.crythst.math;

import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two coordinate conventions that a tilted structure must not mix up:
 *
 * <ul>
 *   <li>the <em>structure pose</em> maps unshifted voxel metres to world
 *       space (voxel {@code (x,y,z)} occupies {@code x*scale .. (x+1)*scale});</li>
 *   <li>the <em>body pose</em> maps centre-of-mass-relative metres to world
 *       space, and {@link VoxelColliderData#localBounds()} is expressed in that
 *       frame.</li>
 * </ul>
 *
 * <p>A world-space bound may use either pair, but never pose from one and
 * bounds from the other. Mixing them translates the box by {@code -R*COM}
 * (or {@code +R*COM}), which is invisible at identity rotation and therefore
 * only shows up as a clipped/offset box once the structure tilts.</p>
 */
class TiltedStructureBoundsTest {
    /** L-shaped 3x2x1 volume so the centre of mass is not the box centre. */
    private static VoxelVolume lShape(double scale) {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 2, 1);
        return new VoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                if (!bounds.contains(x, y, z)) {
                    return false;
                }
                // Two voxels on the bottom row, one above the left voxel.
                return (y == 0 && (x == 0 || x == 1)) || (y == 1 && x == 0);
            }

            @Override
            public double blockMass(int x, int y, int z) {
                return 1000.0 * scale * scale * scale;
            }
        };
    }

    private static Transform3d structurePose(Transform3d bodyPose, Vector3d comOffset) {
        final Vector3d structurePosition = new Vector3d(bodyPose.position())
                .sub(new Vector3d(comOffset).rotate(bodyPose.rotation()));
        return Transform3d.of(structurePosition, bodyPose.rotation());
    }

    /** World AABB of every merged collider box under the structure pose. */
    private static Aabb colliderCornerHull(VoxelColliderData collider, Transform3d pose, double scale) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (VoxelBox box : collider.boxes()) {
            for (int corner = 0; corner < 8; corner++) {
                final Vector3d point = pose.transformPoint(new Vector3d(
                        (box.minX() + ((corner & 1) == 0 ? 0.0 : box.sizeX())) * scale,
                        (box.minY() + ((corner & 2) == 0 ? 0.0 : box.sizeY())) * scale,
                        (box.minZ() + ((corner & 4) == 0 ? 0.0 : box.sizeZ())) * scale));
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                minZ = Math.min(minZ, point.z);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                maxZ = Math.max(maxZ, point.z);
            }
        }
        for (VoxelCellPart part : collider.customParts()) {
            for (int corner = 0; corner < 8; corner++) {
                final Vector3d point = pose.transformPoint(new Vector3d(
                        (part.minX() + ((corner & 1) == 0 ? 0.0 : part.sizeX())) * scale,
                        (part.minY() + ((corner & 2) == 0 ? 0.0 : part.sizeY())) * scale,
                        (part.minZ() + ((corner & 4) == 0 ? 0.0 : part.sizeZ())) * scale));
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                minZ = Math.min(minZ, point.z);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                maxZ = Math.max(maxZ, point.z);
            }
        }
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    private static Quaterniond tilted(Random random) {
        return new Quaterniond()
                .rotateX(random.nextDouble() * Math.PI * 2.0)
                .rotateY(random.nextDouble() * Math.PI * 2.0)
                .rotateZ(random.nextDouble() * Math.PI * 2.0);
    }

    @Test
    void colliderLocalBoundsMatchTheCompoundShapeLocalBounds() {
        final Random random = new Random(0x51A7);
        final double scale = 0.5 + random.nextDouble() * 2.0;
        final VoxelVolume volume = lShape(scale);
        final VoxelColliderData collider = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT.withBlockScale(scale));

        final Aabb direct = collider.localBounds();
        final Aabb compound = collider.toCompoundShape().localBounds();
        assertEquals(direct.min().x, compound.min().x, 1.0e-9);
        assertEquals(direct.min().y, compound.min().y, 1.0e-9);
        assertEquals(direct.min().z, compound.min().z, 1.0e-9);
        assertEquals(direct.max().x, compound.max().x, 1.0e-9);
        assertEquals(direct.max().y, compound.max().y, 1.0e-9);
        assertEquals(direct.max().z, compound.max().z, 1.0e-9);
    }

    @Test
    void tiltedRenderBoundsEqualTheStructurePoseColliderHull() {
        final Random random = new Random(0x0BB0);
        for (int iteration = 0; iteration < 500; iteration++) {
            final double scale = 0.25 + random.nextDouble() * 2.5;
            final VoxelVolume volume = lShape(scale);
            final VoxelColliderData collider = VoxelColliderBuilder.build(
                    volume, VoxelColliderOptions.DEFAULT.withBlockScale(scale));

            final Transform3d bodyPose = Transform3d.of(
                    new Vector3d(random.nextDouble() * 40.0 - 20.0,
                            random.nextDouble() * 40.0 - 20.0,
                            random.nextDouble() * 40.0 - 20.0),
                    tilted(random));
            final Vector3d comOffset = new Vector3d(collider.centerOfMass()).mul(scale);
            final Transform3d structurePose = structurePose(bodyPose, comOffset);

            final StructureRenderState state = new StructureRenderState(
                    1L, structurePose, collider, comOffset, scale);

            // Convention A (structure pose): the collider bounds expressed in
            // unshifted voxel metres and mapped with the structure pose.
            final Aabb voxelFrameBounds = new Aabb(
                    collider.localBounds().min().add(comOffset),
                    collider.localBounds().max().add(comOffset));
            final Aabb expected = voxelFrameBounds.transform(structurePose);
            // Convention B (body pose): COM-relative bounds mapped with the
            // body pose. Both must produce the same world AABB.
            final Aabb actual = state.aabb();
            final Aabb bodyHull = collider.localBounds().transform(bodyPose);
            assertEquals(expected.min().x, actual.min().x, 1.0e-8, "min x " + iteration);
            assertEquals(expected.min().y, actual.min().y, 1.0e-8, "min y " + iteration);
            assertEquals(expected.min().z, actual.min().z, 1.0e-8, "min z " + iteration);
            assertEquals(expected.max().x, actual.max().x, 1.0e-8, "max x " + iteration);
            assertEquals(expected.max().y, actual.max().y, 1.0e-8, "max y " + iteration);
            assertEquals(expected.max().z, actual.max().z, 1.0e-8, "max z " + iteration);
            assertEquals(expected.min().x, bodyHull.min().x, 1.0e-8);
            assertEquals(expected.min().y, bodyHull.min().y, 1.0e-8);
            assertEquals(expected.min().z, bodyHull.min().z, 1.0e-8);
            assertEquals(expected.max().x, bodyHull.max().x, 1.0e-8);
            assertEquals(expected.max().y, bodyHull.max().y, 1.0e-8);
            assertEquals(expected.max().z, bodyHull.max().z, 1.0e-8);

            // The body-pose OBB must also be the body-pose image of the
            // COM-relative collider bounds.
            final OrientedBox bodyObb = OrientedBox.fromAabb(collider.localBounds(), bodyPose);
            assertEquals(bodyHull.min().x, bodyObb.toAabb().min().x, 1.0e-8);
            assertEquals(bodyHull.max().z, bodyObb.toAabb().max().z, 1.0e-8);

            // The enclosing AABB must actually enclose the collision geometry,
            // not merely agree with the other pose convention.
            final Aabb geometryHull = colliderCornerHull(collider, structurePose, scale);
            assertTrue(expected.min().x <= geometryHull.min().x + 1.0e-8, "geometry min x");
            assertTrue(expected.min().y <= geometryHull.min().y + 1.0e-8, "geometry min y");
            assertTrue(expected.min().z <= geometryHull.min().z + 1.0e-8, "geometry min z");
            assertTrue(expected.max().x >= geometryHull.max().x - 1.0e-8, "geometry max x");
            assertTrue(expected.max().y >= geometryHull.max().y - 1.0e-8, "geometry max y");
            assertTrue(expected.max().z >= geometryHull.max().z - 1.0e-8, "geometry max z");

            final IntBounds3 bounds = volume.bounds();
            for (int y = bounds.minY(); y < bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                    for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                        if (!volume.isSolid(x, y, z)) {
                            continue;
                        }
                        final Vector3d centre = structurePose.transformPoint(new Vector3d(
                                (x + 0.5) * scale, (y + 0.5) * scale, (z + 0.5) * scale));
                        assertTrue(state.bounds().contains(centre),
                                "OBB must contain a tilted solid voxel centre " + iteration);
                        assertTrue(state.aabb().contains(centre),
                                "world AABB must contain a tilted solid voxel centre " + iteration);
                    }
                }
            }
        }
    }

    @Test
    void shiftingToTheOtherPoseConventionMovesTheBoxOnlyWhenTilted() {
        // Guards against the exact mistake this test class documents: using
        // structure-pose position with COM-relative bounds. At identity
        // rotation it is invisible; once tilted it offsets the box sideways.
        final VoxelVolume volume = lShape(1.0);
        final VoxelColliderData collider = VoxelColliderBuilder.build(
                volume, VoxelColliderOptions.DEFAULT);
        final Vector3d com = collider.centerOfMass();
        assertTrue(com.lengthSquared() > 0.1,
                "test volume must have an off-centre centre of mass");

        final Transform3d bodyPose = Transform3d.of(new Vector3d(10, 20, 30),
                new Quaterniond().rotateX(0.7).rotateZ(0.4));
        final Transform3d structurePose = structurePose(bodyPose, com);

        final OrientedBox correct = OrientedBox.fromAabb(collider.localBounds(), bodyPose);
        final OrientedBox wrong = OrientedBox.fromAabb(collider.localBounds(), structurePose);
        final double offset = correct.center().distance(wrong.center());
        assertEquals(new Vector3d(com).length(), offset, 1.0e-9,
                "the wrong pose convention must shift by |COM|");
        assertTrue(offset > 0.1);
    }
}
