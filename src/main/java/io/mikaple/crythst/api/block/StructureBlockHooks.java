package io.mikaple.crythst.api.block;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.BlockVoxelVolume;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsProfile;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import io.mikaple.crythst.physics.voxel.VoxelColliderHooks;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry and adapter for per-block structure hooks.
 *
 * <p>A hook can be supplied in two ways:</p>
 * <ol>
 *   <li>implement one of the {@code BlockStructure*} interfaces on the block
 *       class; this needs no registration; or</li>
 *   <li>register an explicit {@link BlockStructureHooks} bundle for a
 *       {@link Block} or a {@link BlockEntityType}.</li>
 * </ol>
 *
 * <p>Lookup precedence, highest first:</p>
 * <ol>
 *   <li>an explicit {@link Block} registration;</li>
 *   <li>an explicit {@link BlockEntityType} registration whose
 *       {@link BlockEntityType#isValid(BlockState)} accepts the state, in
 *       registration order;</li>
 *   <li>interfaces implemented by the block class;</li>
 *   <li>no hook (vanilla voxel cube, data-pack mass, geometric centre).</li>
 * </ol>
 *
 * <p>Registration is safe from any thread. The registry is read by structure
 * collider builds and by the server-side assembly/block-change paths; callers
 * must not mutate the returned hook objects after registration. Register
 * during mod initialization where possible so already-built structures can be
 * rebuilt with {@code PhysicsStructure#reindex}.</p>
 */
public final class StructureBlockHooks {
    /** Shapes this close to [0,1]^3 are treated as full cubes for merging. */
    private static final double FULL_CUBE_EPSILON = 1.0e-6;
    private static final Map<Block, BlockStructureHooks> BLOCK_HOOKS = new ConcurrentHashMap<>();
    private static volatile List<BlockEntityHook> blockEntityHooks = List.of();

    /** Per-cell block-state lookup used by the collider adapter. */
    @FunctionalInterface
    private interface StateSource {
        BlockState stateAt(int x, int y, int z);
    }

    private StructureBlockHooks() {
    }

    /**
     * Registers or replaces the complete hook bundle for a block.
     *
     * <p>Individual fields in the new bundle override previously registered
     * fields; passing {@link BlockStructureHooks#EMPTY} clears the registration.</p>
     */
    public static void register(Block block, BlockStructureHooks hooks) {
        if (block == null) {
            throw new NullPointerException("block");
        }
        if (hooks == null) {
            throw new NullPointerException("hooks");
        }
        if (hooks.isEmpty()) {
            BLOCK_HOOKS.remove(block);
        } else {
            BLOCK_HOOKS.put(block, hooks);
        }
    }

    /**
     * Merges one hook into a block's existing bundle.
     */
    public static void registerCollisionShape(Block block, BlockStructureCollisionShape hook) {
        mergeBlock(block, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block's existing bundle.
     */
    public static void registerCenterOfMass(Block block, BlockStructureCustomCenterOfMass hook) {
        mergeBlock(block, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block's existing bundle.
     */
    public static void registerDynamicCollider(Block block, BlockStructureDynamicCollider hook) {
        mergeBlock(block, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block's existing bundle.
     */
    public static void registerAssemblyListener(Block block, BlockStructureAssemblyListener hook) {
        mergeBlock(block, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block's existing bundle.
     */
    public static void registerBlockChangeListener(Block block, BlockStructureBlockChangeListener hook) {
        mergeBlock(block, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block's existing bundle.
     */
    public static void registerPhysicsProperties(Block block, BlockStructurePhysicsProperties hook) {
        mergeBlock(block, BlockStructureHooks.of(hook));
    }

    private static void mergeBlock(Block block, BlockStructureHooks hook) {
        if (block == null) {
            throw new NullPointerException("block");
        }
        if (hook == null) {
            throw new NullPointerException("hook");
        }
        BLOCK_HOOKS.merge(block, hook, BlockStructureHooks::mergeOver);
    }

    /**
     * Registers or replaces hooks for every block accepted by a block-entity
     * type. The type is checked with {@link BlockEntityType#isValid(BlockState)}
     * during lookup.
     */
    public static synchronized void register(BlockEntityType<?> type, BlockStructureHooks hooks) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        if (hooks == null) {
            throw new NullPointerException("hooks");
        }
        final List<BlockEntityHook> updated = new ArrayList<>(blockEntityHooks.size() + 1);
        boolean replaced = false;
        for (BlockEntityHook entry : blockEntityHooks) {
            if (entry.type == type) {
                if (!hooks.isEmpty()) {
                    updated.add(new BlockEntityHook(type, hooks));
                }
                replaced = true;
            } else {
                updated.add(entry);
            }
        }
        if (!replaced && !hooks.isEmpty()) {
            updated.add(new BlockEntityHook(type, hooks));
        }
        blockEntityHooks = List.copyOf(updated);
    }

    /**
     * Merges one hook into a block-entity type's bundle.
     */
    public static synchronized void registerCollisionShape(BlockEntityType<?> type,
                                             BlockStructureCollisionShape hook) {
        mergeEntityType(type, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block-entity type's bundle.
     */
    public static synchronized void registerCenterOfMass(BlockEntityType<?> type,
                                             BlockStructureCustomCenterOfMass hook) {
        mergeEntityType(type, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block-entity type's bundle.
     */
    public static synchronized void registerDynamicCollider(BlockEntityType<?> type,
                                             BlockStructureDynamicCollider hook) {
        mergeEntityType(type, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block-entity type's bundle.
     */
    public static synchronized void registerAssemblyListener(BlockEntityType<?> type,
                                             BlockStructureAssemblyListener hook) {
        mergeEntityType(type, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block-entity type's bundle.
     */
    public static synchronized void registerBlockChangeListener(BlockEntityType<?> type,
                                             BlockStructureBlockChangeListener hook) {
        mergeEntityType(type, BlockStructureHooks.of(hook));
    }

    /**
     * Merges one hook into a block-entity type's bundle.
     */
    public static synchronized void registerPhysicsProperties(BlockEntityType<?> type,
                                             BlockStructurePhysicsProperties hook) {
        mergeEntityType(type, BlockStructureHooks.of(hook));
    }

    private static void mergeEntityType(BlockEntityType<?> type, BlockStructureHooks hook) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        if (hook == null) {
            throw new NullPointerException("hook");
        }
        BlockStructureHooks existing = BlockStructureHooks.EMPTY;
        for (BlockEntityHook entry : blockEntityHooks) {
            if (entry.type == type) {
                existing = entry.hooks;
                break;
            }
        }
        register(type, existing.mergeOver(hook));
    }

    /**
     * Removes an explicit block registration. Interface implementations remain
     * active.
     */
    public static void unregister(Block block) {
        if (block != null) {
            BLOCK_HOOKS.remove(block);
        }
    }

    /**
     * Removes an explicit block-entity-type registration.
     */
    public static synchronized void unregister(BlockEntityType<?> type) {
        if (type == null) {
            return;
        }
        final List<BlockEntityHook> updated = new ArrayList<>(blockEntityHooks.size());
        for (BlockEntityHook entry : blockEntityHooks) {
            if (entry.type != type) {
                updated.add(entry);
            }
        }
        blockEntityHooks = List.copyOf(updated);
    }

    /**
     * Resolves all hooks that apply to a block state.
     *
     * <p>The returned bundle is immutable and never {@code null}. Hooks are
     * resolved live, so a registration affects the next collider build without
     * restarting the server.</p>
     */
    public static BlockStructureHooks hooksFor(BlockState state) {
        if (state == null) {
            return BlockStructureHooks.EMPTY;
        }
        BlockStructureHooks result = fromInterfaces(state.getBlock());
        for (BlockEntityHook entry : blockEntityHooks) {
            if (entry.type.isValid(state)) {
                result = result.mergeOver(entry.hooks);
                break;
            }
        }
        final BlockStructureHooks explicit = BLOCK_HOOKS.get(state.getBlock());
        if (explicit != null) {
            result = result.mergeOver(explicit);
        }
        return result;
    }

    private static BlockStructureHooks fromInterfaces(Block block) {
        BlockStructureHooks.Builder builder = null;
        if (block instanceof BlockStructureCollisionShape shape) {
            builder = BlockStructureHooks.builder().collisionShape(shape);
        }
        if (block instanceof BlockStructureCustomCenterOfMass com) {
            builder = builder == null ? BlockStructureHooks.builder() : builder;
            builder.centerOfMass(com);
        }
        if (block instanceof BlockStructureDynamicCollider dynamic) {
            builder = builder == null ? BlockStructureHooks.builder() : builder;
            builder.dynamicCollider(dynamic);
        }
        if (block instanceof BlockStructureAssemblyListener assembly) {
            builder = builder == null ? BlockStructureHooks.builder() : builder;
            builder.assemblyListener(assembly);
        }
        if (block instanceof BlockStructureBlockChangeListener change) {
            builder = builder == null ? BlockStructureHooks.builder() : builder;
            builder.blockChangeListener(change);
        }
        if (block instanceof BlockStructurePhysicsProperties properties) {
            builder = builder == null ? BlockStructureHooks.builder() : builder;
            builder.physicsProperties(properties);
        }
        return builder == null ? BlockStructureHooks.EMPTY : builder.build();
    }

    /**
     * Returns whether any block-change listener is currently registered.
     */
    public static boolean hasBlockChangeListeners() {
        for (BlockStructureHooks hooks : BLOCK_HOOKS.values()) {
            if (hooks.blockChangeListener() != null) {
                return true;
            }
        }
        for (BlockEntityHook entry : blockEntityHooks) {
            if (entry.hooks.blockChangeListener() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clears every explicit registration. Intended for tests and server
     * shutdown; block-class interfaces are not affected.
     */
    public static synchronized void clear() {
        BLOCK_HOOKS.clear();
        blockEntityHooks = List.of();
    }

    // ------------------------------------------------------------------
    // Core adapters
    // ------------------------------------------------------------------

    /**
     * Creates the per-cell hook set consumed by the voxel collider builder.
     *
     * <p>Volumes that can expose an authored block state additionally fall back
     * to the state's vanilla collision shape when no explicit collision hook is
     * registered. Full cubes stay on the cheap merged-box path, empty shapes
     * contribute no collision geometry, and partial shapes (slabs, stairs,
     * fences, ...) become exact {@link VoxelCellPart}s.</p>
     *
     * <p>This overload has no structure identity, so it only resolves states
     * carried by a {@link StatefulVoxelVolume}. Use
     * {@link #colliderHooks(PhysicsStructure, VoxelVolume)} for a
     * {@link MutableVoxelVolume} whose states live in
     * {@link StructureBlockStates}.</p>
     *
     * @param volume structure volume being colliderised
     * @return an adapter, or {@link VoxelColliderHooks#NONE}
     */
    public static VoxelColliderHooks colliderHooks(VoxelVolume volume) {
        return colliderHooks(null, volume);
    }

    /**
     * Creates the per-cell hook set for one structure, including authored block
     * states kept in the {@link StructureBlockStates} side table.
     *
     * <p>This is the variant {@link PhysicsStructure#reindex(VoxelVolume)} uses
     * so a {@link MutableVoxelVolume} with authored states gets the same exact
     * vanilla shapes as a stateful volume.</p>
     *
     * @param structure structure that owns the side-table states, or
     *                  {@code null} to skip side-table lookup
     * @param volume    structure volume being colliderised
     * @return an adapter, or {@link VoxelColliderHooks#NONE}
     */
    public static VoxelColliderHooks colliderHooks(PhysicsStructure structure, VoxelVolume volume) {
        final StateSource states = stateSource(structure, volume);
        if (states == null) {
            return VoxelColliderHooks.NONE;
        }
        final BlockGetter level = volume instanceof BlockVoxelVolume blockVolume
                ? blockVolume.level() : null;
        final BlockGetter virtualLevel = virtualLevel(states, volume);
        return new VoxelColliderHooks() {
            @Override
            public List<VoxelCellPart> collisionParts(int x, int y, int z, VoxelVolume ignored) {
                final BlockState state = states.stateAt(x, y, z);
                if (state == null) {
                    return null;
                }
                try {
                    final BlockStructureHooks hooks = hooksFor(state);
                    final BlockPos pos = new BlockPos(x, y, z);
                    if (hooks.dynamicCollider() != null) {
                        final List<StructureColliderBox> boxes =
                                hooks.dynamicCollider().structureCollisionBoxes(level, pos, state);
                        if (boxes == null) {
                            return null;
                        }
                        return toPartsFromBoxes(x, y, z, boxes);
                    }
                    if (hooks.collisionShape() != null) {
                        final VoxelShape shape =
                                hooks.collisionShape().structureCollisionShape(level, pos, state);
                        if (shape == null) {
                            return null;
                        }
                        return toPartsFromAabbs(x, y, z, shape.toAabbs());
                    }
                    // No explicit hook: use the block state's own collision
                    // shape. The virtual getter supplies neighbour states from
                    // the volume, so fences, walls and stairs shape themselves
                    // from the structure rather than from the world level.
                    return automaticCollisionParts(state, virtualLevel, pos, x, y, z);
                } catch (Throwable t) {
                    logHookFailure(state, "collision shape", t);
                    return null;
                }
            }

            @Override
            public Vector3dc centerOfMass(int x, int y, int z, VoxelVolume ignored) {
                final BlockState state = states.stateAt(x, y, z);
                if (state == null) {
                    return null;
                }
                try {
                    final BlockStructureHooks hooks = hooksFor(state);
                    if (hooks.centerOfMass() == null) {
                        return null;
                    }
                    final Vector3dc result = hooks.centerOfMass()
                            .structureCenterOfMass(level, new BlockPos(x, y, z), state);
                    if (result == null) {
                        return null;
                    }
                    if (!Double.isFinite(result.x()) || !Double.isFinite(result.y())
                            || !Double.isFinite(result.z())) {
                        logHookFailure(state, "centre of mass", new IllegalArgumentException("non-finite"));
                        return null;
                    }
                    return new Vector3d(result);
                } catch (Throwable t) {
                    logHookFailure(state, "centre of mass", t);
                    return null;
                }
            }

            @Override
            public Double mass(int x, int y, int z, VoxelVolume ignored) {
                final BlockPhysicsProfile profile = profile(states, level, x, y, z);
                return profile == null ? null : profile.mass();
            }

            @Override
            public PhysicsMaterial material(int x, int y, int z, VoxelVolume ignored) {
                final BlockPhysicsProfile profile = profile(states, level, x, y, z);
                return profile == null ? null : profile.toMaterial();
            }
        };
    }

    /**
     * Resolves a cell's state from the volume itself or from the structure's
     * side table. Returns {@code null} when the volume cannot provide states.
     */
    private static StateSource stateSource(PhysicsStructure structure, VoxelVolume volume) {
        if (volume instanceof StatefulVoxelVolume stateful) {
            return (x, y, z) -> stateAt(stateful, x, y, z);
        }
        if (structure != null && volume instanceof MutableVoxelVolume) {
            return (x, y, z) -> {
                if (!volume.bounds().contains(x, y, z) || !volume.isSolid(x, y, z)) {
                    return null;
                }
                final BlockState state = StructureBlockStates.get(structure, x, y, z);
                return state == null || state.isAir() ? null : state;
            };
        }
        return null;
    }

    private static BlockPhysicsProfile profile(StateSource states, BlockGetter level,
                                               int x, int y, int z) {
        final BlockState state = states.stateAt(x, y, z);
        if (state == null) {
            return null;
        }
        try {
            final BlockStructurePhysicsProperties hook = hooksFor(state).physicsProperties();
            return hook == null ? null : hook.structurePhysics(level, new BlockPos(x, y, z), state);
        } catch (Throwable t) {
            logHookFailure(state, "physics properties", t);
            return null;
        }
    }

    private static BlockState stateAt(StatefulVoxelVolume volume, int x, int y, int z) {
        if (!volume.bounds().contains(x, y, z) || !volume.isSolid(x, y, z)) {
            return null;
        }
        final BlockState state = volume.blockState(x, y, z);
        return state == null || state.isAir() ? null : state;
    }

    /**
     * Fallback used when a block registers no explicit collision hook: extract
     * the vanilla collision shape for the virtual structure position.
     *
     * @return {@code null} for a full cube (keep the greedy merged box), an
     *         empty list for a non-colliding shape, or exact cell parts
     */
    private static List<VoxelCellPart> automaticCollisionParts(BlockState state, BlockGetter level,
                                                               BlockPos pos,
                                                               int x, int y, int z) {
        if (state.isCollisionShapeFullBlock(level, pos)) {
            return null;
        }
        final VoxelShape shape =
                state.getCollisionShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return List.of();
        }
        final List<AABB> aabbs = shape.toAabbs();
        if (aabbs.size() == 1 && isFullCube(aabbs.get(0))) {
            return null;
        }
        return toPartsFromAabbs(x, y, z, aabbs);
    }

    private static boolean isFullCube(AABB box) {
        return Math.abs(box.minX) <= FULL_CUBE_EPSILON
                && Math.abs(box.minY) <= FULL_CUBE_EPSILON
                && Math.abs(box.minZ) <= FULL_CUBE_EPSILON
                && Math.abs(box.maxX - 1.0) <= FULL_CUBE_EPSILON
                && Math.abs(box.maxY - 1.0) <= FULL_CUBE_EPSILON
                && Math.abs(box.maxZ - 1.0) <= FULL_CUBE_EPSILON;
    }

    /**
     * A read-only {@link BlockGetter} for structure-local voxel positions.
     * Vanilla shape code sometimes inspects neighbours (fences, walls, stairs);
     * it must see the structure's own states, not whatever occupies the same
     * coordinates in the real world.
     */
    private static BlockGetter virtualLevel(StateSource states, VoxelVolume volume) {
        final int minY = volume.bounds().minY();
        final int height = Math.max(1, volume.bounds().sizeY());
        return new BlockGetter() {
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                final BlockState state = states.stateAt(pos.getX(), pos.getY(), pos.getZ());
                return state == null ? air() : state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return Fluids.EMPTY.defaultFluidState();
            }

            @Override
            public int getHeight() {
                return height;
            }

            @Override
            public int getMinY() {
                return minY;
            }
        };
    }

    private static List<VoxelCellPart> toPartsFromBoxes(int x, int y, int z,
                                                        List<StructureColliderBox> boxes) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        final List<VoxelCellPart> parts = new ArrayList<>(boxes.size());
        for (StructureColliderBox box : boxes) {
            parts.add(new VoxelCellPart(x + box.minX(), y + box.minY(), z + box.minZ(),
                    box.sizeX(), box.sizeY(), box.sizeZ()));
        }
        return parts;
    }

    private static List<VoxelCellPart> toPartsFromAabbs(int x, int y, int z, List<AABB> boxes) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        final List<VoxelCellPart> parts = new ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            parts.add(new VoxelCellPart(x + box.minX, y + box.minY, z + box.minZ,
                    box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ));
        }
        return parts;
    }

    // ------------------------------------------------------------------
    // Assembly and block-change plumbing
    // ------------------------------------------------------------------

    /**
     * Fires before-assembly callbacks for every listener referenced by the
     * volume. Internal plumbing called by {@code LevelPhysics}.
     */
    public static void fireBeforeAssembly(ServerLevel level, VoxelVolume volume) {
        if (!(volume instanceof StatefulVoxelVolume stateful)) {
            return;
        }
        final Set<BlockStructureAssemblyListener> listeners = listeners(stateful, true);
        if (listeners.isEmpty()) {
            return;
        }
        final StructureAssemblyContext context = new StructureAssemblyContext(level, volume);
        for (BlockStructureAssemblyListener listener : listeners) {
            try {
                listener.beforeStructureAssembly(context);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Structure assembly listener failed", t);
            }
        }
    }

    /**
     * Fires after-assembly callbacks for every listener referenced by the
     * volume. Internal plumbing called by {@code LevelPhysics}.
     */
    public static void fireAfterAssembly(ServerLevel level, VoxelVolume volume,
                                         PhysicsStructure structure) {
        if (!(volume instanceof StatefulVoxelVolume stateful) || structure == null) {
            return;
        }
        final Set<BlockStructureAssemblyListener> listeners = listeners(stateful, true);
        if (listeners.isEmpty()) {
            return;
        }
        final StructureAssemblyContext context = new StructureAssemblyContext(level, volume);
        for (BlockStructureAssemblyListener listener : listeners) {
            try {
                listener.afterStructureAssembly(context, structure);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Structure assembly listener failed", t);
            }
        }
    }

    private static Set<BlockStructureAssemblyListener> listeners(StatefulVoxelVolume volume,
                                                                 boolean assembly) {
        final Set<BlockStructureAssemblyListener> listeners =
                Collections.newSetFromMap(new IdentityHashMap<>());
        final var bounds = volume.bounds();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!volume.isSolid(x, y, z)) {
                        continue;
                    }
                    final BlockState state = volume.blockState(x, y, z);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    final BlockStructureHooks hooks = hooksFor(state);
                    if (assembly && hooks.assemblyListener() != null) {
                        listeners.add(hooks.assemblyListener());
                    }
                }
            }
        }
        return listeners;
    }

    /**
     * Captures block states if at least one block-change listener is
     * registered, otherwise returns {@code null} to avoid the allocation.
     *
     * <p>Internal plumbing used by {@code PhysicsStructure}.</p>
     */
    public static StructureBlockStateSnapshot captureBlockStates(VoxelVolume volume) {
        if (!(volume instanceof StatefulVoxelVolume stateful)) {
            return null;
        }
        boolean listening = hasBlockChangeListeners();
        if (!listening) {
            // Interface-only listeners cannot be enumerated from the registry;
            // scan once before allocating a snapshot.
            final var probeBounds = stateful.bounds();
            scan:
            for (int y = probeBounds.minY(); y < probeBounds.maxY(); y++) {
                for (int z = probeBounds.minZ(); z < probeBounds.maxZ(); z++) {
                    for (int x = probeBounds.minX(); x < probeBounds.maxX(); x++) {
                        if (stateful.isSolid(x, y, z)) {
                            final BlockState state = stateful.blockState(x, y, z);
                            if (state != null && !state.isAir()
                                    && state.getBlock() instanceof BlockStructureBlockChangeListener) {
                                listening = true;
                                break scan;
                            }
                        }
                    }
                }
            }
        }
        if (!listening) {
            return null;
        }
        final Map<Long, BlockState> states = new java.util.HashMap<>();
        final var bounds = stateful.bounds();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!stateful.isSolid(x, y, z)) {
                        continue;
                    }
                    final BlockState state = stateful.blockState(x, y, z);
                    if (state != null && !state.isAir()) {
                        states.put(BlockPos.asLong(x, y, z), state);
                    }
                }
            }
        }
        return new StructureBlockStateSnapshot(states);
    }

    /**
     * Fires block-change callbacks by comparing a previous snapshot with the
     * new volume. Internal plumbing used by {@code PhysicsStructure}.
     *
     * @param previous snapshot returned by {@link #captureBlockStates}, or
     *                 {@code null} to skip
     */
    public static void fireBlockChanges(PhysicsStructure structure,
                                        StructureBlockStateSnapshot previous,
                                        VoxelVolume volume) {
        if (previous == null || !(volume instanceof StatefulVoxelVolume stateful)) {
            return;
        }
        final StructureBlockStateSnapshot current = captureBlockStates(volume);
        if (current == null) {
            return;
        }
        fireDiff(structure, previous.states(), current.states());
        // Cells present before but absent now were removed.
        for (Map.Entry<Long, BlockState> entry : previous.states().entrySet()) {
            if (!current.states().containsKey(entry.getKey())) {
                final long key = entry.getKey();
                fireOne(structure, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key),
                        entry.getValue(), air());
            }
        }
    }

    private static void fireDiff(PhysicsStructure structure,
                                 Map<Long, BlockState> previous,
                                 Map<Long, BlockState> current) {
        for (Map.Entry<Long, BlockState> entry : current.entrySet()) {
            final long key = entry.getKey();
            final BlockState oldState = previous.getOrDefault(key, air());
            if (!sameState(oldState, entry.getValue())) {
                fireOne(structure, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key),
                        oldState, entry.getValue());
            }
        }
    }

    private static boolean sameState(BlockState a, BlockState b) {
        return a == b || a.equals(b);
    }

    /**
     * Lazy so loading the hook registry does not force Minecraft's block
     * registry to bootstrap on a pure physics/test path.
     */
    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    private static void fireOne(PhysicsStructure structure, int x, int y, int z,
                                BlockState oldState, BlockState newState) {
        final Set<BlockStructureBlockChangeListener> listeners =
                Collections.newSetFromMap(new IdentityHashMap<>());
        addChangeListener(listeners, oldState);
        addChangeListener(listeners, newState);
        if (listeners.isEmpty()) {
            return;
        }
        final StructureBlockChange change = new StructureBlockChange(structure, x, y, z,
                oldState, newState);
        for (BlockStructureBlockChangeListener listener : listeners) {
            try {
                listener.onStructureBlockChanged(change);
            } catch (Throwable t) {
                Crythst.LOGGER.error("Structure block-change listener failed", t);
            }
        }
    }

    private static void addChangeListener(Set<BlockStructureBlockChangeListener> listeners,
                                          BlockState state) {
        if (state == null || state.isAir()) {
            return;
        }
        final BlockStructureBlockChangeListener listener = hooksFor(state).blockChangeListener();
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private static void logHookFailure(BlockState state, String hook, Throwable failure) {
        Crythst.LOGGER.warn("Structure block hook '{}' failed for {}; falling back to vanilla behaviour",
                hook, state, failure);
    }

    private record BlockEntityHook(BlockEntityType<?> type, BlockStructureHooks hooks) {
    }
}
