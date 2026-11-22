package io.mikaple.crythst.integration.minecraft.gameevent;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.EditableStatefulVolume;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolume;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.mixin.explosion.ServerExplosionAccessor;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Bridges vanilla explosions and physics-structure voxels.
 *
 * <p>There are exactly two modes, selected from the level that owns the
 * explosion:</p>
 * <ul>
 *   <li><b>Host mode</b> — a real-world TNT/creeper/bed explosion. Vanilla has
 *       already handled the host block grid. This bridge runs the same
 *       resistance-aware ray walk in each structure's local space, removes the
 *       selected voxels, drops their loot and applies a radial impulse to the
 *       body.</li>
 *   <li><b>Void mode</b> — a block/explosion in the auxiliary copied level.
 *       Vanilla has already edited the copy. This bridge does <em>not</em>
 *       remove blocks again; it uses the captured vanilla target list for
 *       impulses, mirrors the copy through the normal edit pass and re-emits
 *       the entity part of the explosion in the host world so host
 *       players/mobs are damaged.</li>
 * </ul>
 *
 * <p>The mode split is the host+void deduplication: the void pass never edits
 * structure voxels and the host pass never looks at the auxiliary copy. A weak
 * identity set also prevents a second bridge pass if the same
 * {@link ServerExplosion} object is replayed.</p>
 */
public final class StructureExplosions {
    private static final Map<Explosion, List<BlockPos>> VOID_BLOCK_TARGETS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Explosion, Boolean> HANDLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StructureExplosions() {
    }

    /**
     * Captures the vanilla destroy list for an auxiliary explosion before
     * {@code ServerExplosion#interactWithBlocks} removes the copied blocks.
     * The list is used only for the impulse pass.
     */
    public static void captureVoidBlockTargets(ServerExplosion explosion,
                                               @Nullable List<BlockPos> targetBlocks) {
        if (explosion == null || targetBlocks == null || targetBlocks.isEmpty()) {
            return;
        }
        if (EmptyPhysicsLevel.ofLevel(explosion.level()) == null) {
            return;
        }
        if (!destroysBlocks(explosion.getBlockInteraction())) {
            return;
        }
        VOID_BLOCK_TARGETS.put(explosion, List.copyOf(targetBlocks));
    }

    /**
     * Runs after vanilla's block/entity pass for one explosion.
     */
    public static void afterVanillaExplosion(@Nullable ServerExplosion explosion) {
        if (explosion == null) {
            return;
        }
        if (HANDLED.put(explosion, Boolean.TRUE) != null) {
            VOID_BLOCK_TARGETS.remove(explosion);
            return;
        }
        try {
            final EmptyPhysicsLevel auxiliary = EmptyPhysicsLevel.ofLevel(explosion.level());
            if (auxiliary != null) {
                handleVoidExplosion(explosion, auxiliary);
            } else {
                handleHostExplosion(explosion);
            }
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Structure explosion bridge failed for dimension {}",
                    explosion.level().dimension().identifier(), t);
        } finally {
            VOID_BLOCK_TARGETS.remove(explosion);
        }
    }

    private static void handleHostExplosion(ServerExplosion explosion) {
        if (!destroysBlocks(explosion.getBlockInteraction())) {
            return;
        }
        final ServerLevel level = explosion.level();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics == null) {
            return;
        }
        final ExplosionDamageCalculator calculator = calculator(explosion);
        final Vec3 center = explosion.center();
        final Vector3d centerVector = new Vector3d(center.x, center.y, center.z);
        for (PhysicsStructure structure : List.copyOf(physics.structures())) {
            if (structure.closed()) {
                continue;
            }
            // Conservative sphere/AABB reject before running 1,352 rays.
            if (!structure.aabb().expand(explosion.radius() + structure.blockScale())
                    .contains(centerVector)) {
                continue;
            }
            final List<ExplosionBlast.Hit> hits =
                    traceStructure(explosion, calculator, physics, structure, centerVector);
            if (hits.isEmpty()) {
                continue;
            }
            // Impulses must be applied before reindexing/removal: a split copy
            // inherits the original body's linear/angular velocity.
            applyHostImpulses(structure, hits, centerVector, explosion.radius());
            dropHostLoot(level, explosion, structure, hits);
            removeHostVoxels(physics, structure, hits);
        }
    }

    private static List<ExplosionBlast.Hit> traceStructure(
            ServerExplosion explosion, ExplosionDamageCalculator calculator,
            LevelPhysics physics, PhysicsStructure structure, Vector3d center) {
        final Transform3d pose = structure.pose();
        final double scale = structure.blockScale() > 1.0e-9
                ? structure.blockScale() : 1.0;
        final Vector3d localCenter = pose.inverseTransformPoint(center);
        final Map<ExplosionBlast.Cell, BlockState> states = new HashMap<>();
        final ExplosionBlast.Probe probe = new ExplosionBlast.Probe() {
            @Override
            public @Nullable ExplosionBlast.Cell cellAt(double x, double y, double z) {
                final ExplosionBlast.Cell cell = new ExplosionBlast.Cell(
                        (int) Math.floor(x / scale),
                        (int) Math.floor(y / scale),
                        (int) Math.floor(z / scale));
                if (states.containsKey(cell)) {
                    return states.get(cell) == null ? null : cell;
                }
                final BlockState state = stateAt(physics, structure,
                        cell.x(), cell.y(), cell.z());
                states.put(cell, state);
                return state == null ? null : cell;
            }

            @Override
            public double blastResistance(ExplosionBlast.Cell cell) {
                final BlockState state = states.get(cell);
                return state == null ? 0.0 : explosionResistance(state);
            }

            @Override
            public boolean shouldDestroy(ExplosionBlast.Cell cell, float power) {
                final BlockState state = states.get(cell);
                if (state == null) {
                    return false;
                }
                return calculator.shouldBlockExplode(explosion, explosion.level(),
                        hostBlockPos(structure, cell.x(), cell.y(), cell.z()),
                        state, power);
            }
        };
        return ExplosionBlast.trace(explosion.radius(), localCenter,
                () -> explosion.level().getRandom().nextFloat(), probe);
    }

    private static void applyHostImpulses(PhysicsStructure structure,
                                          List<ExplosionBlast.Hit> hits, Vector3d center,
                                          double radius) {
        final VoxelVolume volume = structure.volume();
        final double effectiveRadius = Math.max(1.0e-6, radius);
        for (ExplosionBlast.Hit hit : hits) {
            final ExplosionBlast.Cell cell = hit.cell();
            final double mass = volume.blockMass(cell.x(), cell.y(), cell.z());
            if (mass <= 0.0) {
                continue;
            }
            final Vector3d blockCenter = structure.blockCenter(
                    cell.x(), cell.y(), cell.z());
            final Vector3d impulse = ExplosionImpulse.impulse(center, blockCenter,
                    mass * Math.max(1.0, structure.massScale()),
                    hit.power(), effectiveRadius);
            if (impulse.lengthSquared() > 0.0) {
                structure.applyImpulseAt(impulse, blockCenter);
            }
        }
    }

    private static void dropHostLoot(ServerLevel level, ServerExplosion explosion,
                                     PhysicsStructure structure,
                                     List<ExplosionBlast.Hit> hits) {
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        final Entity source = validHostSource(explosion.getDirectSourceEntity(), level);
        final boolean dropExperience = explosion.getIndirectSourceEntity() instanceof Player;
        for (ExplosionBlast.Hit hit : hits) {
            final ExplosionBlast.Cell cell = hit.cell();
            final BlockState state = stateAt(physics, structure,
                    cell.x(), cell.y(), cell.z());
            if (state == null || state.isAir()) {
                continue;
            }
            final BlockPos hostPos = hostBlockPos(structure,
                    cell.x(), cell.y(), cell.z());
            final Block block = state.getBlock();
            if (block.dropFromExplosion(explosion)) {
                for (ItemStack drop : Block.getDrops(state, level, hostPos,
                        null, source, ItemStack.EMPTY)) {
                    Block.popResource(level, hostPos, drop);
                }
                state.spawnAfterBreak(level, hostPos, ItemStack.EMPTY, dropExperience);
            }
            // TNT and similar blocks react to being destroyed by an explosion
            // (TNT spawns a primed host entity here, giving a chain reaction).
            block.wasExploded(level, hostPos, explosion);
        }
    }

    private static void removeHostVoxels(LevelPhysics physics, PhysicsStructure structure,
                                         List<ExplosionBlast.Hit> hits) {
        final EmptyPhysicsLevel auxiliary = physics.auxiliaryLevel();
        final EmptyPhysicsLevel.RegionView region =
                auxiliary == null ? null : auxiliary.regionView(structure);
        if (region != null) {
            // Remove from the live copy first. The normal edit pass then
            // mirrors states, drops stale block-entity NBT and performs
            // split/massless removal exactly like any other block edit.
            final List<BlockPos> localVoxels = new ArrayList<>(hits.size());
            for (ExplosionBlast.Hit hit : hits) {
                localVoxels.add(new BlockPos(hit.cell().x(), hit.cell().y(), hit.cell().z()));
            }
            auxiliary.removeBlocksForExplosion(structure, localVoxels);
            physics.flushAuxiliaryBlockStates();
            return;
        }
        removeHostVoxelsDirect(physics, structure, hits);
    }

    /**
     * Fallback for structures without a live auxiliary region (void level
     * disabled or region unloaded). The volume is edited in place and
     * reindexed once; no block-entity NBT can be captured from a missing
     * region, so stale entries are removed from the side table here.
     */
    private static void removeHostVoxelsDirect(LevelPhysics physics, PhysicsStructure structure,
                                               List<ExplosionBlast.Hit> hits) {
        final VoxelVolume volume = structure.volume();
        final boolean editable = volume instanceof MutableVoxelVolume
                || volume instanceof RestoredStructureVolume
                || volume instanceof EditableStatefulVolume;
        if (!editable) {
            return;
        }
        final IntBounds3 bounds = volume.bounds();
        final Map<BlockPos, CompoundTag> blockEntities =
                new LinkedHashMap<>(StructureBlockEntityData.get(structure));
        for (ExplosionBlast.Hit hit : hits) {
            final ExplosionBlast.Cell cell = hit.cell();
            if (volume instanceof RestoredStructureVolume restored) {
                restored.setBlock(cell.x(), cell.y(), cell.z(), false, 0.0,
                        PhysicsMaterial.DEFAULT, null);
            } else if (volume instanceof EditableStatefulVolume stateful) {
                stateful.setBlock(cell.x(), cell.y(), cell.z(), false, 0.0,
                        PhysicsMaterial.DEFAULT, null);
            } else if (volume instanceof MutableVoxelVolume mutable) {
                mutable.setSolid(cell.x(), cell.y(), cell.z(), false);
            }
            StructureBlockStates.remove(structure, cell.x(), cell.y(), cell.z());
            blockEntities.remove(new BlockPos(
                    cell.x() - bounds.minX(),
                    cell.y() - bounds.minY(),
                    cell.z() - bounds.minZ()));
        }
        if (!blockEntities.isEmpty()) {
            StructureBlockEntityData.put(structure, blockEntities);
        } else {
            StructureBlockEntityData.remove(structure);
        }
        try {
            if (StructureSplitter.isMassless(volume)) {
                StructureSplitter.removeIfMassless(physics, structure, volume);
            } else {
                structure.reindex(volume);
                physics.refreshStructure(structure, volume);
            }
        } catch (RuntimeException e) {
            Crythst.LOGGER.warn("Could not apply explosion edits to structure body #{}",
                    structure.body().id(), e);
        }
    }

    private static void handleVoidExplosion(ServerExplosion explosion,
                                            EmptyPhysicsLevel auxiliary) {
        final Vec3 center = explosion.center();
        // A blast centred just outside the copied bounds can still reach the
        // structure, so allow a radius-sized fallback to the nearest region.
        final Vec3 mappedCenter = auxiliary.projectAuxiliaryPointNear(
                center.x, center.y, center.z,
                Math.max(1.0, explosion.radius()) + 1.0);
        if (mappedCenter == null) {
            // The explosion happened outside every copied structure region.
            return;
        }
        final ServerLevel host = auxiliary.hostLevel();
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(host);
        final List<BlockPos> targets = VOID_BLOCK_TARGETS.get(explosion);
        if (targets != null && physics != null) {
            applyVoidImpulses(physics, auxiliary, targets, mappedCenter,
                    explosion.radius());
            // Mirror the already-destroyed copy now, so split/removal and host
            // collision state are correct before the next tick.
            physics.flushAuxiliaryBlockStates();
        }
        // Vanilla only damaged entities inside the player-less void copy.
        // Re-run the entity-only part in the host world. NONE keeps the block
        // interaction from destroying host terrain or re-running the structure
        // pass (the host bridge sees KEEP and skips).
        final Entity source = validHostSource(explosion.getDirectSourceEntity(), host);
        host.explode(source, mappedCenter.x, mappedCenter.y, mappedCenter.z,
                explosion.radius(), Level.ExplosionInteraction.NONE);
    }

    private static void applyVoidImpulses(LevelPhysics physics, EmptyPhysicsLevel auxiliary,
                                          List<BlockPos> targets, Vec3 mappedCenter,
                                          double radius) {
        final Set<StructureVoxel> claimed = new HashSet<>();
        final Vector3d center = new Vector3d(mappedCenter.x, mappedCenter.y, mappedCenter.z);
        for (BlockPos target : targets) {
            final EmptyPhysicsLevel.RegionView region = auxiliary.regionViewAt(target);
            if (region == null) {
                continue;
            }
            final BlockPos local = region.toLocalVoxel(target);
            if (local == null) {
                continue;
            }
            final PhysicsStructure structure = structureById(physics, region.structureId());
            if (structure == null || structure.closed()) {
                continue;
            }
            if (!claimed.add(new StructureVoxel(structure.body().id(),
                    local.getX(), local.getY(), local.getZ()))) {
                continue;
            }
            final double mass = structure.volume().blockMass(
                    local.getX(), local.getY(), local.getZ());
            if (mass <= 0.0) {
                continue;
            }
            final Vector3d blockCenter = structure.blockCenter(
                    local.getX(), local.getY(), local.getZ());
            final float power = ExplosionImpulse.approximatePower(
                    radius, Math.sqrt(blockCenter.distanceSquared(center)));
            final Vector3d impulse = ExplosionImpulse.impulse(center, blockCenter,
                    mass * Math.max(1.0, structure.massScale()), power, radius);
            if (impulse.lengthSquared() > 0.0) {
                structure.applyImpulseAt(impulse, blockCenter);
            }
        }
    }

    private static @Nullable PhysicsStructure structureById(LevelPhysics physics, long bodyId) {
        for (PhysicsStructure structure : physics.structures()) {
            if (!structure.closed() && structure.body().id() == bodyId) {
                return structure;
            }
        }
        return null;
    }

    private static @Nullable BlockState stateAt(LevelPhysics physics,
                                                PhysicsStructure structure,
                                                int x, int y, int z) {
        if (physics != null) {
            final EmptyPhysicsLevel.LiveBlock live =
                    physics.liveBlock(structure, x, y, z);
            // A live air block is authoritative: the volume side table may
            // still hold the previous state until the mirror pass runs.
            if (live != null) {
                return live.state().isAir() ? null : live.state();
            }
        }
        final VoxelVolume volume = structure.volume();
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(x, y, z);
            if (state != null && !state.isAir()) {
                return state;
            }
        }
        final BlockState stored = StructureBlockStates.get(structure, x, y, z);
        if (stored != null && !stored.isAir()) {
            return stored;
        }
        return volume.bounds().contains(x, y, z) && volume.isSolid(x, y, z)
                ? Blocks.STONE.defaultBlockState() : null;
    }

    private static double explosionResistance(BlockState state) {
        return Math.max(state.getBlock().getExplosionResistance(),
                state.getFluidState().getExplosionResistance());
    }

    private static BlockPos hostBlockPos(PhysicsStructure structure, int x, int y, int z) {
        final Vector3d center = structure.blockCenter(x, y, z);
        return BlockPos.containing(center.x, center.y, center.z);
    }

    private static boolean destroysBlocks(Explosion.BlockInteraction interaction) {
        return interaction == Explosion.BlockInteraction.DESTROY
                || interaction == Explosion.BlockInteraction.DESTROY_WITH_DECAY;
    }

    private static ExplosionDamageCalculator calculator(ServerExplosion explosion) {
        if (explosion instanceof ServerExplosionAccessor accessor) {
            final ExplosionDamageCalculator calculator = accessor.crythst$damageCalculator();
            if (calculator != null) {
                return calculator;
            }
        }
        return new ExplosionDamageCalculator();
    }

    private static @Nullable Entity validHostSource(@Nullable Entity source, ServerLevel host) {
        return source != null && !source.isRemoved() && source.level() == host
                ? source : null;
    }

    private record StructureVoxel(long bodyId, int x, int y, int z) {
    }
}
