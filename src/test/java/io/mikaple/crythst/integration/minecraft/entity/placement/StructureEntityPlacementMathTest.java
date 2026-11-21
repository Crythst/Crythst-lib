package io.mikaple.crythst.integration.minecraft.entity.placement;

import io.mikaple.crythst.integration.minecraft.interaction.StructureItemUse;
import io.mikaple.crythst.integration.minecraft.interaction.StructurePlacementMath;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureEntityPlacementMathTest {
    private static final double EPSILON = 0.1;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyFaceHasTheExpectedCentreAndOutwardNormal() {
        final Transform3d pose = new Transform3d(
                new Vector3d(10.0, 64.0, -3.0), new Quaterniond());
        final int x = 2;
        final int y = 3;
        final int z = 4;

        for (Direction face : Direction.values()) {
            final StructureEntityPlacementMath.SurfacePoint surface =
                    StructureEntityPlacementMath.surfacePoint(hit(pose, x, y, z, face, 1.0), EPSILON);

            final Vec3 expectedCentre = new Vec3(
                    10.0 + x + 0.5 + face.getStepX() * 0.5,
                    64.0 + y + 0.5 + face.getStepY() * 0.5,
                    -3.0 + z + 0.5 + face.getStepZ() * 0.5);
            assertEquals(expectedCentre.x, surface.faceCenter().x, 1.0e-9, "centre x " + face);
            assertEquals(expectedCentre.y, surface.faceCenter().y, 1.0e-9, "centre y " + face);
            assertEquals(expectedCentre.z, surface.faceCenter().z, 1.0e-9, "centre z " + face);

            assertEquals(face.getStepX(), surface.normal().x, 1.0e-9, "normal x " + face);
            assertEquals(face.getStepY(), surface.normal().y, 1.0e-9, "normal y " + face);
            assertEquals(face.getStepZ(), surface.normal().z, 1.0e-9, "normal z " + face);
            assertEquals(face, surface.face());

            final Vec3 spawn = surface.spawnPoint();
            assertEquals(surface.faceCenter().x + face.getStepX() * EPSILON, spawn.x, 1.0e-9);
            assertEquals(surface.faceCenter().y + face.getStepY() * EPSILON, spawn.y, 1.0e-9);
            assertEquals(surface.faceCenter().z + face.getStepZ() * EPSILON, spawn.z, 1.0e-9);
            assertFalse(isInside(pose, 1.0, x, y, z, spawn), "spawn point is inside " + face);
        }
    }

    @Test
    void actualHitPointAlsoMovesOutward() {
        final Transform3d pose = new Transform3d(new Vector3d(), new Quaterniond());
        final StructureBlockHitResult hit = hit(pose, 2, 3, 4, Direction.WEST, 1.0);
        final StructureEntityPlacementMath.SurfacePoint surface =
                StructureEntityPlacementMath.surfacePoint(hit, EPSILON);

        assertEquals(hit.getLocation().x - EPSILON, surface.hitPoint().x, 1.0e-9);
        assertEquals(hit.getLocation().y, surface.hitPoint().y, 1.0e-9);
        assertEquals(hit.getLocation().z, surface.hitPoint().z, 1.0e-9);
    }

    @Test
    void rotatedAndScaledStructureStillExposesTheClickedFace() {
        final Transform3d pose = new Transform3d(
                new Vector3d(5.5, 70.25, -12.0),
                new Quaterniond().rotateY(Math.PI / 2.0).rotateX(0.35));
        final double scale = 1.7;
        final int x = -2;
        final int y = 5;
        final int z = 3;

        for (Direction face : Direction.values()) {
            final StructureEntityPlacementMath.SurfacePoint surface =
                    StructureEntityPlacementMath.surfacePoint(hit(pose, x, y, z, face, scale), EPSILON);
            final Vector3d local = pose.inverseTransformPoint(new Vector3d(
                    surface.faceCenter().x, surface.faceCenter().y, surface.faceCenter().z))
                    .div(scale);
            final double expectedX = x + 0.5 + face.getStepX() * 0.5;
            final double expectedY = y + 0.5 + face.getStepY() * 0.5;
            final double expectedZ = z + 0.5 + face.getStepZ() * 0.5;
            assertEquals(expectedX, local.x, 1.0e-8, "local centre x " + face);
            assertEquals(expectedY, local.y, 1.0e-8, "local centre y " + face);
            assertEquals(expectedZ, local.z, 1.0e-8, "local centre z " + face);

            // The spawn point must stay on the same face plane, displaced
            // outward by epsilon in world space.
            final Vector3d localSpawn = pose.inverseTransformPoint(new Vector3d(
                    surface.spawnPoint().x, surface.spawnPoint().y, surface.spawnPoint().z))
                    .div(scale);
            assertEquals(EPSILON / scale,
                    localSpawn.sub(new Vector3d(expectedX, expectedY, expectedZ))
                            .dot(new Vector3d(face.getStepX(), face.getStepY(), face.getStepZ())),
                    1.0e-8, "outward offset " + face);
            assertFalse(isInside(pose, scale, x, y, z, surface.spawnPoint()),
                    "rotated/scaled spawn point is inside " + face);
        }
    }

    @Test
    void spawnBlockCellIsTheAdjacentAirCell() {
        final Transform3d pose = new Transform3d(new Vector3d(), new Quaterniond());
        final int x = 2;
        final int y = 3;
        final int z = 4;
        for (Direction face : Direction.values()) {
            final StructureEntityPlacementMath.SurfacePoint surface =
                    StructureEntityPlacementMath.surfacePoint(hit(pose, x, y, z, face, 1.0), EPSILON);
            final BlockPos expected = new BlockPos(
                    x + face.getStepX(),
                    y + face.getStepY(),
                    z + face.getStepZ());
            assertEquals(expected, surface.spawnBlockPos(), "spawn cell " + face);
        }
    }

    @Test
    void itemFrameSitsJustOutsideTheClickedFace() {
        final Transform3d pose = new Transform3d(new Vector3d(), new Quaterniond());
        final StructureEntityPlacementMath.SurfacePoint east =
                StructureEntityPlacementMath.surfacePoint(hit(pose, 2, 3, 4, Direction.EAST, 1.0), EPSILON);
        final StructureEntityPlacementMath.FramePlacement frame =
                StructureEntityPlacementMath.framePlacement(east);

        assertNotNull(frame);
        assertEquals(Direction.EAST, frame.facing());
        assertEquals(east.faceCenter().x + StructureEntityPlacementMath.ITEM_FRAME_DEPTH_OFFSET,
                frame.position().x, 1.0e-9);
        assertEquals(east.faceCenter().y, frame.position().y, 1.0e-9);
        assertEquals(east.faceCenter().z, frame.position().z, 1.0e-9);
        assertEquals(new BlockPos(3, 3, 4), frame.attachedBlockPos());

        final AABB box = frame.boundingBox();
        assertEquals(StructureEntityPlacementMath.ITEM_FRAME_THICKNESS, box.getXsize(), 1.0e-9);
        assertEquals(StructureEntityPlacementMath.ITEM_FRAME_SIZE, box.getYsize(), 1.0e-9);
        assertEquals(StructureEntityPlacementMath.ITEM_FRAME_SIZE, box.getZsize(), 1.0e-9);
        assertEquals(frame.position().x, box.getCenter().x, 1.0e-9);
    }

    @Test
    void itemFrameFacingFollowsTheClickedFaceForTopAndBottom() {
        final Transform3d pose = new Transform3d(new Vector3d(), new Quaterniond());
        for (Direction face : new Direction[]{Direction.UP, Direction.DOWN}) {
            final StructureEntityPlacementMath.SurfacePoint surface =
                    StructureEntityPlacementMath.surfacePoint(hit(pose, 1, 2, 3, face, 1.0), EPSILON);
            final StructureEntityPlacementMath.FramePlacement frame =
                    StructureEntityPlacementMath.framePlacement(surface);

            assertEquals(face, frame.facing());
            assertEquals(StructureEntityPlacementMath.ITEM_FRAME_THICKNESS,
                    frame.boundingBox().getYsize(), 1.0e-9);
            assertEquals(face.getStepY() * StructureEntityPlacementMath.ITEM_FRAME_DEPTH_OFFSET,
                    frame.position().y - surface.faceCenter().y, 1.0e-9);
        }
    }

    @Test
    void clientAndServerAgreeWhichItemsAreHandled() {
        assertTrue(StructureItemUse.shouldHandleStructureUse(Items.STONE),
                "block items keep the existing structure placement path");
        assertTrue(StructureItemUse.shouldHandleStructureUse(Items.COW_SPAWN_EGG),
                "spawn eggs must be consumed before vanilla prediction");
        assertTrue(StructureItemUse.shouldHandleStructureUse(Items.ITEM_FRAME));
        assertTrue(StructureItemUse.shouldHandleStructureUse(Items.GLOW_ITEM_FRAME));
        assertFalse(StructureItemUse.shouldHandleStructureUse(Items.PAINTING),
                "paintings have no structure support search yet");
        assertFalse(StructureItemUse.shouldHandleStructureUse(Items.STICK));

        final Item custom = Items.STICK;
        final StructureEntityPlacementHandler handler =
                context -> StructureEntityPlacementHandler.Result.placed();
        StructureEntityPlacement.register(custom, handler);
        try {
            assertTrue(StructureItemUse.shouldHandleStructureUse(custom),
                    "a registered entity item is handled on both sides");
        } finally {
            StructureEntityPlacement.unregister(custom);
        }
        assertFalse(StructureItemUse.shouldHandleStructureUse(custom));
    }

    private static StructureBlockHitResult hit(Transform3d pose, int x, int y, int z,
                                               Direction face, double scale) {
        final Vec3 centre = StructureEntityPlacementMath.faceCenter(pose, x, y, z, face, scale);
        final Direction worldFace = StructurePlacementMath.worldFace(pose, face);
        return new StructureBlockHitResult(centre, worldFace, BlockPos.containing(centre),
                1L, pose, x, y, z, face, scale,
                Blocks.STONE.defaultBlockState(), 1.0, null);
    }

    private static boolean isInside(Transform3d pose, double scale, int x, int y, int z,
                                    Vec3 worldPoint) {
        final Vector3d local = pose.inverseTransformPoint(
                new Vector3d(worldPoint.x, worldPoint.y, worldPoint.z)).div(scale);
        final double slack = 1.0e-7;
        return local.x > x + slack && local.x < x + 1 - slack
                && local.y > y + slack && local.y < y + 1 - slack
                && local.z > z + slack && local.z < z + 1 - slack;
    }
}
