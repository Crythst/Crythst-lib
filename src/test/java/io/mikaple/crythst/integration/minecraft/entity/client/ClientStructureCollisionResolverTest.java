package io.mikaple.crythst.integration.minecraft.entity.client;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStructureCollisionResolverTest {
    private static final double MARGIN = 0.02;
    private static final int MAX_BOXES = 64;
    private static final double MAX_CORRECTION = ClientStructureCollisionResolver.DEFAULT_MAX_CORRECTION;

    private static VoxelBox box(int x, int y, int z, int sx, int sy, int sz) {
        return new VoxelBox(x, y, z, sx, sy, sz, PhysicsMaterial.STONE, 1.0);
    }

    private static Aabb entityBox(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    private static ClientStructureCollisionResolver.Collider collider(
            Transform3d pose, List<VoxelBox> boxes, double blockScale) {
        return new ClientStructureCollisionResolver.Collider(pose, boxes, blockScale);
    }

    private static ClientStructureCollisionResolver.Collider collider(
            Transform3d pose, List<VoxelBox> boxes, List<VoxelCellPart> parts,
            double blockScale) {
        return new ClientStructureCollisionResolver.Collider(pose, boxes, blockScale, parts);
    }

    @Test
    void axisAlignedFloorPushesPlayerUp() {
        final var correction = ClientStructureCollisionResolver.resolve(
                entityBox(0.2, 0.9, 0.2, 0.8, 2.7, 0.8),
                null,
                collider(Transform3d.identity(), List.of(box(0, 0, 0, 1, 1, 1)), 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);

        assertNotNull(correction);
        assertTrue(correction.normal().y > 0.99);
        assertEquals(0.1, correction.penetration(), 1.0e-6);
        assertEquals(0.12, correction.offset().y, 1.0e-6);
        assertEquals(0.0, correction.offset().x, 1.0e-6);
        assertEquals(0.0, correction.offset().z, 1.0e-6);
    }

    @Test
    void fractionalSlabPartPushesPlayerOntoTheHalfBlockTop() {
        final VoxelCellPart slab = new VoxelCellPart(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        final var correction = ClientStructureCollisionResolver.resolve(
                entityBox(0.2, 0.45, 0.2, 0.8, 2.25, 0.8),
                null,
                collider(Transform3d.identity(), List.of(), List.of(slab), 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);

        assertNotNull(correction);
        assertTrue(correction.normal().y > 0.99);
        assertEquals(0.05, correction.penetration(), 1.0e-6);
        assertEquals(0.07, correction.offset().y, 1.0e-6);
    }

    @Test
    void rotatedStructureUsesWorldSpaceNormal() {
        final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(90.0));
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 0.0, 0.0), rotation);

        final var correction = ClientStructureCollisionResolver.resolve(
                entityBox(10.2, 0.9, -0.8, 10.8, 2.7, -0.2),
                null,
                collider(pose, List.of(box(0, 0, 0, 1, 1, 1)), 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);

        assertNotNull(correction);
        assertTrue(correction.normal().y > 0.99);
        assertEquals(0.1, correction.penetration(), 1.0e-6);
        assertEquals(0.12, correction.offset().y, 1.0e-6);
    }

    @Test
    void rotatedCubeCorrectionActuallySeparatesThePlayer() {
        final Quaterniond rotation = new Quaterniond()
                .rotateX(0.41)
                .rotateY(0.93)
                .rotateZ(-0.37);
        final Transform3d pose = Transform3d.of(new Vector3d(2.0, -1.0, 6.0), rotation);
        final double scale = 0.5;
        final List<VoxelBox> boxes = List.of(box(0, 0, 0, 6, 6, 6));

        // Player standing slightly inside the rotated cube's local +X face.
        final Vector3d inside = pose.transformPoint(new Vector3d(2.95, 1.25, 1.25));
        final Aabb player = Aabb.fromCenterHalfExtents(inside, new Vector3d(0.2, 0.8, 0.2));
        final ClientStructureCollisionResolver.Collider collider =
                collider(pose, boxes, scale);

        final var correction = ClientStructureCollisionResolver.resolve(
                player, null, collider, MARGIN, MAX_BOXES, MAX_CORRECTION);
        assertNotNull(correction);

        final Aabb separated = player.offset(correction.offset());
        assertNull(ClientStructureCollisionResolver.resolve(
                separated, null, collider, MARGIN, MAX_BOXES, MAX_CORRECTION),
                "corrected player must no longer overlap the structure");
    }

    @Test
    void playerBoxAgainstMultipleMergedBoxes() {
        // A two-block-wide floor: the player straddles the shared edge.
        final var correction = ClientStructureCollisionResolver.resolve(
                entityBox(0.8, 0.9, 0.2, 1.4, 2.7, 0.8),
                null,
                collider(Transform3d.identity(),
                        List.of(box(0, 0, 0, 1, 1, 1), box(1, 0, 0, 1, 1, 1)), 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);

        assertNotNull(correction);
        assertTrue(correction.normal().y > 0.99);
        assertEquals(0.1, correction.penetration(), 1.0e-6);
        assertEquals(0.12, correction.offset().y, 1.0e-6);
    }

    @Test
    void embeddedSideExitsThroughTheNearFace() {
        final List<VoxelBox> wall = List.of(box(0, 0, 0, 1, 2, 1));

        final var positive = ClientStructureCollisionResolver.resolve(
                entityBox(0.85, 0.1, 0.2, 1.45, 1.9, 0.8),
                null,
                collider(Transform3d.identity(), wall, 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);
        assertNotNull(positive);
        assertTrue(positive.normal().x > 0.99, "expected +X exit: " + positive.normal());
        assertEquals(0.17, positive.offset().x, 1.0e-6);

        final var negative = ClientStructureCollisionResolver.resolve(
                entityBox(-0.45, 0.1, 0.2, 0.15, 1.9, 0.8),
                null,
                collider(Transform3d.identity(), wall, 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);
        assertNotNull(negative);
        assertTrue(negative.normal().x < -0.99, "expected -X exit: " + negative.normal());
        assertEquals(-0.17, negative.offset().x, 1.0e-6);
    }

    @Test
    void playerAlreadyOutsideIsNotMoved() {
        final var correction = ClientStructureCollisionResolver.resolve(
                entityBox(0.2, 1.3, 0.2, 0.8, 2.1, 0.8),
                null,
                collider(Transform3d.identity(), List.of(box(0, 0, 0, 1, 1, 1)), 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);

        assertNull(correction);
    }

    @Test
    void previousCentreHintKeepsPlayerOnEntrySide() {
        final Aabb current = entityBox(0.3, 0.1, 0.2, 0.9, 1.9, 0.8);
        final ClientStructureCollisionResolver.Collider collider =
                collider(Transform3d.identity(), List.of(box(0, 0, 0, 1, 2, 1)), 1.0);

        final var withoutHint = ClientStructureCollisionResolver.resolve(
                current, null, collider, MARGIN, MAX_BOXES, MAX_CORRECTION);
        assertNotNull(withoutHint);
        assertTrue(withoutHint.normal().x > 0.99,
                "without a hint the far side wins: " + withoutHint.normal());

        final var withHint = ClientStructureCollisionResolver.resolve(
                current, new Vector3d(0.4, 1.0, 0.5),
                collider, MARGIN, MAX_BOXES, MAX_CORRECTION);
        assertNotNull(withHint);
        assertTrue(withHint.normal().x < -0.99,
                "hint should choose the entry side, got " + withHint.normal());
        assertTrue(withHint.offset().x < 0.0);
    }

    @Test
    void oversizedCorrectionIsLeftToTheServer() {
        final var correction = ClientStructureCollisionResolver.resolve(
                entityBox(2.0, 2.0, 2.0, 2.6, 2.6, 2.6),
                null,
                collider(Transform3d.identity(), List.of(box(0, 0, 0, 8, 8, 8)), 1.0),
                MARGIN, MAX_BOXES, MAX_CORRECTION);

        assertNull(correction);
    }

    @Test
    void listResolutionAppliesTheFirstApplicableStructure() {
        final Aabb player = entityBox(0.2, 0.9, 0.2, 0.8, 2.7, 0.8);
        final var correction = ClientStructureCollisionResolver.resolve(
                player,
                null,
                List.of(
                        collider(Transform3d.identity(), List.of(box(0, 0, 0, 1, 1, 1)), 1.0),
                        collider(Transform3d.of(new Vector3d(100.0, 0.0, 0.0)),
                                List.of(box(0, 0, 0, 1, 1, 1)), 1.0)),
                MARGIN,
                MAX_BOXES,
                ClientStructureCollisionResolver.DEFAULT_MAX_PASSES,
                MAX_CORRECTION);

        assertNotNull(correction);
        assertTrue(correction.normal().y > 0.99);
        assertEquals(0.12, correction.offset().y, 1.0e-6);
    }

    @Test
    void emptyCandidateListIsANoOp() {
        assertNull(ClientStructureCollisionResolver.resolve(
                entityBox(0.2, 0.9, 0.2, 0.8, 2.7, 0.8),
                null,
                List.of(),
                MARGIN,
                MAX_BOXES,
                ClientStructureCollisionResolver.DEFAULT_MAX_PASSES,
                MAX_CORRECTION));
    }

    @Test
    void budgetExhaustionIsANoOp() {
        final var correction = ClientStructureCollisionResolver.resolve(
                entityBox(0.2, 0.9, 0.2, 0.8, 2.7, 0.8),
                null,
                collider(Transform3d.identity(), List.of(box(0, 0, 0, 1, 1, 1)), 1.0),
                MARGIN, 0, MAX_CORRECTION);

        assertNull(correction);
    }
}
