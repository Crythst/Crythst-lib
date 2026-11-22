package io.mikaple.crythst.integration.minecraft.entity.attachment;

import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolume;
import io.mikaple.crythst.integration.minecraft.storage.SavedStructureVolume;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAttachedEntitiesTest {
    private static final double EPS = 1.0e-9;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void localAnchorFollowsRotatedAndScaledStructurePose() {
        final Transform3d pose = new Transform3d(
                new Vector3d(30.0, 70.0, -5.0),
                new Quaterniond().rotateY(Math.toRadians(90.0)).rotateX(Math.toRadians(20.0)));
        final Vector3d local = new Vector3d(1.5, 2.0, 0.25);
        final Vector3d expected = pose.transformPoint(local);
        final Vector3d actual = StructureAttachedEntities.worldAnchor(pose, local);
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
    }

    @Test
    void voidPositionUsesRegionLocalOriginAndBlockScale() {
        final EmptyPhysicsLevel.RegionView region = new EmptyPhysicsLevel.RegionView(
                7L, 100, 0, 200, -4, 2, 6, 3, 4, 5);
        final Vector3d local = StructureAttachedEntities.localFromVoid(
                region, 2.5, 102.0, 3.0, 204.0);
        assertEquals((102 - 104) * 2.5, local.x, EPS);
        assertEquals((3 - -2) * 2.5, local.y, EPS);
        assertEquals((204 - 194) * 2.5, local.z, EPS);
    }

    @Test
    void seatClassificationRecognisesNamesAndExplicitTypes() {
        assertTrue(StructureAttachedEntities.looksLikeSeat("seat", "com.example.SeatEntity"));
        assertTrue(StructureAttachedEntities.looksLikeSeat("create:seat", "com.example.Entity"));
        assertFalse(StructureAttachedEntities.looksLikeSeat("item", "com.example.ItemEntity"));
    }

    @Test
    void localAndWorldYawRoundTripAroundStructureYaw() {
        final Transform3d pose = new Transform3d(new Vector3d(),
                new Quaterniond().rotateY(Math.toRadians(37.0)));
        final float local = 25.0f;
        final float world = StructureAttachedEntities.worldYaw(pose, local);
        final float expected = io.mikaple.crythst.integration.minecraft.entity.projection
                .StructureEntityProjection.projectRotation(pose, local, 0.0f).y;
        assertEquals(expected, world, 1.0e-4f);
        assertEquals(local, StructureAttachedEntities.localYaw(pose, world), 1.0e-2f);
    }

    @Test
    void savedVolumeKeepsStatesForNonSolidLogicBlocks() {
        final IntBounds3 bounds = new IntBounds3(0, 0, 0, 2, 1, 1);
        final BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        final StatefulVoxelVolume volume = new StatefulVoxelVolume() {
            @Override
            public IntBounds3 bounds() {
                return bounds;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return x == 0;
            }

            @Override
            public double blockMass(int x, int y, int z) {
                return isSolid(x, y, z) ? 1000.0 : 0.0;
            }

            @Override
            public PhysicsMaterial blockMaterial(int x, int y, int z) {
                return PhysicsMaterial.STONE;
            }

            @Override
            public BlockState blockState(int x, int y, int z) {
                return x == 1 ? wire : null;
            }
        };

        final SavedStructureVolume saved = SavedStructureVolume.fromVolume(volume);
        assertTrue(saved.hasBlockStates(), "non-solid logic states must be preserved");
        assertEquals(wire, saved.blockState(1, 0, 0));
        assertFalse(saved.isSolid(1, 0, 0));
        assertNotNull(saved.toTag());

        final RestoredStructureVolume restored = new RestoredStructureVolume(saved);
        assertEquals(wire, restored.blockState(1, 0, 0),
                "restored volumes must expose non-solid logic states to the void copy");
        assertFalse(restored.isSolid(1, 0, 0));
    }
}
