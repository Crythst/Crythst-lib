package io.mikaple.crythst.integration.minecraft.operation;

import io.mikaple.crythst.integration.minecraft.interaction.StructurePlacementMath;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureOperationRedirectorTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void auxiliaryHitPreservesLocalFaceAndFraction() {
        final Transform3d pose = new Transform3d(
                new Vector3d(20.0, 64.0, -7.0),
                new Quaterniond().rotateY(0.4).rotateX(-0.15));
        final double scale = 2.0;
        final int voxelX = 3;
        final int voxelY = 4;
        final int voxelZ = 5;
        final Direction localFace = Direction.EAST;

        // Centre of the east face in structure-local metres.
        final Vector3d faceCentre = pose.transformPoint(new Vector3d(
                (voxelX + 1.0) * scale,
                (voxelY + 0.5) * scale,
                (voxelZ + 0.5) * scale));
        final Vec3 worldHit = new Vec3(faceCentre.x, faceCentre.y, faceCentre.z);
        final Direction worldFace = StructurePlacementMath.worldFace(pose, localFace);
        final BlockPos realPos = BlockPos.containing(worldHit);
        final StructureBlockHitResult hit = new StructureBlockHitResult(
                worldHit, worldFace, realPos, 1L, pose,
                voxelX, voxelY, voxelZ, localFace, scale,
                Blocks.STONE.defaultBlockState(), 4.0, null);

        final BlockPos auxiliaryPos = new BlockPos(100, 0, 200);
        final BlockHitResult auxiliary = StructureOperationRedirector.auxiliaryHit(hit, auxiliaryPos);

        assertEquals(auxiliaryPos, auxiliary.getBlockPos());
        assertEquals(localFace, auxiliary.getDirection());
        assertFalse(auxiliary.isInside());
        // Hit is on the east face of voxel (3,4,5): local fraction is
        // (1.0, 0.5, 0.5), which is aux (101.0, 0.5, 200.5).
        assertEquals(101.0, auxiliary.getLocation().x, 1.0e-7);
        assertEquals(0.5, auxiliary.getLocation().y, 1.0e-7);
        assertEquals(200.5, auxiliary.getLocation().z, 1.0e-7);
    }

    @Test
    void redirectIsEnabledByDefault() {
        assertTrue(new io.mikaple.crythst.config.CrythstConfig().useVoidLevelOperations);
    }

    @Test
    void routedResultsConsumeWithoutLegacyFallback() {
        assertFalse(StructureOperationRedirector.shouldFallback(
                StructureOperationRedirector.Status.HANDLED));
        assertFalse(StructureOperationRedirector.shouldFallback(
                StructureOperationRedirector.Status.FAILED));
    }

    @Test
    void unavailableAndDisabledResultsFallBack() {
        assertTrue(StructureOperationRedirector.shouldFallback(
                StructureOperationRedirector.Status.DISABLED));
        assertTrue(StructureOperationRedirector.shouldFallback(
                StructureOperationRedirector.Status.NOT_HANDLED),
                "an unloaded auxiliary chunk must use the custom edit");
    }

    @Test
    void outOfBoundsPlacementFallsBackToVolumeExpansion() {
        assertTrue(StructureOperationRedirector.shouldFallback(
                StructureOperationRedirector.Status.OUT_OF_BOUNDS));
    }

    @Test
    void auxiliaryHitClampsNumericalFractions() {
        final Transform3d pose = Transform3d.identity();
        final Vec3 worldHit = new Vec3(3.999999999, 4.000000001, 5.5);
        final StructureBlockHitResult hit = new StructureBlockHitResult(
                worldHit, Direction.EAST, BlockPos.containing(worldHit), 1L, pose,
                3, 4, 5, Direction.EAST, 1.0,
                Blocks.STONE.defaultBlockState(), 1.0, null);

        final BlockHitResult auxiliary = StructureOperationRedirector.auxiliaryHit(
                hit, BlockPos.ZERO);
        assertTrue(auxiliary.getLocation().x >= 0.0 && auxiliary.getLocation().x <= 1.0);
        assertTrue(auxiliary.getLocation().y >= 0.0 && auxiliary.getLocation().y <= 1.0);
        assertTrue(auxiliary.getLocation().z >= 0.0 && auxiliary.getLocation().z <= 1.0);
    }
}
