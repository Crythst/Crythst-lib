package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolume;
import io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.query.BodyFilter;
import io.mikaple.crythst.physics.query.RayHit;
import io.mikaple.crythst.physics.query.RayQuery;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

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
    /** Distance slack used when deciding whether a structure is in front of a vanilla hit. */
    public static final double FRONT_SLACK = 0.35;

    private static final List<BlockChangeListener> LISTENERS = new CopyOnWriteArrayList<>();
    /**
     * Two ticks is enough to merge the vanilla use-item/use-block packet with
     * the PLACE payload from the same right-click. Vanilla cannot place twice
     * that quickly. Keyed weakly because ServerPlayer instances are long-lived.
     */
    private static final Map<ServerPlayer, Long> LAST_PLACEMENT_TICK = new WeakHashMap<>();

    /**
     * Minimal edit surface shared by {@link MutableVoxelVolume} and restored
     * stateful volumes. Keeping this adapter here avoids forcing every volume
     * type to implement a common mutable interface.
     */
    private interface EditableVolume {
        void setBlock(int x, int y, int z, boolean solid, double mass,
                      PhysicsMaterial material, BlockState state);
    }

    private StructureInteraction() {
    }

    private static EditableVolume editableVolume(VoxelVolume volume) {
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
        return StructureBlockStates.get(structure, x, y, z);
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
     * Raycasts the player's look vector against every live structure body.
     *
     * @return the nearest structure hit, or {@code null}
     */
    public static StructureHit raycast(ServerPlayer player, double reach) {
        final CrythstConfig config = MinecraftPhysics.config();
        if (!config.structureBlockInteractionEnabled || reach <= 0.0) {
            return null;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent((ServerLevel) player.level());
        if (physics == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition();
        final Vector3d direction = PlayerGravity.lookDirection(player);
        final Vector3d from = new Vector3d(eye.x, eye.y, eye.z);
        if (direction.lengthSquared() < 1.0e-12) {
            return null;
        }
        final PhysicsWorld world = physics.world();
        final BodyFilter filter = body -> body.userData() instanceof PhysicsStructure structure
                && !structure.closed();
        final List<RayHit> hits = world.raycast(RayQuery.fromDirection(from, direction, reach)
                .withFilter(filter));
        for (RayHit hit : hits) {
            if (!(hit.body().userData() instanceof PhysicsStructure structure) || structure.closed()) {
                continue;
            }
            final StructureHit resolved = resolve(structure, hit);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    public static StructureHit raycast(ServerPlayer player) {
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
     * Removes one solid voxel from a mutable structure volume, rebuilds the
     * collider, refreshes the auxiliary block-ticking level and applies a
     * reaction impulse opposite to the player's look direction.
     */
    public static BreakResult breakBlock(ServerPlayer player, StructureHit hit) {
        if (!MinecraftPhysics.config().structureBlockInteractionEnabled || hit == null) {
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

        final BlockState previousState = stateAt(volume, structure, x, y, z);
        final double previousMass = volume.blockMass(x, y, z);
        final PhysicsMaterial previousMaterial = volume.blockMaterial(x, y, z);
        StructureBlockStates.remove(structure, x, y, z);
        editable.setBlock(x, y, z, false, 0.0, PhysicsMaterial.DEFAULT, null);

        if (StructureSplitter.isMassless(volume)) {
            // A body with no solid cells or zero total mass cannot be
            // simulated, so it is cleared instead of reindexed.
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
        // Removing a block can disconnect the remaining solid cells. The
        // refresh pass also performs that split and returns the bodies that now
        // represent the structure.
        final List<PhysicsStructure> remaining = physics.refreshStructure(structure, volume);
        final PhysicsStructure reactionTarget = nearestStructure(remaining, hit.point());

        if (reactionTarget != null) {
            applyImpulse(player, reactionTarget, hit.point(), -MINING_IMPULSE * scale(
                    MinecraftPhysics.config().miningImpulseScale));
        }
        playSound(player, hit.point(), previousState, true);
        notifyListeners(player, reactionTarget != null ? reactionTarget : structure,
                x, y, z, previousState, null);
        return new BreakResult(true, false, previousState);
    }

    /**
     * Adds a block into the structure voxel adjacent to the hit face.
     *
     * <p>If the target is inside the current bounds it must be empty. If it is
     * outside (building on an outer face), the grid is expanded by one cell and
     * copied into a new mutable volume; a restored
     * {@code RestoredStructureVolume} therefore becomes editable after the
     * first outside placement. A small reaction impulse is applied, mirroring
     * the mining response.</p>
     */
    public static PlaceResult placeBlock(ServerPlayer player, StructureHit hit, BlockState state) {
        if (!MinecraftPhysics.config().structureBlockInteractionEnabled
                || hit == null || state == null || state.isAir()) {
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
        if (inside && volume.isSolid(x, y, z)) {
            return PlaceResult.FAILED;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent((ServerLevel) player.level());
        if (physics == null) {
            return PlaceResult.FAILED;
        }

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
        physics.refreshStructure(structure, targetVolume);

        applyImpulse(player, structure, hit.point(), -MINING_IMPULSE * PLACE_IMPULSE_FRACTION * scale(
                MinecraftPhysics.config().miningImpulseScale));
        playSound(player, structure.blockCenter(x, y, z), state, false);
        notifyListeners(player, structure, x, y, z, null, state);
        return new PlaceResult(true, x, y, z);
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
     * Placeholder for future right-click behaviour on structure blocks.
     *
     * @return the side-table state at the hit voxel, or {@code null}. No
     *         mutation or vanilla block-use dispatch is performed yet.
     */
    public static BlockState interactBlock(ServerPlayer player, StructureHit hit) {
        if (hit == null || hit.structure().closed()) {
            return null;
        }
        return stateAt(hit.structure().volume(), hit.structure(),
                hit.voxelX(), hit.voxelY(), hit.voxelZ());
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
        if (action == null || !MinecraftPhysics.config().structureBlockInteractionEnabled
                || player.isSpectator()) {
            return false;
        }
        return switch (action) {
            case MINE -> {
                final StructureHit hit = raycast(player);
                yield hit != null && isStructureInFrontOfRealBlocks(player, hit)
                        && breakBlock(player, hit).success();
            }
            case PLACE -> {
                final ItemStack stack = player.getMainHandItem();
                if (!(stack.getItem() instanceof BlockItem blockItem)) {
                    yield false;
                }
                final StructureHit hit = raycast(player);
                if (hit == null || !isStructureInFrontOfRealBlocks(player, hit)
                        || !beginPlacement(player)) {
                    yield false;
                }
                final PlaceResult result = placeBlock(player, hit,
                        blockItem.getBlock().defaultBlockState());
                if (result.success() && !player.isCreative()) {
                    stack.shrink(1);
                }
                yield result.success();
            }
            case SHOVEL_WATER -> shovelWater(player);
        };
    }

    /**
     * Whether a physics-structure hit is actually in front of the vanilla hit
     * the client reported. This prevents intercepting a real block that is
     * closer than a structure behind it.
     */
    public static boolean isStructureInFront(StructureHit hit, Vec3 vanillaPoint, ServerPlayer player) {
        if (hit == null || vanillaPoint == null) {
            return false;
        }
        return hit.distance() + FRONT_SLACK
                <= player.getEyePosition().distanceTo(vanillaPoint);
    }

    /**
     * Server-side occlusion check used by item-use placement. It clips against
     * real blocks so a structure behind a wall cannot be edited through it.
     */
    public static boolean isStructureInFrontOfRealBlocks(ServerPlayer player, StructureHit hit) {
        if (hit == null) {
            return false;
        }
        final Vec3 eye = player.getEyePosition();
        final Vector3d look = PlayerGravity.lookDirection(player);
        final double reach = player.blockInteractionRange();
        final Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        final BlockHitResult blockHit = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (blockHit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return isStructureInFront(hit, blockHit.getLocation(), player);
    }

    private static StructureHit resolve(PhysicsStructure structure, RayHit rayHit) {
        final VoxelVolume volume = structure.volume();
        // Step a tiny amount inside the surface before quantising. Without
        // this, a hit exactly on a +X/+Y/+Z face rounds into the empty cell on
        // the other side of the face.
        final double inset = Math.max(1.0e-6, structure.blockScale() * 1.0e-4);
        final Vector3d inside = new Vector3d(rayHit.point())
                .fma(-inset, rayHit.normal());
        final Vector3d voxel = structure.worldToVoxel(new Vector3d(), inside);
        final int baseX = (int) Math.round(voxel.x);
        final int baseY = (int) Math.round(voxel.y);
        final int baseZ = (int) Math.round(voxel.z);
        final Direction face = localFace(structure, rayHit.normal());
        final IntBounds3 bounds = volume.bounds();

        if (bounds.contains(baseX, baseY, baseZ) && volume.isSolid(baseX, baseY, baseZ)) {
            return new StructureHit(structure, rayHit, baseX, baseY, baseZ, face);
        }

        int bestX = baseX;
        int bestY = baseY;
        int bestZ = baseZ;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    final int x = baseX + dx;
                    final int y = baseY + dy;
                    final int z = baseZ + dz;
                    if (!bounds.contains(x, y, z) || !volume.isSolid(x, y, z)) {
                        continue;
                    }
                    final double distance = voxel.distanceSquared(x, y, z);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestX = x;
                        bestY = y;
                        bestZ = z;
                    }
                }
            }
        }
        if (bestDistance == Double.POSITIVE_INFINITY) {
            return null;
        }
        return new StructureHit(structure, rayHit, bestX, bestY, bestZ, face);
    }

    /**
     * Converts the world-space surface normal into a structure-local cardinal
     * direction. Placement uses {@code voxel + face}, so a tilted structure
     * still gets the visually adjacent cell.
     */
    private static Direction localFace(PhysicsStructure structure, Vector3dc worldNormal) {
        final Quaterniond inverse = new Quaterniond(structure.body().transform().rotation()).conjugate();
        final Vector3d local = new Vector3d(worldNormal).rotate(inverse);
        final double ax = Math.abs(local.x);
        final double ay = Math.abs(local.y);
        final double az = Math.abs(local.z);
        if (ax < 1.0e-9 && ay < 1.0e-9 && az < 1.0e-9) {
            return Direction.UP;
        }
        if (ax >= ay && ax >= az) {
            return local.x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= ax && ay >= az) {
            return local.y >= 0.0 ? Direction.UP : Direction.DOWN;
        }
        return local.z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
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

    private static void playSound(ServerPlayer player, Vector3dc point, BlockState state,
                                  boolean breaking) {
        if (state == null) {
            return;
        }
        final SoundType sound = state.getSoundType();
        player.level().playSound(null, point.x(), point.y(), point.z(),
                breaking ? sound.getBreakSound() : sound.getPlaceSound(),
                SoundSource.BLOCKS, 0.8f, 0.95f + player.getRandom().nextFloat() * 0.1f);
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
