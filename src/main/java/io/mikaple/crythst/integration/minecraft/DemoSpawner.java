package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Small self-contained physics demo used by {@code /crythst demo} and optional
 * development joins.
 */
public final class DemoSpawner {
    private DemoSpawner() {
    }

    public static int spawn(LevelPhysics physics, Vec3 player) {
        final CrythstConfig config = MinecraftPhysics.config();

        final IntBounds3 groundBounds = new IntBounds3(0, 0, 0, 9, 1, 9);
        final MutableVoxelVolume ground = VoxelVolumes.filled(groundBounds,
                config.defaultBlockMass, PhysicsMaterial.STONE);
        final PhysicsStructure groundStructure = physics.createStructure(
                StructureDef.builder(ground)
                        .type(BodyType.STATIC)
                        .pose(Transform3d.of(new Vector3d(Math.floor(player.x) - 4.0,
                                Math.floor(player.y) - 1.0, Math.floor(player.z) - 4.0)))
                        .material(PhysicsMaterial.STONE)
                        .build());
        seedBlockStates(groundStructure, groundBounds);

        final IntBounds3 cubeBounds = new IntBounds3(0, 0, 0, 3, 3, 3);
        final MutableVoxelVolume cube = VoxelVolumes.filled(cubeBounds,
                config.defaultBlockMass, PhysicsMaterial.STONE);
        final Quaterniond rotation = new Quaterniond()
                .rotateY(Math.toRadians(20.0))
                .rotateZ(Math.toRadians(12.0));
        final Vector3d center = new Vector3d(player.x, player.y + 6.0, player.z);
        final Vector3d localCenter = new Vector3d(1.5, 1.5, 1.5);
        final Vector3d translation = new Vector3d(center)
                .sub(new Vector3d(localCenter).rotate(rotation));
        final PhysicsStructure cubeStructure = physics.createStructure(
                StructureDef.builder(cube)
                        .colliderOptions(VoxelColliderOptions.DEFAULT.withGreedyMerge(false))
                        .pose(Transform3d.of(translation, rotation))
                        .material(PhysicsMaterial.STONE)
                        .damping(0.02, 0.05)
                        .ccd(true)
                        .build());
        seedBlockStates(cubeStructure, cubeBounds);
        return 2;
    }

    /**
     * Filled volumes have no per-cell block states. Seed the side table so
     * breaking, picking, particles, sounds and outline rendering behave like a
     * normal stone structure.
     */
    private static void seedBlockStates(PhysicsStructure structure, IntBounds3 bounds) {
        final var stone = Blocks.STONE.defaultBlockState();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (structure.volume().isSolid(x, y, z)) {
                        StructureBlockStates.set(structure, x, y, z, stone);
                    }
                }
            }
        }
    }

    /** Removes every structure/body in a level (demo cleanup). */
    public static int clear(LevelPhysics physics) {
        final int structures = physics.structures().size();
        final int bodies = physics.world().bodyCount();
        physics.clearAllBodies();
        return structures + bodies;
    }

    /** @return number of demo structures currently alive, for diagnostics */
    public static int structureCount(LevelPhysics physics) {
        int count = 0;
        for (PhysicsStructure structure : physics.structures()) {
            if (!structure.closed()) {
                count++;
            }
        }
        return count;
    }
}
