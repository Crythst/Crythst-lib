package io.mikaple.crythst.api.block;

import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsProfile;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import io.mikaple.crythst.physics.voxel.VoxelColliderHooks;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void automaticSlabShapeBecomesHalfHeightParts() {
        final BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        final VoxelColliderHooks hooks = StructureBlockHooks.colliderHooks(new TestVolume(slab));

        final List<VoxelCellPart> parts = hooks.collisionParts(0, 0, 0, new TestVolume(slab));

        assertNotNull(parts, "a slab must not fall back to a full cube");
        assertEquals(1, parts.size());
        final VoxelCellPart part = parts.get(0);
        assertEquals(0.0, part.minY(), EPSILON);
        assertEquals(0.5, part.sizeY(), EPSILON);
        assertEquals(1.0, part.sizeX(), EPSILON);
        assertEquals(1.0, part.sizeZ(), EPSILON);
    }

    @Test
    void automaticStairShapeBecomesExactParts() {
        final BlockState stairs = Blocks.STONE_STAIRS.defaultBlockState();
        final VoxelColliderHooks hooks = StructureBlockHooks.colliderHooks(new TestVolume(stairs));

        final List<VoxelCellPart> parts = hooks.collisionParts(0, 0, 0, new TestVolume(stairs));

        assertNotNull(parts, "stairs must not fall back to a full cube");
        assertTrue(parts.size() >= 2, "a stair shape normally has a slab and a step");
        boolean hasPartialHeight = false;
        for (VoxelCellPart part : parts) {
            assertTrue(part.minX() >= -EPSILON && part.minX() + part.sizeX() <= 1.0 + EPSILON);
            assertTrue(part.minY() >= -EPSILON && part.minY() + part.sizeY() <= 1.0 + EPSILON);
            assertTrue(part.minZ() >= -EPSILON && part.minZ() + part.sizeZ() <= 1.0 + EPSILON);
            hasPartialHeight |= part.sizeY() < 1.0 - EPSILON;
        }
        assertTrue(hasPartialHeight);
    }

    @Test
    void automaticFencePostKeepsItsThinFootprint() {
        final BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        final SingleCellVolume volume = new SingleCellVolume(fence);
        final VoxelColliderHooks hooks = StructureBlockHooks.colliderHooks(volume);

        final List<VoxelCellPart> parts = hooks.collisionParts(0, 0, 0, volume);

        assertNotNull(parts);
        assertEquals(1, parts.size(), "an unconnected fence is just its post");
        final VoxelCellPart post = parts.get(0);
        assertTrue(post.sizeX() < 0.5 && post.sizeZ() < 0.5,
                "fence collision must be the post, not the whole voxel");
    }

    @Test
    void automaticFullCubeKeepsTheMergedFastPath() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final VoxelColliderHooks hooks = StructureBlockHooks.colliderHooks(new TestVolume(stone));

        assertNull(hooks.collisionParts(0, 0, 0, new TestVolume(stone)),
                "a full cube must return null so the greedy merge stays cheap");
    }

    @Test
    void automaticEmptyShapeKeepsMassButRemovesCollision() {
        final BlockState torch = Blocks.TORCH.defaultBlockState();
        final VoxelColliderHooks hooks = StructureBlockHooks.colliderHooks(new TestVolume(torch));

        final List<VoxelCellPart> parts = hooks.collisionParts(0, 0, 0, new TestVolume(torch));

        assertNotNull(parts, "an empty shape must be an explicit empty part list");
        assertTrue(parts.isEmpty());
    }

    @Test
    void reindexUsesMutableVolumeSideTableStates() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final MutableVoxelVolume volume = VoxelVolumes.filled(
                    new IntBounds3(0, 0, 0, 1, 1, 1), 1000.0, PhysicsMaterial.STONE);
            final PhysicsStructure structure = new PhysicsStructure(world,
                    StructureDef.builder(volume)
                            .type(BodyType.STATIC)
                            .pose(Transform3d.identity())
                            .build());
            try {
                StructureBlockStates.set(structure, 0, 0, 0,
                        Blocks.STONE_SLAB.defaultBlockState());

                structure.reindex(volume);

                assertEquals(1, structure.collider().customParts().size(),
                        "reindex must read the side-table slab state");
                assertEquals(0.5, structure.collider().customParts().get(0).sizeY(), EPSILON);
                assertTrue(structure.collider().boxes().isEmpty(),
                        "the slab cell must not also emit a full cube");
            } finally {
                StructureBlockStates.clear(structure);
                structure.close();
            }
        }
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

    private record SingleCellVolume(BlockState state) implements StatefulVoxelVolume {
        @Override
        public IntBounds3 bounds() {
            return new IntBounds3(0, 0, 0, 1, 1, 1);
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return x == 0 && y == 0 && z == 0;
        }

        @Override
        public BlockState blockState(int x, int y, int z) {
            return isSolid(x, y, z) ? state : Blocks.AIR.defaultBlockState();
        }
    }
}
