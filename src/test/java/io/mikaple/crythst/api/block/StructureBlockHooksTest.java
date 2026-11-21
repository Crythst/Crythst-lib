package io.mikaple.crythst.api.block;

import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsProfile;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import io.mikaple.crythst.physics.voxel.VoxelColliderHooks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class StructureBlockHooksTest {
    private static final double EPSILON = 1.0e-9;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearRegistry() {
        StructureBlockHooks.clear();
    }

    @Test
    void blockRegistrationMergesIndividualHooks() {
        final BlockStructureCollisionShape shape = (level, pos, state) -> null;
        final BlockStructureCustomCenterOfMass com =
                (level, pos, state) -> new Vector3d(0.25, 0.5, 0.5);

        StructureBlockHooks.registerCollisionShape(Blocks.STONE, shape);
        StructureBlockHooks.registerCenterOfMass(Blocks.STONE, com);

        final BlockStructureHooks hooks = StructureBlockHooks.hooksFor(
                Blocks.STONE.defaultBlockState());
        assertSame(shape, hooks.collisionShape());
        assertSame(com, hooks.centerOfMass());
    }

    @Test
    void explicitBlockRegistrationOverridesBlockEntityType() {
        final BlockStructureCustomCenterOfMass entityCom =
                (level, pos, state) -> new Vector3d(0.9, 0.5, 0.5);
        final BlockStructureCustomCenterOfMass blockCom =
                (level, pos, state) -> new Vector3d(0.1, 0.5, 0.5);

        StructureBlockHooks.registerCenterOfMass(BlockEntityTypes.CHEST, entityCom);
        StructureBlockHooks.registerCenterOfMass(Blocks.CHEST, blockCom);

        final BlockState chest = Blocks.CHEST.defaultBlockState();
        assertSame(blockCom, StructureBlockHooks.hooksFor(chest).centerOfMass());

        StructureBlockHooks.unregister(Blocks.CHEST);
        assertSame(entityCom, StructureBlockHooks.hooksFor(chest).centerOfMass());

        StructureBlockHooks.unregister(BlockEntityTypes.CHEST);
        assertNull(StructureBlockHooks.hooksFor(chest).centerOfMass());
    }

    @Test
    void dynamicColliderTakesPrecedenceOverShapeAndPropertiesApply() {
        final BlockStructureDynamicCollider dynamic =
                (level, pos, state) -> List.of(new StructureColliderBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0));
        final BlockStructureCollisionShape shape = (level, pos, state) -> null;
        final BlockStructurePhysicsProperties properties =
                (level, pos, state) -> new BlockPhysicsProfile(321.0, 0.6, 0.1, 0.0, 0.0, 1.0);

        StructureBlockHooks.registerDynamicCollider(Blocks.STONE, dynamic);
        StructureBlockHooks.registerCollisionShape(Blocks.STONE, shape);
        StructureBlockHooks.registerPhysicsProperties(Blocks.STONE, properties);

        final BlockState stone = Blocks.STONE.defaultBlockState();
        final VoxelColliderHooks hooks = StructureBlockHooks.colliderHooks(new TestVolume(stone));
        final List<VoxelCellPart> parts = hooks.collisionParts(0, 0, 0, new TestVolume(stone));

        assertNotNull(parts);
        assertEquals(1, parts.size());
        assertEquals(0.5, parts.get(0).sizeY(), EPSILON);
        assertEquals(321.0, hooks.mass(0, 0, 0, new TestVolume(stone)), EPSILON);
        final PhysicsMaterial material = hooks.material(0, 0, 0, new TestVolume(stone));
        assertNotNull(material);
        assertEquals(0.6, material.friction(), EPSILON);
    }

    @Test
    void centerOfMassHookIsExposedToColliderAdapter() {
        final Vector3dc com = new Vector3d(0.2, 0.8, 0.5);
        final BlockStructureCustomCenterOfMass hook = (level, pos, state) -> com;
        StructureBlockHooks.registerCenterOfMass(Blocks.STONE, hook);

        final VoxelColliderHooks hooks = StructureBlockHooks.colliderHooks(
                new TestVolume(Blocks.STONE.defaultBlockState()));
        final Vector3dc result = hooks.centerOfMass(0, 0, 0, new TestVolume(
                Blocks.STONE.defaultBlockState()));

        assertNotNull(result);
        assertEquals(0.2, result.x(), EPSILON);
        assertEquals(0.8, result.y(), EPSILON);
    }

    private record TestVolume(BlockState state) implements StatefulVoxelVolume {
        @Override
        public IntBounds3 bounds() {
            return new IntBounds3(0, 0, 0, 1, 1, 1);
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return true;
        }

        @Override
        public BlockState blockState(int x, int y, int z) {
            return state;
        }
    }
}
