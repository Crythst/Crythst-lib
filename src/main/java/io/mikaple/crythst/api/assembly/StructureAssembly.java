package io.mikaple.crythst.api.assembly;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Moves blocks between the real Minecraft world and a Crythst world-space
 * structure.
 *
 * <p><strong>This is not Sable plot or sub-level assembly.</strong> Crythst
 * structures are rigid bodies whose voxel grid is mapped into world space by a
 * pose; there is no distant plot, plot-local chunk holder or second
 * {@code Level} that owns the blocks. Assembly captures an axis-aligned group of
 * real world positions into a dense voxel volume, creates a
 * {@link PhysicsStructure}, and then removes the source blocks. Disassembly
 * walks the current structure pose and puts each solid voxel back into the real
 * world.</p>
 *
 * <p>Safety contract:</p>
 * <ul>
 *   <li>Air, fluids and blocks with no collision shape are skipped. They stay in
 *       the world and are not part of the structure.</li>
 *   <li>The world is not modified until the volume and rigid body have both
 *       been created successfully. If anything fails during capture, body
 *       creation or block removal, every block removed by this call is restored
 *       and the body is closed.</li>
 *   <li>Removal uses vanilla update-suppression flags, not
 *       {@code destroyBlock}, so assembling does not drop item entities.</li>
 *   <li>Block-entity NBT is captured before removal into
 *       {@link StructureBlockEntityData} and reattached to the auxiliary ticking
 *       level. Keys are structure-local voxels relative to the volume minimum,
 *       matching persistence and splitting.</li>
 *   <li>At most {@link StructureGather#MAX_BLOCKS} blocks (1,000,000) are
 *       accepted.</li>
 * </ul>
 *
 * <p>Entities are not transferred into a structure: the Crythst structure model
 * has no entity container. {@link AssemblyOptions#keepEntities()} leaves
 * entities untouched; it never deletes them.</p>
 */
public final class StructureAssembly {
    private static final int REMOVE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    private static final int RESTORE_SUPPRESSED_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    private static final int RESTORE_NORMAL_FLAGS = Block.UPDATE_ALL;

    private StructureAssembly() {
    }

    /**
     * Assembles a set of world blocks into a new physics structure.
     *
     * <p>{@code blocks} may contain duplicates; they are removed. Positions are
     * captured in deterministic {@code x,y,z} order. The anchor is the world
     * block corner used for the pose described by {@link AssemblyOptions#pose()}.
     * With the default options the captured minimum block ends up exactly at its
     * original world position and the structure has one metre per voxel.</p>
     *
     * @param level   server level containing the blocks
     * @param anchor  world block corner used as the pose anchor
     * @param blocks  candidate world positions; unsupported entries are skipped
     * @param options assembly options
     * @return the created structure
     * @throws IllegalArgumentException when no block can be assembled
     * @throws IllegalStateException    when the body or world edit fails
     */
    public static PhysicsStructure assemble(ServerLevel level, BlockPos anchor,
                                            Iterable<BlockPos> blocks,
                                            AssemblyOptions options) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(blocks, "blocks");
        final AssemblyOptions effective = options == null ? AssemblyOptions.defaults() : options;
        final LevelPhysics physics = MinecraftPhysics.get(level);

        final BuiltVolume built = buildVolume(blocks,
                pos -> snapshot(level, pos, effective));
        final Transform3d pose = structurePose(anchor, built.origin(), effective);
        final VoxelColliderOptions colliderOptions =
                VoxelColliderOptions.DEFAULT.withBlockScale(effective.blockScale());
        final StructureDef definition = StructureDef.builder(built.volume())
                .pose(pose)
                .type(effective.type())
                .material(effective.material())
                .massScale(effective.massScale())
                .colliderOptions(colliderOptions)
                .build();

        PhysicsStructure structure = null;
        final List<BlockPos> removed = new ArrayList<>();
        try {
            structure = physics.createStructure(definition);
            if (effective.copyBlockEntities() && !built.blockEntities().isEmpty()) {
                StructureBlockEntityData.put(structure, built.blockEntities());
                physics.applyBlockEntities(structure, built.blockEntities());
            }
            if (effective.removeFromWorld()) {
                removeCapturedBlocks(level, built, removed);
                physics.invalidateTerrain();
            }
            return structure;
        } catch (RuntimeException | Error failure) {
            rollback(level, physics, structure, built, removed);
            throw failure;
        }
    }

    /**
     * Places a live structure's solid voxels back into the real world at the
     * positions described by its current pose.
     *
     * <p>Voxel coordinates are used unshifted, exactly as they are stored in the
     * volume; {@link AssemblyTransform} applies the pose and block scale. A
     * destination outside the build height or occupied by a non-replaceable
     * block is skipped, and optionally dropped as an item.</p>
     *
     * @param level     server level to write to
     * @param structure structure to disassemble
     * @param options   disassembly options
     */
    public static void disassemble(ServerLevel level, PhysicsStructure structure,
                                   DisassembleOptions options) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(structure, "structure");
        final DisassembleOptions effective =
                options == null ? DisassembleOptions.defaults() : options;
        if (structure.closed()) {
            return;
        }

        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        final VoxelVolume volume = structure.volume();
        final IntBounds3 bounds = volume.bounds();
        final AssemblyTransform transform =
                new AssemblyTransform(structure.pose(), structure.blockScale());
        final Map<BlockPos, CompoundTag> blockEntities = effective.restoreBlockEntities()
                ? StructureBlockEntityData.get(structure) : Map.of();
        final int flags = effective.suppressNeighborUpdates()
                ? RESTORE_SUPPRESSED_FLAGS : RESTORE_NORMAL_FLAGS;

        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!volume.isSolid(x, y, z)) {
                        continue;
                    }
                    final BlockState state = stateAt(structure, volume, x, y, z);
                    if (state == null) {
                        continue;
                    }
                    final BlockPos worldPos = transform.voxelToWorldBlock(x, y, z);
                    if (level.isOutsideBuildHeight(worldPos)
                            || !level.getBlockState(worldPos).canBeReplaced()) {
                        if (effective.dropBlocksThatDoNotFit()) {
                            dropBlock(level, worldPos, state);
                        }
                        continue;
                    }
                    if (!level.setBlock(worldPos, state, flags)) {
                        if (effective.dropBlocksThatDoNotFit()) {
                            dropBlock(level, worldPos, state);
                        }
                        continue;
                    }
                    if (effective.restoreBlockEntities() && state.hasBlockEntity()) {
                        final BlockPos local = new BlockPos(
                                x - bounds.minX(), y - bounds.minY(), z - bounds.minZ());
                        restoreBlockEntity(level, worldPos, state, blockEntities.get(local));
                    }
                }
            }
        }

        if (physics != null) {
            physics.invalidateTerrain();
        }
        if (effective.removeStructure()) {
            if (physics != null) {
                physics.removeStructure(structure);
            } else {
                structure.close();
            }
            StructureBlockEntityData.remove(structure);
            StructureBlockStates.clear(structure);
        }
    }

    // ------------------------------------------------------------------
    // Pure-ish volume construction and coordinate helpers
    // ------------------------------------------------------------------

    /**
     * One block captured from the world.
     */
    record BlockSnapshot(BlockState state, double mass, PhysicsMaterial material,
                         @Nullable CompoundTag blockEntity) {
        BlockSnapshot {
            Objects.requireNonNull(state, "state");
            if (state.isAir()) {
                throw new IllegalArgumentException("Cannot capture air");
            }
            mass = mass > 0.0 && Double.isFinite(mass) ? mass : 1.0;
            material = material == null ? PhysicsMaterial.DEFAULT : material;
            blockEntity = blockEntity == null ? null : blockEntity.copy();
        }
    }

    /** Block lookup used by the pure volume builder and by tests. */
    @FunctionalInterface
    interface BlockSource {
        @Nullable BlockSnapshot snapshot(BlockPos pos);
    }

    /**
     * Dense 0-based volume plus the original world positions and block-entity
     * data captured for it.
     */
    static final class BuiltVolume {
        private final AssemblyVolume volume;
        private final BlockPos origin;
        private final List<BlockPos> worldBlocks;
        private final Map<BlockPos, CompoundTag> blockEntities;

        private BuiltVolume(AssemblyVolume volume, BlockPos origin,
                            List<BlockPos> worldBlocks,
                            Map<BlockPos, CompoundTag> blockEntities) {
            this.volume = volume;
            this.origin = origin;
            this.worldBlocks = List.copyOf(worldBlocks);
            this.blockEntities = Collections.unmodifiableMap(new LinkedHashMap<>(blockEntities));
        }

        AssemblyVolume volume() {
            return volume;
        }

        BlockPos origin() {
            return origin;
        }

        List<BlockPos> worldBlocks() {
            return worldBlocks;
        }

        Map<BlockPos, CompoundTag> blockEntities() {
            return blockEntities;
        }
    }

    static BuiltVolume buildVolume(Iterable<BlockPos> blocks, BlockSource source) {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(source, "source");

        final TreeSet<BlockPos> positions = new TreeSet<>(StructureGather.POSITION_ORDER);
        for (BlockPos pos : blocks) {
            if (pos == null) {
                continue;
            }
            positions.add(pos.immutable());
            if (positions.size() > StructureGather.MAX_BLOCKS) {
                throw new IllegalArgumentException("Block set exceeds the "
                        + StructureGather.MAX_BLOCKS + " block assembly limit");
            }
        }

        final List<BlockPos> included = new ArrayList<>();
        final Map<BlockPos, BlockSnapshot> snapshots = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            final BlockSnapshot snapshot = source.snapshot(pos);
            if (snapshot == null || snapshot.state() == null || snapshot.state().isAir()) {
                continue;
            }
            included.add(pos);
            snapshots.put(pos, snapshot);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (included.isEmpty()) {
            throw new IllegalArgumentException(
                    "No assemblable blocks: every position was air, fluid or unsupported");
        }

        final IntBounds3 bounds = new IntBounds3(0, 0, 0,
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        final AssemblyVolume volume = new AssemblyVolume(bounds);
        final Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        for (BlockPos pos : included) {
            final BlockSnapshot snapshot = snapshots.get(pos);
            final int x = pos.getX() - minX;
            final int y = pos.getY() - minY;
            final int z = pos.getZ() - minZ;
            volume.setBlock(x, y, z, snapshot.mass(), snapshot.material(), snapshot.state());
            if (snapshot.blockEntity() != null) {
                blockEntities.put(new BlockPos(x, y, z), snapshot.blockEntity().copy());
            }
        }
        return new BuiltVolume(volume, new BlockPos(minX, minY, minZ),
                included, blockEntities);
    }

    static Transform3d structurePose(BlockPos anchor, BlockPos origin, AssemblyOptions options) {
        final Vector3d anchorVec = vector(anchor);
        final Vector3d originOffset = vector(origin).sub(anchorVec);
        return Transform3d.of(anchorVec)
                .compose(options.pose())
                .compose(Transform3d.of(originOffset));
    }

    // ------------------------------------------------------------------
    // World capture and restore
    // ------------------------------------------------------------------

    static boolean isAssemblable(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // Fluids, torches and flowers have no collision shape. Full blocks take
        // the fast path; the precise shape handles stairs, slabs and modded
        // partial blocks.
        return state.isCollisionShapeFullBlock(level, pos)
                || !state.getCollisionShape(level, pos).isEmpty();
    }

    private static @Nullable BlockSnapshot snapshot(ServerLevel level, BlockPos pos,
                                                    AssemblyOptions options) {
        if (!isAssemblable(level, pos)) {
            return null;
        }
        final BlockState state = level.getBlockState(pos);
        final BlockPhysicsMapper.BlockProperties properties =
                MinecraftPhysics.blockPhysics().properties(state);
        final double mass = properties.mass() > 0.0 && Double.isFinite(properties.mass())
                ? properties.mass()
                : Math.max(1.0, MinecraftPhysics.config().defaultBlockMass);
        CompoundTag blockEntity = null;
        if (options.copyBlockEntities() && state.hasBlockEntity()) {
            final BlockEntity existing = level.getBlockEntity(pos);
            if (existing != null) {
                try {
                    blockEntity = existing.saveWithFullMetadata(level.registryAccess());
                } catch (RuntimeException failure) {
                    throw new IllegalStateException(
                            "Could not capture block entity at " + pos, failure);
                }
            }
        }
        return new BlockSnapshot(state, mass, properties.material(), blockEntity);
    }

    private static void removeCapturedBlocks(ServerLevel level, BuiltVolume built,
                                             List<BlockPos> removed) {
        final BlockPos origin = built.origin();
        for (BlockPos pos : built.worldBlocks()) {
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            final int localX = pos.getX() - origin.getX();
            final int localY = pos.getY() - origin.getY();
            final int localZ = pos.getZ() - origin.getZ();
            final BlockState expected = built.volume().blockState(localX, localY, localZ);
            if (expected == null || expected.isAir()) {
                continue;
            }
            final BlockState current = level.getBlockState(pos);
            if (!current.equals(expected)) {
                throw new IllegalStateException("Block at " + pos
                        + " changed during assembly (expected " + expected
                        + ", found " + current + ")");
            }
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS)) {
                throw new IllegalStateException("Could not remove block at " + pos);
            }
            removed.add(pos.immutable());
        }
    }

    private static void rollback(ServerLevel level, LevelPhysics physics,
                                 @Nullable PhysicsStructure structure, BuiltVolume built,
                                 List<BlockPos> removed) {
        final BlockPos origin = built.origin();
        for (int i = removed.size() - 1; i >= 0; i--) {
            final BlockPos pos = removed.get(i);
            final int localX = pos.getX() - origin.getX();
            final int localY = pos.getY() - origin.getY();
            final int localZ = pos.getZ() - origin.getZ();
            final BlockState state = built.volume().blockState(localX, localY, localZ);
            if (state == null || state.isAir()) {
                continue;
            }
            try {
                level.setBlock(pos, state, RESTORE_SUPPRESSED_FLAGS);
                restoreBlockEntity(level, pos, state,
                        built.blockEntities().get(new BlockPos(localX, localY, localZ)));
            } catch (RuntimeException failure) {
                Crythst.LOGGER.error("Could not roll back assembled block at {}", pos, failure);
            }
        }
        if (structure != null) {
            try {
                physics.removeStructure(structure);
            } catch (RuntimeException failure) {
                Crythst.LOGGER.error("Could not remove partially assembled structure", failure);
                try {
                    structure.close();
                } catch (RuntimeException ignored) {
                    // Best effort.
                }
            }
            StructureBlockEntityData.remove(structure);
            StructureBlockStates.clear(structure);
        }
        physics.invalidateTerrain();
    }

    private static void restoreBlockEntity(ServerLevel level, BlockPos pos,
                                           BlockState state, @Nullable CompoundTag tag) {
        if (tag == null || !state.hasBlockEntity()) {
            return;
        }
        try {
            final BlockEntity blockEntity = BlockEntity.loadStatic(
                    pos, state, tag.copy(), level.registryAccess());
            if (blockEntity != null) {
                level.setBlockEntity(blockEntity);
            }
        } catch (RuntimeException failure) {
            Crythst.LOGGER.warn("Could not restore block entity at {} in {}", pos,
                    level.dimension().identifier(), failure);
        }
    }

    private static @Nullable BlockState stateAt(PhysicsStructure structure, VoxelVolume volume,
                                                int x, int y, int z) {
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(x, y, z);
            if (state != null && !state.isAir()) {
                return state;
            }
        }
        final BlockState sideTable = StructureBlockStates.get(structure, x, y, z);
        return sideTable == null || sideTable.isAir() ? null : sideTable;
    }

    private static void dropBlock(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            final ItemStack stack = state.getCloneItemStack(level, pos, false);
            if (!stack.isEmpty()) {
                Block.popResource(level, pos, stack);
            }
        } catch (RuntimeException failure) {
            Crythst.LOGGER.warn("Could not drop disassembled block {} at {}", state, pos, failure);
        }
    }

    private static Vector3d vector(BlockPos pos) {
        return new Vector3d(pos.getX(), pos.getY(), pos.getZ());
    }
}
