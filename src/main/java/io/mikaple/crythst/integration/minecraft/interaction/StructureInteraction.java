package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.integration.minecraft.EditableStatefulVolume;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.entity.placement.StructureEntityPlacement;
import io.mikaple.crythst.integration.minecraft.entity.placement.StructureEntityPlacementHandler;
import io.mikaple.crythst.integration.minecraft.gameevent.StructurePlayerProgress;
import io.mikaple.crythst.integration.minecraft.operation.StructureOperationRedirector;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import io.mikaple.crythst.integration.minecraft.selection.StructureSelection;
import io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolume;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.mixin.ServerGamePacketListenerImplInvoker;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side interaction API for blocks that live inside a
 * {@link PhysicsStructure} rather than in Minecraft's block grid.
 *
 * <p>The vanilla client cannot raycast virtual structure blocks, so all edits
 * are resolved again on the server from the player's eye position and look
 * vector. This class is the authoritative half; the optional client hook in
 * {@code interaction.client} only decides when to ask the server to act.</p>
 *
 * <p>All methods must be called from the server thread. They return a small
 * result record instead of throwing for ordinary gameplay failures (no hit,
 * read-only volume, occupied cell, ...).</p>
 */
public final class StructureInteraction {
    /** Base mining impulse in N·s at {@code miningImpulseScale = 1}. */
    public static final double MINING_IMPULSE = 400.0;
    /** Placement reaction relative to {@link #MINING_IMPULSE}. */
    public static final double PLACE_IMPULSE_FRACTION = 0.3;
    /** Base water recoil in blocks/tick at {@code shovelWaterReaction = 1}. */
    public static final double WATER_RECOIL = 0.55;
    /** Extra "up" component of the water recoil. */
    public static final double WATER_LIFT = 0.18;
    /**
     * Distance tolerance used when deciding whether a structure is in front of
     * a vanilla hit. This is deliberately small: a real block must win when it
     * is genuinely closer, while a few millimetres of ray quantisation between
     * client and server must not hide a structure that is visibly in front.
     */
    public static final double FRONT_SLACK = 0.02;

    private static final List<BlockChangeListener> LISTENERS = new CopyOnWriteArrayList<>();
    /**
     * Two ticks is enough to merge the vanilla use-item/use-block packet with
     * the PLACE payload from the same right-click. Vanilla cannot place twice
     * that quickly. Keyed weakly because ServerPlayer instances are long-lived.
     */
    private static final Map<ServerPlayer, Long> LAST_PLACEMENT_TICK = new WeakHashMap<>();
    /**
     * At most one authoritative structure break per server tick. This is a
     * sanity limit for the client-driven {@code MINE} payload: the client may
     * compute survival progress locally, but the server never trusts a burst of
     * break packets for arbitrary voxels. A successful break removes the voxel,
     * so the next legitimate break for the same target must wait for a new
     * authoritative raycast anyway.
     */
    private static final Map<ServerPlayer, Long> LAST_MINE_TICK = new WeakHashMap<>();
    /**
     * Collapses the vanilla {@code UseBlockCallback}/{@code UseItemCallback}
     * and the explicit payload for one physical use click when the void-level
     * redirect path owns the action. The legacy placement guard remains
     * separate because an out-of-bounds redirect deliberately falls back to
     * custom volume expansion.
     */
    private static final Map<ServerPlayer, Long> LAST_USE_TICK = new WeakHashMap<>();

    /**
     * Minimal edit surface shared by {@link MutableVoxelVolume}, split
     * components and restored stateful volumes. Keeping this adapter here
     * avoids forcing every volume type to implement a common mutable
     * interface; package visibility lets tests assert which volumes the
     * interaction layer accepts.
     */
    interface EditableVolume {
        void setBlock(int x, int y, int z, boolean solid, double mass,
                      PhysicsMaterial material, BlockState state);
    }

    private StructureInteraction() {
    }

    static EditableVolume editableVolume(VoxelVolume volume) {
        // Checked before the mutable branch: a stateful volume also has to
        // record the block state being written, and the plain mutable adapter
        // below drops it.
        if (volume instanceof EditableStatefulVolume stateful) {
            return stateful::setBlock;
        }
        if (volume instanceof MutableVoxelVolume mutable) {
            return (x, y, z, solid, mass, material, state) -> {
                if (solid) {
                    mutable.setBlock(x, y, z, true, mass, material);
                } else {
                    mutable.setSolid(x, y, z, false);
                }
            };
        }
        if (volume instanceof RestoredStructureVolume restored) {
            return restored::setBlock;
        }
        return null;
    }

    /**
     * Reads the stored state from a stateful volume first, then from the
     * interaction side table used by plain mutable volumes.
     */
    private static BlockState stateAt(VoxelVolume volume, PhysicsStructure structure,
                                      int x, int y, int z) {
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(x, y, z);
            if (state != null) {
                return state;
            }
        }
        final BlockState stored = StructureBlockStates.get(structure, x, y, z);
        if (stored != null) {
            return stored;
        }
        // Stateless volumes (for example /crythst demo's filled stone grid)
        // have no block-state side table. Fall back to a representative
        // vanilla block for feedback, drops, picking and placement previews
        // instead of silently producing no particles/sound.
        if (volume.bounds().contains(x, y, z) && volume.isSolid(x, y, z)) {
            return fallbackState(volume.blockMaterial(x, y, z));
        }
        return null;
    }

    private static BlockState fallbackState(PhysicsMaterial material) {
        if (PhysicsMaterial.ICE.equals(material)) {
            return Blocks.ICE.defaultBlockState();
        }
        if (PhysicsMaterial.WOOD.equals(material)) {
            return Blocks.OAK_PLANKS.defaultBlockState();
        }
        if (PhysicsMaterial.METAL.equals(material)) {
            return Blocks.IRON_BLOCK.defaultBlockState();
        }
        if (PhysicsMaterial.DIRT.equals(material)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    /**
     * Receives every successful voxel edit. Intended for drops, statistics,
     * particles or gameplay mods that need to mirror structure edits.
     */
    @FunctionalInterface
    public interface BlockChangeListener {
        void onBlockChanged(ServerPlayer player, PhysicsStructure structure,
                            int x, int y, int z, BlockState previousState, BlockState newState);
    }

    public static void addBlockChangeListener(BlockChangeListener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeBlockChangeListener(BlockChangeListener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Result of a break attempt.
     *
     * @param success          whether a solid voxel was removed
     * @param structureRemoved whether the structure became empty and its body was removed
     * @param previousState    the side-table state before the edit, or {@code null}
     */
    public record BreakResult(boolean success, boolean structureRemoved, BlockState previousState) {
        public static final BreakResult FAILED = new BreakResult(false, false, null);
    }

    /** Result of a placement attempt. */
    public record PlaceResult(boolean success, int x, int y, int z) {
        public static final PlaceResult FAILED = new PlaceResult(false, 0, 0, 0);
    }

    /**
     * Raycasts the player's look vector against every live structure using the
     * same {@link StructureSelection} algorithm as the client clip.
     *
     * @return the nearest structure hit, or {@code null}
     */
    public static StructureBlockHitResult raycast(ServerPlayer player, double reach) {
        final CrythstConfig config = MinecraftPhysics.config();
        if (config == null || !config.structureBlockInteractionEnabled || reach <= 0.0) {
            return null;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent((ServerLevel) player.level());
        if (physics == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition();
        final Vector3d direction = PlayerGravity.lookDirection(player);
        if (direction.lengthSquared() < 1.0e-12) {
            return null;
        }
        return StructureSelection.raycast(StructureSelection.serverViews(player.level()),
                eye, direction, reach);
    }

    public static StructureBlockHitResult raycast(ServerPlayer player) {
        return raycast(player, player.blockInteractionRange());
    }

    /**
     * Whether a structure currently exposes an editable volume. Useful for
     * diagnostics and for gameplay code deciding whether to offer editing UI.
     */
    public static boolean isEditable(PhysicsStructure structure) {
        return structure != null && !structure.closed()
                && editableVolume(structure.volume()) != null;
    }

    /**
     * Called by each right-click path before placing. It collapses the
     * potential duplicates from {@code UseBlockCallback}, {@code UseItemCallback}
     * and the client {@code PLACE} payload for one physical click.
     *
     * @return {@code false} when this player already placed within the last two
     *         ticks and the duplicate should be ignored
     */
    public static boolean beginPlacement(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        final long now = player.level().getGameTime();
        final Long previous = LAST_PLACEMENT_TICK.get(player);
        if (previous != null && now - previous >= 0L && now - previous <= 1L) {
            return false;
        }
        LAST_PLACEMENT_TICK.put(player, now);
        return true;
    }

    /**
     * Server-side rate guard for the client {@code MINE} payload.
     *
     * <p>Progress is intentionally not trusted across the wire. A client sends
     * {@code MINE} only when its local progress reaches {@code 1.0}, but the
     * server still checks reach, occlusion and occupancy through
     * {@link #breakBlock}. This guard only collapses duplicate packets that
     * arrive in the same server tick, which cannot represent two legitimate
     * breaks of different voxels because a single structure raycast has one
     * target.</p>
     *
     * @return {@code false} when this player already mined during this tick
     */
    public static boolean beginMine(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        final long now = player.level().getGameTime();
        final Long previous = LAST_MINE_TICK.get(player);
        if (previous != null && previous == now) {
            return false;
        }
        LAST_MINE_TICK.put(player, now);
        return true;
    }

    /**
     * Server-side duplicate guard for right-click use/placement when the void
     * redirector owns the action. Fabric callbacks and the explicit payload can
     * describe the same physical click; vanilla use/placement must run once.
     *
     * @return {@code false} when this player already used a structure during
     *         this tick or the previous one
     */
    public static boolean beginInteraction(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        final long now = player.level().getGameTime();
        final Long previous = LAST_USE_TICK.get(player);
        if (previous != null && now - previous >= 0L && now - previous <= 1L) {
            return false;
        }
        LAST_USE_TICK.put(player, now);
        return true;
    }

    /**
     * Removes one solid voxel from a mutable structure volume, rebuilds the
     * collider, refreshes the auxiliary block-ticking level and applies a
     * reaction impulse opposite to the player's look direction.
     */
    public static BreakResult breakBlock(ServerPlayer player, StructureBlockHitResult hit) {
        if (MinecraftPhysics.config() == null
                || !MinecraftPhysics.config().structureBlockInteractionEnabled
                || hit == null || hit.structure() == null) {
            return BreakResult.FAILED;
        }
        final PhysicsStructure structure = hit.structure();
        if (structure.closed()) {
            return BreakResult.FAILED;
        }
        final VoxelVolume volume = structure.volume();
        final EditableVolume editable = editableVolume(volume);
        if (editable == null) {
            // BlockVoxelVolume and unknown read-only views cannot be edited.
            return BreakResult.FAILED;
        }
        final int x = hit.voxelX();
        final int y = hit.voxelY();
        final int z = hit.voxelZ();
        if (!volume.bounds().contains(x, y, z) || !volume.isSolid(x, y, z)) {
            return BreakResult.FAILED;
        }

        final LevelPhysics physics = MinecraftPhysics.getIfPresent((ServerLevel) player.level());
        if (physics == null) {
            return BreakResult.FAILED;
        }
        final ServerLevel level = (ServerLevel) player.level();

        final BlockState previousState = stateAt(volume, structure, x, y, z);
        final double previousMass = volume.blockMass(x, y, z);
        final PhysicsMaterial previousMaterial = volume.blockMaterial(x, y, z);
        final double blockScale = structure.blockScale();
        // Capture the pose before mutating or splitting the body. The last
        // voxel closes its body below, and a split replaces the original
        // structure with new components, so feedback must not read the body
        // afterwards.
        final Transform3d feedbackPose = structure.pose();
        StructureBlockStates.remove(structure, x, y, z);
        editable.setBlock(x, y, z, false, 0.0, PhysicsMaterial.DEFAULT, null);

        if (StructureSplitter.isMassless(volume)) {
            // A body with no solid cells or zero total mass cannot be
            // simulated, so it is cleared instead of reindexed. Emit feedback
            // first: this is the last block and there is no later success
            // path for it.
            StructureFeedback.breakBlock(level, feedbackPose, x, y, z,
                    previousState, blockScale, level.getRandom());
            StructureSplitter.removeIfMassless(physics, structure, volume);
            notifyListeners(player, structure, x, y, z, previousState, null);
            return new BreakResult(true, true, previousState);
        }

        try {
            structure.reindex(volume);
        } catch (RuntimeException e) {
            editable.setBlock(x, y, z, true, previousMass, previousMaterial, previousState);
            if (previousState != null) {
                StructureBlockStates.set(structure, x, y, z, previousState);
            }
            return BreakResult.FAILED;
        }
        // Emit feedback after the successful reindex but before the refresh
        // pass, which may split or close this body. The pose was captured
        // before the edit, so playing the sound and particles while the body is
        // still alive keeps the ordering obvious.
        StructureFeedback.breakBlock(level, feedbackPose, x, y, z,
                previousState, blockScale, level.getRandom());
        // The redirected path uses vanilla destroyBlock, which already handles
        // drops/tool durability/stats. This custom fallback must do the same
        // rather than silently deleting the block.
        if (!player.preventsBlockDrops()) {
            final Vector3d center = structure.blockCenter(x, y, z);
            final BlockPos dropPos = BlockPos.containing(center.x, center.y, center.z);
            final ItemStack tool = player.getMainHandItem().copy();
            for (ItemStack drop : Block.getDrops(previousState, level, dropPos,
                    null, player, tool)) {
                Block.popResource(level, dropPos, drop);
            }
            player.getMainHandItem().mineBlock(level, previousState, dropPos, player);
            StructurePlayerProgress.onCustomBreak(player, structure, x, y, z, previousState);
            player.causeFoodExhaustion(0.005F);
        }
        // Removing a block can disconnect the remaining solid cells. The
        // refresh pass also performs that split and returns the bodies that now
        // represent the structure.
        final List<PhysicsStructure> remaining = physics.refreshStructure(structure, volume);
        final PhysicsStructure reactionTarget = nearestStructure(remaining, hit.worldHit());

        if (reactionTarget != null) {
            applyImpulse(player, reactionTarget, hit.worldHit(), -MINING_IMPULSE * scale(
                    MinecraftPhysics.config().miningImpulseScale));
        }
        notifyListeners(player, reactionTarget != null ? reactionTarget : structure,
                x, y, z, previousState, null);
        return new BreakResult(true, false, previousState);
    }

    /**
     * Resolves the block state a held block item would place for this hit.
     *
     * <p>The client never supplies a state. The server rebuilds a
     * {@link StructurePlacementContext} expressed in the structure's own frame
     * from the player's current look direction, the clicked local face and the
     * stack, then asks {@link Block#getStateForPlacement(BlockPlaceContext)}.
     * The hit point and look vector are transformed through
     * {@code pose^-1}, so stairs, slabs, trapdoors, fences, logs and pillars
     * honour the structure-local yaw/pitch, face and click fraction. This also
     * handles directional-gravity cameras because the world look vector already
     * comes from {@link PlayerGravity#lookDirection(Player)}. {@code
     * BlockItem.getPlacementState} is protected; calling the public block hook
     * avoids an accessor while keeping the exact state semantics.</p>
     *
     * <p>Placement validation is separate: it uses the virtual structure level
     * for {@code canSurvive}, replacement and entity obstruction. We
     * intentionally do not call {@code BlockItem.canPlace} against the real
     * {@code Level} because structure voxels are not part of it.</p>
     *
     * <p>The {@code minecraft:block_state} component carried by the stack (for
     * example a debug-stick-styled item or a loot-table-defined item) is applied
     * afterwards, exactly as vanilla does when it places a block.</p>
     *
     * @return the resolved state, or {@code null} when the stack is not a block
     *         item or resolves to air
     */
    public static BlockState placementState(Player player, ItemStack stack, StructureBlockHitResult hit) {
        if (player == null || stack == null || hit == null
                || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        final BlockState placed = blockItem.getBlock().getStateForPlacement(
                StructurePlacementContext.create(player, stack, hit));
        final BlockState resolved = placed != null
                ? placed
                : blockItem.getBlock().defaultBlockState();
        final BlockState withComponents;
        try {
            withComponents = stack.getOrDefault(DataComponents.BLOCK_STATE,
                    BlockItemStateProperties.EMPTY).apply(resolved);
        } catch (RuntimeException ignored) {
            // An invalid component must not make placement impossible.
            return resolved.isAir() ? null : resolved;
        }
        return withComponents.isAir() ? null : withComponents;
    }

    /**
     * Server-authoritative placement of the held block item.
     *
     * @param stack the stack in the player's main hand; only its item and
     *              components are read
     */
    public static PlaceResult placeBlock(ServerPlayer player, StructureBlockHitResult hit, ItemStack stack) {
        final BlockState state = placementState(player, stack, hit);
        return state == null ? PlaceResult.FAILED : placeBlock(player, hit, state);
    }

    /**
     * Adds a block into the structure voxel adjacent to the hit face.
     *
     * <p>All vanilla placement rules are checked before any mutation: the
     * target must be replaceable, it must survive at the virtual structure
     * position, it must be exposed by a solid structure voxel (or be itself a
     * replaceable cell), and the exact placed collision shape must not
     * intersect a player, mob, item frame or other blocking entity in world
     * space. A rejected placement returns {@link PlaceResult#FAILED} without
     * copying/expanding the volume, reindexing the body, playing feedback or
     * consuming the stack.</p>
     *
     * <p>If the target is inside the current bounds it must be empty or
     * replaceable. If it is outside (building on an outer face), the grid is
     * expanded by one cell and copied into a new mutable volume; a restored
     * {@code RestoredStructureVolume} therefore becomes editable after the
     * first outside placement. A small reaction impulse is applied, mirroring
     * the mining response.</p>
     */
    public static PlaceResult placeBlock(ServerPlayer player, StructureBlockHitResult hit, BlockState state) {
        if (MinecraftPhysics.config() == null
                || !MinecraftPhysics.config().structureBlockInteractionEnabled
                || player == null || hit == null || hit.structure() == null
                || state == null || state.isAir()) {
            return PlaceResult.FAILED;
        }
        final PhysicsStructure structure = hit.structure();
        if (structure.closed()) {
            return PlaceResult.FAILED;
        }
        final VoxelVolume volume = structure.volume();
        final EditableVolume existing = editableVolume(volume);
        if (existing == null) {
            return PlaceResult.FAILED;
        }
        final int x = hit.adjacentX();
        final int y = hit.adjacentY();
        final int z = hit.adjacentZ();
        final IntBounds3 bounds = volume.bounds();
        final boolean inside = bounds.contains(x, y, z);
        final ServerLevel level = (ServerLevel) player.level();
        final double blockScale = structure.blockScale();

        final StructurePlacementLevel virtualLevel = new StructurePlacementLevel(
                level, structure.pose(), blockScale, bounds,
                (sx, sy, sz) -> stateAt(volume, structure, sx, sy, sz));
        if (StructurePlacementValidation.worldObstructed(
                level, structure.pose(), blockScale, x, y, z)) {
            return PlaceResult.FAILED;
        }
        final StructurePlacementValidation.Result validation =
                StructurePlacementValidation.validate(
                        state, volume, virtualLevel,
                        StructurePlacementContext.create(player, player.getMainHandItem(), hit),
                        x, y, z,
                        hit.voxelX(), hit.voxelY(), hit.voxelZ(),
                        blockingEntityBoxes(player, level, structure, x, y, z),
                        structure.pose(), blockScale);
        if (!validation.valid()) {
            return PlaceResult.FAILED;
        }

        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics == null) {
            return PlaceResult.FAILED;
        }
        // Resolve the world centre before expanding/reindexing the volume.
        // reindex keeps the world pose stable, so this is the final location of
        // the placed voxel as well.
        final Vector3d feedbackCenter = structure.blockCenter(x, y, z);

        final BlockPhysicsMapper.BlockProperties properties =
                MinecraftPhysics.blockPhysics().properties(state);
        final double mass = properties.mass() > 0.0
                ? properties.mass()
                : MinecraftPhysics.config().defaultBlockMass;

        final VoxelVolume targetVolume;
        final EditableVolume targetEditable;
        if (inside) {
            targetVolume = volume;
            targetEditable = existing;
        } else {
            // Building on an outer face expands the grid by one cell. The new
            // volume is mutable, so restored structures become editable as soon
            // as the player places the first outside block.
            final IntBounds3 expandedBounds = bounds.union(
                    new IntBounds3(x, y, z, x + 1, y + 1, z + 1));
            final MutableVoxelVolume expanded = new MutableVoxelVolume(expandedBounds);
            copyVolume(structure, volume, expanded);
            targetVolume = expanded;
            targetEditable = editableVolume(expanded);
        }
        if (targetEditable == null) {
            return PlaceResult.FAILED;
        }

        targetEditable.setBlock(x, y, z, true, mass, properties.material(), state);
        StructureBlockStates.set(structure, x, y, z, state);
        try {
            structure.reindex(targetVolume);
        } catch (RuntimeException e) {
            // Roll back the edit if the collider cannot represent it (for
            // example an oversized expanded volume).
            targetEditable.setBlock(x, y, z, false, 0.0, PhysicsMaterial.DEFAULT, null);
            StructureBlockStates.remove(structure, x, y, z);
            return PlaceResult.FAILED;
        }
        final List<PhysicsStructure> placedBodies =
                physics.refreshStructure(structure, targetVolume);

        applyImpulse(player, structure, hit.worldHit(), -MINING_IMPULSE * PLACE_IMPULSE_FRACTION * scale(
                MinecraftPhysics.config().miningImpulseScale));
        StructureFeedback.placeBlock(level, feedbackCenter, state, blockScale,
                level.getRandom());
        // The custom fallback never runs vanilla BlockItem.place/ItemStack.useOn,
        // so award the same placement advancement trigger and item-used stat.
        final PhysicsStructure progressTarget = structure.closed()
                ? nearestStructure(placedBodies, feedbackCenter) : structure;
        if (progressTarget != null) {
            StructurePlayerProgress.onCustomPlace(player, progressTarget, x, y, z,
                    player.getMainHandItem());
        }
        notifyListeners(player, structure, x, y, z, null, state);
        return new PlaceResult(true, x, y, z);
    }

    /**
     * Picks the structure block described by an authoritative hit.
     *
     * <p>The state comes from the structure volume, never from the client. The
     * item is created with the same {@code BlockState#getCloneItemStack} hook
     * vanilla uses, then handed to vanilla's private
     * {@code ServerGamePacketListenerImpl#tryPickItem} through a mixin invoker,
     * so creative hotbar insertion and survival "select matching stack"
     * behaviour stay identical.</p>
     *
     * <p>{@code includeData} is only honoured for players with infinite
     * materials, matching vanilla Ctrl+middle-click. Block data is taken from
     * the structure's own side table; a real block entity is used only when the
     * real world genuinely contains the same block at the hit position.</p>
     *
     * @return whether a non-empty pick stack was handled
     */
    public static boolean pickBlock(ServerPlayer player, StructureBlockHitResult hit,
                                    boolean includeData) {
        final CrythstConfig config = MinecraftPhysics.config();
        if (config == null || !config.structureBlockInteractionEnabled
                || player == null || hit == null || hit.structure() == null) {
            return false;
        }
        final PhysicsStructure structure = hit.structure();
        if (structure.closed()) {
            return false;
        }
        final VoxelVolume volume = structure.volume();
        final int x = hit.voxelX();
        final int y = hit.voxelY();
        final int z = hit.voxelZ();
        if (!volume.bounds().contains(x, y, z) || !volume.isSolid(x, y, z)
                || !(hit.distance() <= player.blockInteractionRange() + 1.0e-6)) {
            return false;
        }

        BlockState state = hit.blockState();
        if (state == null || state.isAir()) {
            state = stateAt(volume, structure, x, y, z);
        }
        if (state == null || state.isAir()) {
            return false;
        }

        final ServerLevel level = (ServerLevel) player.level();
        final boolean withData = includeData && player.hasInfiniteMaterials();
        final ItemStack stack = state.getCloneItemStack(level, hit.getBlockPos(), withData);
        if (stack.isEmpty()) {
            return false;
        }
        if (withData) {
            applyStructureBlockData(level, hit, structure, state, stack);
        }
        // The mixin invoker is the common-side counterpart of vanilla's
        // private tryPickItem. It sends the held-slot packet and refreshes the
        // inventory menu exactly like a real block pick.
        ((ServerGamePacketListenerImplInvoker) player.connection).crythst$tryPickItem(stack);
        return true;
    }

    /**
     * Applies include-data components without reading an unrelated real-world
     * block entity. A real block entity is only used when the real block at the
     * hit position is the same block as the structure voxel. Otherwise the
     * structure's captured block-entity NBT (keyed by structure-local voxel
     * coordinates) is loaded off-level.
     */
    private static void applyStructureBlockData(ServerLevel level, StructureBlockHitResult hit,
                                                PhysicsStructure structure, BlockState state,
                                                ItemStack stack) {
        if (!state.hasBlockEntity()) {
            return;
        }
        final BlockEntity blockEntity = findStructureBlockEntity(level, hit, structure, state);
        if (blockEntity == null) {
            return;
        }
        final TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, level.registryAccess());
        blockEntity.saveCustomOnly(output);
        blockEntity.removeComponentsFromTag(output);
        BlockItem.setBlockEntityData(stack, blockEntity.getType(), output);
        stack.applyComponents(blockEntity.collectComponents());
    }

    private static BlockEntity findStructureBlockEntity(ServerLevel level,
                                                        StructureBlockHitResult hit,
                                                        PhysicsStructure structure,
                                                        BlockState state) {
        final BlockPos pos = hit.getBlockPos();
        if (level.getBlockState(pos).getBlock() == state.getBlock()) {
            final BlockEntity real = level.getBlockEntity(pos);
            if (real != null) {
                return real;
            }
        }
        final IntBounds3 bounds = structure.volume().bounds();
        final BlockPos local = new BlockPos(
                hit.voxelX() - bounds.minX(),
                hit.voxelY() - bounds.minY(),
                hit.voxelZ() - bounds.minZ());
        final CompoundTag tag = StructureBlockEntityData.get(structure).get(local);
        if (tag == null) {
            return null;
        }
        try {
            return BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
        } catch (RuntimeException ignored) {
            // Invalid or outdated NBT must not break pick-block.
            return null;
        }
    }

    /**
     * Collects world-space entity boxes that should block a structure
     * placement. Vanilla's {@code isUnobstructed} only considers entities with
     * {@code blocksBuilding} (players and mobs); hanging entities such as item
     * frames do not set that flag, but a block placed through a frame is just as
     * wrong, so they are included explicitly.
     */
    private static List<AABB> blockingEntityBoxes(ServerPlayer player, ServerLevel level,
                                                  PhysicsStructure structure,
                                                  int x, int y, int z) {
        final AABB query = StructurePlacementValidation.worldVoxelBounds(
                structure.pose(), x, y, z, structure.blockScale()).inflate(1.0e-7);
        final List<Entity> entities = level.getEntities((Entity) null, query,
                (Entity entity) -> blocksPlacement(entity, player));
        final List<AABB> boxes = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            boxes.add(entity.getBoundingBox());
        }
        return boxes;
    }

    private static boolean blocksPlacement(Entity entity, Player player) {
        if (entity == null || entity.isRemoved() || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        if (player != null && entity.isPassengerOfSameVehicle(player)) {
            return false;
        }
        return entity.blocksBuilding || entity instanceof HangingEntity;
    }

    private static void copyVolume(PhysicsStructure structure, VoxelVolume from,
                                   MutableVoxelVolume to) {
        final IntBounds3 bounds = from.bounds();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!from.isSolid(x, y, z)) {
                        continue;
                    }
                    to.setBlock(x, y, z, true, from.blockMass(x, y, z),
                            from.blockMaterial(x, y, z));
                    final BlockState state = stateAt(from, structure, x, y, z);
                    if (state != null && !state.isAir()) {
                        StructureBlockStates.set(structure, x, y, z, state);
                    }
                }
            }
        }
    }

    /**
     * Runs vanilla block-use logic for the structure voxel under an
     * authoritative hit.
     *
     * <p>The live block is read from the auxiliary void level, so buttons,
     * levers, doors and other stateful blocks interact with the same ticking
     * copy that drives their block logic. Containers open their menu through
     * the block state's {@link MenuProvider}; all other blocks are dispatched
     * through {@link BlockState#useItemOn} / {@link BlockState#useWithoutItem}
     * exactly like vanilla's server use-on-block path.</p>
     *
     * <p>The auxiliary level has no players. While vanilla logic runs, its
     * sounds, level events and particles are projected to the host level at the
     * structure's current world position, so nearby players see and hear the
     * interaction.</p>
     *
     * <p>Returns {@link InteractionResult#PASS} when the structure, voxel or
     * live auxiliary block is unavailable. Only a consuming result
     * ({@link InteractionResult#consumesAction()}) should cancel vanilla and
     * suppress item use.</p>
     */
    public static InteractionResult interactBlock(ServerPlayer player, StructureBlockHitResult hit) {
        final CrythstConfig config = MinecraftPhysics.config();
        if (config == null || !config.structureBlockInteractionEnabled
                || player == null || hit == null || hit.structure() == null) {
            return InteractionResult.PASS;
        }
        final PhysicsStructure structure = hit.structure();
        if (structure.closed()) {
            return InteractionResult.PASS;
        }
        final VoxelVolume volume = structure.volume();
        final int x = hit.voxelX();
        final int y = hit.voxelY();
        final int z = hit.voxelZ();
        if (!volume.bounds().contains(x, y, z) || !volume.isSolid(x, y, z)
                || !(hit.distance() <= player.blockInteractionRange() + 1.0e-6)
                || !isStructureInFrontOfRealBlocks(player, hit)) {
            return InteractionResult.PASS;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent((ServerLevel) player.level());
        if (physics == null) {
            return InteractionResult.PASS;
        }
        final EmptyPhysicsLevel.LiveBlock live = physics.liveBlock(structure, x, y, z);
        if (live == null) {
            // The auxiliary chunk is not loaded (or the block was never copied).
            // Do not fall back to the stale side table: menus and block logic
            // need the live block entity/state.
            return InteractionResult.PASS;
        }

        final Level auxiliaryLevel = live.level();
        final BlockPos auxiliaryPos = live.pos();
        final BlockState state = live.state();
        final BlockHitResult auxiliaryHit = auxiliaryHit(structure, hit, auxiliaryPos);

        final boolean projecting = physics.beginAuxiliaryAudioProjection(structure);
        try {
            final ItemStack stack = player.getMainHandItem();
            final ItemStack usedStack = stack.copy();
            final InteractionResult signResult = StructureSignInteraction.tryUse(
                    player, structure, live, auxiliaryHit, stack,
                    InteractionHand.MAIN_HAND, x, y, z);
            if (signResult != null && signResult.consumesAction()) {
                return signResult;
            }
            final MenuProvider menu = state.getMenuProvider(auxiliaryLevel, auxiliaryPos);
            if (menu != null && player.openMenu(menu).isPresent()) {
                return InteractionResult.SUCCESS;
            }

            InteractionResult result;
            boolean emptyHandUse = stack.isEmpty();
            if (stack.isEmpty()) {
                result = state.useWithoutItem(auxiliaryLevel, player, auxiliaryHit);
            } else {
                result = state.useItemOn(stack, auxiliaryLevel, player,
                        InteractionHand.MAIN_HAND, auxiliaryHit);
                if (result instanceof InteractionResult.TryEmptyHandInteraction) {
                    // Mirror vanilla ServerPlayerGameMode: a block that reports
                    // "try with an empty hand" gets its no-item use executed.
                    result = state.useWithoutItem(auxiliaryLevel, player, auxiliaryHit);
                    emptyHandUse = true;
                }
            }
            if (result != null && result.consumesAction()) {
                StructurePlayerProgress.onCustomUse(player, structure, x, y, z,
                        usedStack, emptyHandUse);
            }
            return result == null ? InteractionResult.PASS : result;
        } finally {
            physics.flushAuxiliaryBlockStates();
            if (projecting) {
                physics.endAuxiliaryAudioProjection();
            }
        }
    }

    /**
     * Rebuilds the click in the auxiliary level's coordinate space. Local face
     * and voxel-relative hit fractions are preserved, which matters for blocks
     * such as bells that inspect the clicked height.
     */
    private static BlockHitResult auxiliaryHit(PhysicsStructure structure,
                                               StructureBlockHitResult hit,
                                               BlockPos auxiliaryPos) {
        final Vector3d voxel = structure.worldToVoxel(new Vector3d(), hit.worldHit());
        final double localX = clampFraction(voxel.x - hit.voxelX());
        final double localY = clampFraction(voxel.y - hit.voxelY());
        final double localZ = clampFraction(voxel.z - hit.voxelZ());
        final Vec3 location = new Vec3(
                auxiliaryPos.getX() + localX,
                auxiliaryPos.getY() + localY,
                auxiliaryPos.getZ() + localZ);
        return new BlockHitResult(location, hit.localFace(), auxiliaryPos, false);
    }

    private static double clampFraction(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Shovel recoil against water.
     *
     * <p>Vanilla never sends a left-click packet for a fluid target, so the
     * client hook requests this explicitly. The server verifies the held item,
     * the fluid ray and the config before changing motion.</p>
     *
     * @return whether recoil was applied
     */
    public static boolean shovelWater(ServerPlayer player) {
        final CrythstConfig config = MinecraftPhysics.config();
        final double reaction = Math.max(0.0, config.shovelWaterReaction);
        if (!config.structureBlockInteractionEnabled || reaction <= 0.0) {
            return false;
        }
        final ItemStack stack = player.getMainHandItem();
        if (!stack.is(ItemTags.SHOVELS)) {
            return false;
        }

        final Vec3 eye = player.getEyePosition();
        final Vector3d look = PlayerGravity.lookDirection(player);
        final double reach = player.blockInteractionRange();
        final Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        final BlockHitResult hit = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        final FluidState fluid = player.level().getFluidState(hit.getBlockPos());
        if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) {
            return false;
        }

        final LevelPhysics physics = MinecraftPhysics.getIfPresent((ServerLevel) player.level());
        final Vector3d up = physics == null ? new Vector3d(0.0, 1.0, 0.0) : physics.world().up();
        final Vector3d recoil = new Vector3d(look)
                .mul(-WATER_RECOIL * reaction)
                .fma(WATER_LIFT * reaction, up);
        player.push(recoil.x, recoil.y, recoil.z);
        // The player's position is client-authoritative, so explicitly mirror
        // the new velocity instead of relying on the next movement packet.
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        player.level().playSound(null, hit.getBlockPos(), SoundEvents.GENERIC_SPLASH,
                SoundSource.PLAYERS, 0.7f, 1.15f);
        return true;
    }

    /**
     * Handles a client request from {@link StructureActionPayload}.
     */
    public static boolean handleAction(ServerPlayer player, StructureAction action) {
        if (action == null || !MinecraftPhysics.config().structureBlockInteractionEnabled) {
            return false;
        }
        final boolean picking = action == StructureAction.PICK
                || action == StructureAction.PICK_WITH_DATA;
        if (player.isSpectator() && !picking) {
            return false;
        }
        final boolean result = switch (action) {
            // Legacy one-shot break request. New clients use START/TICK/STOP;
            // retaining MINE keeps older clients working while still preferring
            // the void-level vanilla path.
            case MINE -> mineOnce(player);
            case START_MINE -> startMining(player);
            // Heartbeat plus an optional fresh hit for reach/occlusion. The
            // server tick remains authoritative; a missed client look raycast
            // never kills an otherwise valid long-press.
            case TICK_MINE -> {
                final StructureBlockHitResult hit = raycast(player);
                yield StructureMiningSessions.heartbeat(
                        player, hit != null && isStructureInFrontOfRealBlocks(player, hit)
                                ? hit : null);
            }
            case STOP_MINE -> {
                StructureMiningSessions.stop(player);
                yield true;
            }
            case PLACE, USE -> {
                final InteractionResult use = action == StructureAction.PLACE
                        ? placeFromAction(player) : useFromAction(player);
                if (shouldSwingForUse(use)) {
                    player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                }
                yield use.consumesAction();
            }
            case PICK, PICK_WITH_DATA -> {
                final StructureBlockHitResult hit = raycast(player);
                yield hit != null && isStructureInFrontOfRealBlocks(player, hit)
                        && pickBlock(player, hit,
                                action == StructureAction.PICK_WITH_DATA);
            }
            case SHOVEL_WATER -> shovelWater(player);
        };
        if (result && (action == StructureAction.MINE || action == StructureAction.START_MINE)) {
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        }
        return result;
    }

    static boolean shouldSwingForUse(InteractionResult result) {
        return result instanceof InteractionResult.Success success && success.shouldSwing();
    }

    /**
     * Legacy one-shot MINE. The void redirector breaks through vanilla
     * {@code destroyBlock} when possible, otherwise the old custom volume edit
     * is used.
     */
    static boolean mineOnce(ServerPlayer player) {
        final StructureBlockHitResult hit = raycast(player);
        if (hit == null || !isStructureInFrontOfRealBlocks(player, hit)) {
            return false;
        }
        if (StructureOperationRedirector.enabled()) {
            final StructureOperationRedirector.Result result =
                    StructureOperationRedirector.breakBlock(player, hit);
            if (result.handled()) {
                return true;
            }
            if (!StructureOperationRedirector.shouldFallback(result.status())) {
                return false;
            }
        }
        return beginMine(player) && breakBlock(player, hit).success();
    }

    /**
     * START_MINE: begin/refresh a server-authoritative held-mining session.
     * If the auxiliary block/region is unavailable the custom edit is used so
     * the click is not lost.
     */
    private static boolean startMining(ServerPlayer player) {
        final StructureBlockHitResult hit = raycast(player);
        if (hit == null || !isStructureInFrontOfRealBlocks(player, hit)) {
            return false;
        }
        if (StructureOperationRedirector.enabled()
                && StructureMiningSessions.start(player, hit)) {
            return true;
        }
        // Preserve the real game mode's restrictions (adventure) and use the
        // expanding/custom fallback only when the redirector itself declines.
        return mineOnce(player);
    }

    /**
     * Right-click with a placement item. The void-level block can consume the
     * click first. Entity placement and placement outside the copied volume
     * then use the custom paths.
     */
    private static InteractionResult placeFromAction(ServerPlayer player) {
        final ItemStack stack = player.getMainHandItem();
        if (!StructureItemUse.shouldHandleStructureUse(stack)) {
            return InteractionResult.PASS;
        }
        final StructureBlockHitResult hit = raycast(player);
        if (hit == null || !isStructureInFrontOfRealBlocks(player, hit)) {
            return InteractionResult.PASS;
        }
        if (StructureOperationRedirector.enabled()) {
            if (!beginInteraction(player)) {
                return InteractionResult.CONSUME;
            }
            final StructureOperationRedirector.Result result =
                    StructureOperationRedirector.useOn(player, hit, InteractionHand.MAIN_HAND);
            if (!StructureOperationRedirector.shouldFallback(result.status())) {
                return result.handled() ? result.interaction() : InteractionResult.FAIL;
            }
            // OUT_OF_BOUNDS and unavailable-region statuses fall through to
            // the custom path below.
        }
        if (!beginPlacement(player)) {
            return InteractionResult.CONSUME;
        }
        if (stack.getItem() instanceof BlockItem) {
            final PlaceResult result = placeBlock(player, hit, stack);
            if (result.success()) {
                stack.consume(1, player);
            }
            return result.success() ? InteractionResult.SUCCESS_SERVER : InteractionResult.FAIL;
        }
        final StructureEntityPlacementHandler.Result result =
                StructureEntityPlacement.place(player, stack, hit, InteractionHand.MAIN_HAND);
        if (result.success()) {
            stack.consume(1, player);
        }
        return result.success() ? InteractionResult.SUCCESS_SERVER : InteractionResult.FAIL;
    }

    /**
     * Right-click use action. All ordinary items and empty hands run vanilla
     * use-on/use-without-item logic in the auxiliary level. The custom
     * interaction is only a fallback when the redirector is disabled or the
     * auxiliary block/region is unavailable.
     */
    private static InteractionResult useFromAction(ServerPlayer player) {
        final StructureBlockHitResult hit = raycast(player);
        if (hit == null || !isStructureInFrontOfRealBlocks(player, hit)) {
            return InteractionResult.PASS;
        }
        if (StructureOperationRedirector.enabled()) {
            if (!beginInteraction(player)) {
                return InteractionResult.CONSUME;
            }
            final StructureOperationRedirector.Result result =
                    StructureOperationRedirector.useOn(player, hit, InteractionHand.MAIN_HAND);
            if (!StructureOperationRedirector.shouldFallback(result.status())) {
                return result.handled() ? result.interaction() : InteractionResult.FAIL;
            }
        }
        return interactBlock(player, hit);
    }

    /**
     * Whether a physics-structure hit is actually in front of the vanilla hit
     * the client reported. This prevents intercepting a real block that is
     * closer than a structure behind it.
     */
    public static boolean isStructureInFront(StructureBlockHitResult hit, Vec3 vanillaPoint, ServerPlayer player) {
        return isStructureInFront(hit, vanillaPoint, player == null ? null : player.getEyePosition());
    }

    /**
     * Pure distance check used by the occlusion test and unit tests.
     */
    static boolean isStructureInFront(StructureBlockHitResult hit, Vec3 vanillaPoint, Vec3 eye) {
        if (hit == null || vanillaPoint == null || eye == null) {
            return false;
        }
        return hit.distance() + FRONT_SLACK <= eye.distanceTo(vanillaPoint);
    }

    /**
     * Server-side occlusion check used by item-use placement. It clips against
     * real blocks so a structure behind a wall cannot be edited through it.
     */
    public static boolean isStructureInFrontOfRealBlocks(ServerPlayer player, StructureBlockHitResult hit) {
        if (hit == null) {
            return false;
        }
        final Vec3 eye = player.getEyePosition();
        final Vector3d look = PlayerGravity.lookDirection(player);
        final double reach = player.blockInteractionRange();
        final Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        final BlockHitResult blockHit = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (blockHit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return isStructureInFront(hit, blockHit.getLocation(), player);
    }

    private static void applyImpulse(ServerPlayer player, PhysicsStructure structure,
                                     Vector3dc point, double magnitude) {
        if (Math.abs(magnitude) < 1.0e-6) {
            return;
        }
        final Vector3d impulse = PlayerGravity.lookDirection(player).normalize(magnitude);
        structure.applyImpulseAt(impulse, point);
    }

    private static double scale(double configValue) {
        return Math.max(0.0, configValue);
    }

    /**
     * Chooses the body that should receive a reaction impulse after a possible
     * split: the component whose centre is closest to the edited block.
     */
    private static PhysicsStructure nearestStructure(List<PhysicsStructure> structures, Vector3dc point) {
        PhysicsStructure best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PhysicsStructure candidate : structures) {
            if (candidate.closed()) {
                continue;
            }
            final Vector3d center = candidate.pose().position();
            final double distance = center.distanceSquared(point);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static void notifyListeners(ServerPlayer player, PhysicsStructure structure,
                                        int x, int y, int z, BlockState previous, BlockState next) {
        for (BlockChangeListener listener : LISTENERS) {
            try {
                listener.onBlockChanged(player, structure, x, y, z, previous, next);
            } catch (RuntimeException ignored) {
                // A listener must not break the authoritative edit path.
            }
        }
    }
}
