package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePlacementMathTest {
    private static final Transform3d IDENTITY = new Transform3d(
            new Vector3d(10.0, 64.0, -3.0), new Quaterniond());

    @Test
    void identityPoseKeepsLocalFace() {
        for (Direction face : Direction.values()) {
            assertEquals(face, StructurePlacementMath.worldFace(IDENTITY, face),
                    "face " + face);
        }
    }

    @Test
    void rotatedPoseTransformsFaceIntoWorldSpace() {
        final Transform3d yaw90 = new Transform3d(new Vector3d(), new Quaterniond().rotateY(Math.PI / 2.0));

        // Right-handed +90 deg yaw maps local +X to world -Z (north).
        assertEquals(Direction.NORTH, StructurePlacementMath.worldFace(yaw90, Direction.EAST));
        assertEquals(Direction.SOUTH, StructurePlacementMath.worldFace(yaw90, Direction.WEST));
        assertEquals(Direction.UP, StructurePlacementMath.worldFace(yaw90, Direction.UP));
        assertEquals(Direction.DOWN, StructurePlacementMath.worldFace(yaw90, Direction.DOWN));
    }

    @Test
    void syntheticClickStaysInsideTheClickedVoxel() {
        final BlockHitResult east = StructurePlacementMath.blockHit(
                new Vector3d(2.0, 1.3, 0.7), Direction.EAST);

        assertEquals(new BlockPos(1, 1, 0), east.getBlockPos());
        assertEquals(Direction.EAST, east.getDirection());
        assertFalse(east.isInside());
        assertEquals(2.0, east.getLocation().x, 1.0e-9);
        assertEquals(1.3, east.getLocation().y, 1.0e-9);
        assertEquals(0.7, east.getLocation().z, 1.0e-9);
        // Slab/stair vanilla rules compare the click fraction with 0.5.
        assertTrue(east.getLocation().y - east.getBlockPos().getY() < 0.5);

        final BlockHitResult high = StructurePlacementMath.blockHit(
                new Vector3d(2.0, 1.7, 0.7), Direction.EAST);
        assertEquals(new BlockPos(1, 1, 0), high.getBlockPos());
        assertTrue(high.getLocation().y - high.getBlockPos().getY() > 0.5);
    }

    @Test
    void negativeFacesAlsoSelectTheClickedVoxel() {
        final BlockHitResult west = StructurePlacementMath.blockHit(
                new Vector3d(-2.0, 1.3, 0.7), Direction.WEST);
        final BlockHitResult down = StructurePlacementMath.blockHit(
                new Vector3d(1.3, -2.0, 0.7), Direction.DOWN);

        assertEquals(new BlockPos(-2, 1, 0), west.getBlockPos());
        assertEquals(new BlockPos(1, -2, 0), down.getBlockPos());
        assertTrue(down.getLocation().y - down.getBlockPos().getY() < 0.5);
    }

    @Test
    void pillarAxisComesFromTheClickedWorldFace() {
        // RotatedPillarBlock#getStateForPlacement uses getClickedFace().getAxis().
        assertEquals(Direction.Axis.Y, Direction.UP.getAxis());
        assertEquals(Direction.Axis.Y, Direction.DOWN.getAxis());
        assertEquals(Direction.Axis.X, Direction.EAST.getAxis());
        assertEquals(Direction.Axis.Z, Direction.NORTH.getAxis());
    }

    @Test
    void onlyIntegerAlignedIdentityPosesUseTheExactGridShape() {
        assertTrue(StructurePlacementMath.isAxisAlignedGrid(IDENTITY, 1.0));
        assertFalse(StructurePlacementMath.isAxisAlignedGrid(IDENTITY, 1.5),
                "scaled structures need a world-space outline");
        assertFalse(StructurePlacementMath.isAxisAlignedGrid(
                new Transform3d(new Vector3d(0.5, 64.0, 0.0), new Quaterniond()), 1.0),
                "fractional translations are not on the block grid");
        assertFalse(StructurePlacementMath.isAxisAlignedGrid(
                new Transform3d(new Vector3d(), new Quaterniond().rotateY(Math.PI / 2.0)), 1.0),
                "rotated structures use the world-box fallback");
    }

    @Test
    void gridBlockPositionUsesTheVoxelLowerCorner() {
        assertEquals(new BlockPos(12, 64, -3),
                StructurePlacementMath.gridBlockPos(IDENTITY, 2, 0, 0, 1.0));
    }
}
