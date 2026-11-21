package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
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

    private StructureInteraction() {
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
        if (!(volume instanceof MutableVoxelVolume mutable)) {
            // BlockVoxelVolume is a read-only view over real Minecraft blocks.
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

        final BlockState previousState = StructureBlockStates.remove(structure, x, y, z);
        mutable.setSolid(x, y, z, false);

        if (!hasSolidBlock(volume)) {
            // PhysicsStructure.reindex() rejects an empty volume. Removing the
            // last block therefore removes the body instead.
            StructureBlockStates.clear(structure);
            physics.removeStructure(structure);
            notifyListeners(player, structure, x, y, z, previousState, null);
            return new BreakResult(true, true, previousState);
        }

        structure.reindex(volume);
        physics.refreshStructure(structure, volume);

        applyImpulse(player, structure, hit.point(), -MINING_IMPULSE * scale(
                MinecraftPhysics.config().miningImpulseScale));
        playSound(player, hit.point(), previousState, true);
        notifyListeners(player, structure, x, y, z, previousState, null);
        return new BreakResult(true, false, previousState);
    }

    /**
     * Adds a block into the structure voxel adjacent to the hit face.
     *
     * <p>The volume is not expanded: the target cell must already be inside the
     * volume bounds and empty. A small reaction impulse is applied, mirroring
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
        if (!(volume instanceof MutableVoxelVolume mutable)) {
            return PlaceResult.FAILED;
        }
        final int x = hit.adjacentX();
        final int y = hit.adjacentY();
        final int z = hit.adjacentZ();
        final IntBounds3 bounds = volume.bounds();
        if (!bounds.contains(x, y, z) || volume.isSolid(x, y, z)) {
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
        mutable.setBlock(x, y, z, true, mass, properties.material());
        StructureBlockStates.set(structure, x, y, z, state);
        structure.reindex(volume);
        physics.refreshStructure(structure, volume);

        applyImpulse(player, structure, hit.point(), -MINING_IMPULSE * PLACE_IMPULSE_FRACTION * scale(
                MinecraftPhysics.config().miningImpulseScale));
        playSound(player, structure.blockCenter(x, y, z), state, false);
        notifyListeners(player, structure, x, y, z, null, state);
        return new PlaceResult(true, x, y, z);
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
        return StructureBlockStates.get(hit.structure(), hit.voxelX(), hit.voxelY(), hit.voxelZ());
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
                yield hit != null && breakBlock(player, hit).success();
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
        final Vector3d voxel = structure.worldToVoxel(new Vector3d(), rayHit.point());
        final int baseX = (int) Math.round(voxel.x);
        final int baseY = (int) Math.round(voxel.y);
        final int baseZ = (int) Math.round(voxel.z);

        int bestX = baseX;
        int bestY = baseY;
        int bestZ = baseZ;
        double bestDistance = Double.POSITIVE_INFINITY;
        final IntBounds3 bounds = volume.bounds();
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
        return new StructureHit(structure, rayHit, bestX, bestY, bestZ,
                localFace(structure, rayHit.normal()));
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

    private static boolean hasSolidBlock(VoxelVolume volume) {
        final IntBounds3 bounds = volume.bounds();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (volume.isSolid(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
