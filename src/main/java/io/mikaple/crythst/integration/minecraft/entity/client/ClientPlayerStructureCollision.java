package io.mikaple.crythst.integration.minecraft.entity.client;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side structure collision prediction for the local player.
 *
 * <p>Vanilla movement only knows about world blocks, so without this pass a
 * player walking into a physics structure visually sinks into it until the
 * server-side {@code EntityPhysicsCollisionManager} teleports them back. This
 * hook runs after vanilla movement at the end of the client tick and applies
 * the same shallowest-face separation locally.</p>
 *
 * <p>Data sources:</p>
 * <ul>
 *   <li>singleplayer: {@link ClientPhysicsView} plus
 *       {@link PhysicsStructure#renderState(double)}, so the correction uses
 *       the same interpolated pose the renderer draws;</li>
 *   <li>multiplayer: {@link ClientStructureCache}, using each snapshot's last
 *       synced pose and merged boxes rebuilt from its voxel cells. Network
 *       snapshots are only used for body ids that the integrated server did not
 *       publish, which also avoids rebuilding geometry in singleplayer.</li>
 * </ul>
 *
 * <p>Every correction is deliberately conservative: the player must not
 * currently overlap a vanilla block, the corrected box must also be clear of
 * vanilla blocks, and the offset must stay under
 * {@link #MAX_CORRECTION}. Anything larger is left to the server, which is
 * authoritative.</p>
 */
public final class ClientPlayerStructureCollision {
    /** Safety cap; larger penetrations are left to the server. */
    public static final double MAX_CORRECTION = 1.0;

    /** Only the nearest structures are resolved against one player. */
    private static final int MAX_STRUCTURES = 8;

    /** Broad-phase padding around the player box. */
    private static final double BROAD_PHASE_PADDING = 0.1;

    /** Above this per-tick displacement a previous-centre hint is unreliable. */
    private static final double PREVIOUS_HINT_MAX_DISTANCE_SQUARED = 4.0;

    /** Guards against reusing network geometry after a dimension/session change. */
    private static final Map<Long, CachedGeometry> NETWORK_GEOMETRY = new HashMap<>();
    private static long networkGeometrySyncId = Long.MIN_VALUE;

    private ClientPlayerStructureCollision() {
    }

    /**
     * Registers the end-of-client-tick correction. Add this after
     * {@code ClientPhysicsView::tick} so singleplayer render snapshots are
     * refreshed for the current tick.
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientPlayerStructureCollision::onEndClientTick);
    }

    /**
     * Fabric callback: resolve the local player against nearby structures.
     */
    public static void onEndClientTick(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }
        final LocalPlayer player = client.player;
        if (player == null || isIneligible(player)) {
            return;
        }
        final CrythstConfig config = MinecraftPhysics.config();
        if (!config.enabled || !config.entityPhysicsCollisionEnabled) {
            return;
        }

        final Level level = player.level();
        final AABB vanillaBox = player.getBoundingBox();
        // A player already inside a vanilla block is a vanilla/stuck problem;
        // never add a structure push that could move them through terrain.
        // Shrink slightly so a sub-millimetre resting-contact overlap cannot
        // disable prediction forever.
        if (hasVanillaCollision(level, player, shrink(vanillaBox, 1.0e-4))) {
            return;
        }

        final double margin = Math.clamp(config.entityCollisionMargin, 0.0, 0.5);
        final Aabb entityBox = toCore(vanillaBox);
        final Aabb query = entityBox.expand(margin + BROAD_PHASE_PADDING);
        final float alpha = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        final List<ClientStructureCollisionResolver.Collider> candidates =
                collectCandidates(client, query, alpha, margin);
        if (candidates.isEmpty()) {
            return;
        }

        final ClientStructureCollisionResolver.Correction correction =
                ClientStructureCollisionResolver.resolve(
                        entityBox,
                        previousCenter(player, entityBox.center()),
                        candidates,
                        margin,
                        ClientStructureCollisionResolver.DEFAULT_MAX_BOXES,
                        ClientStructureCollisionResolver.DEFAULT_MAX_PASSES,
                        MAX_CORRECTION);
        if (correction == null) {
            return;
        }
        apply(level, player, correction);
    }

    private static boolean isIneligible(LocalPlayer player) {
        return player.isRemoved()
                || player.isSpectator()
                || player.isPassenger()
                || player.noPhysics
                || player.isSleeping()
                || player.isDeadOrDying();
    }

    private static List<ClientStructureCollisionResolver.Collider> collectCandidates(
            Minecraft client, Aabb query, float alpha, double margin) {
        final List<Candidate> found = new ArrayList<>(4);
        final Set<Long> liveBodyIds = new HashSet<>();

        // Singleplayer / integrated server: live physics structures with the
        // interpolated render pose. These win over network data for the same id.
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final VoxelColliderData collider = structure.collider();
            if (collider.isEmpty()) {
                continue;
            }
            final StructureRenderState state = structure.renderState(alpha);
            final Aabb bounds = state.aabb();
            liveBodyIds.add(state.bodyId());
            if (!bounds.intersects(query)) {
                continue;
            }
            found.add(new Candidate(
                    new ClientStructureCollisionResolver.Collider(
                            state.pose(), collider.boxes(), state.blockScale()),
                    bounds));
        }

        // Multiplayer: last synced pose and geometry rebuilt from the cells.
        final Identifier activeDimension = ClientStructureCache.activeDimension();
        if (activeDimension != null
                && activeDimension.equals(client.level.dimension().identifier())) {
            for (ClientStructureSnapshot snapshot : ClientStructureCache.snapshots()) {
                if (snapshot.nonAirBlocks() <= 0 || liveBodyIds.contains(snapshot.bodyId())) {
                    continue;
                }
                final Aabb bounds = networkBounds(snapshot);
                if (!bounds.intersects(query)) {
                    continue;
                }
                final List<VoxelBox> boxes = networkBoxes(snapshot);
                if (boxes.isEmpty()) {
                    continue;
                }
                found.add(new Candidate(
                        new ClientStructureCollisionResolver.Collider(
                                snapshot.pose(), boxes, snapshot.blockScale()),
                        bounds));
            }
        }

        if (found.isEmpty()) {
            return List.of();
        }
        if (found.size() > MAX_STRUCTURES) {
            final Vector3d center = query.center();
            found.sort(Comparator.comparingDouble(
                    candidate -> candidate.bounds().center().distanceSquared(center)));
            found.subList(MAX_STRUCTURES, found.size()).clear();
        }
        final List<ClientStructureCollisionResolver.Collider> result =
                new ArrayList<>(found.size());
        for (Candidate candidate : found) {
            result.add(candidate.collider());
        }
        return result;
    }

    /**
     * The current AABB centre translated back to the previous tick's position.
     * The hint is dropped after a large jump (teleport or lag spike), where it
     * would choose the wrong exit side.
     */
    private static Vector3d previousCenter(LocalPlayer player, Vector3d currentCenter) {
        final Vec3 previous = player.oldPosition();
        final Vec3 position = player.position();
        final double dx = previous.x - position.x;
        final double dy = previous.y - position.y;
        final double dz = previous.z - position.z;
        final double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared < 1.0e-10 || distanceSquared > PREVIOUS_HINT_MAX_DISTANCE_SQUARED) {
            return null;
        }
        return new Vector3d(currentCenter.x + dx, currentCenter.y + dy, currentCenter.z + dz);
    }

    private static void apply(Level level, LocalPlayer player,
                              ClientStructureCollisionResolver.Correction correction) {
        final Vector3d offset = correction.offset();
        final AABB corrected = player.getBoundingBox().move(offset.x, offset.y, offset.z);
        if (hasVanillaCollision(level, player, corrected)) {
            // The structure push would enter terrain; leave it to the server.
            return;
        }

        player.setPos(
                player.getX() + offset.x,
                player.getY() + offset.y,
                player.getZ() + offset.z
        );

        // Remove only the component pointing into the structure. Vanilla
        // movement recomputes the rest next tick, so no impulse is invented.
        final Vector3d normal = correction.normal();
        final Vec3 motion = player.getDeltaMovement();
        final double into = motion.x * normal.x + motion.y * normal.y + motion.z * normal.z;
        if (into < 0.0) {
            player.setDeltaMovement(
                    motion.x - normal.x * into,
                    motion.y - normal.y * into,
                    motion.z - normal.z * into
            );
        }

        if (normal.y > 0.5) {
            // The structure is not a vanilla block; mirror the server-side
            // ground flag so animation and client-side stepping behave.
            player.setOnGround(true);
            player.resetFallDistance();
        }
    }

    private static boolean hasVanillaCollision(Level level, LocalPlayer player, AABB box) {
        for (VoxelShape shape : level.getBlockCollisions(player, box)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static AABB shrink(AABB box, double amount) {
        final double minX = box.minX + amount;
        final double minY = box.minY + amount;
        final double minZ = box.minZ + amount;
        final double maxX = box.maxX - amount;
        final double maxY = box.maxY - amount;
        final double maxZ = box.maxZ - amount;
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
            return box;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * World-space AABB of a network snapshot. Uses the structure pose with
     * unshifted voxel bounds, matching the render convention
     * {@code structurePose * (voxel * blockScale)}.
     *
     * <p>Corners are transformed explicitly, as the multiplayer renderer does,
     * so broad-phase bounds and rendered geometry cannot diverge.</p>
     */
    private static Aabb networkBounds(ClientStructureSnapshot snapshot) {
        final IntBounds3 bounds = snapshot.bounds();
        final Transform3d pose = snapshot.pose();
        final double scale = snapshot.blockScale();
        final double localMinX = bounds.minX() * scale;
        final double localMinY = bounds.minY() * scale;
        final double localMinZ = bounds.minZ() * scale;
        final double localMaxX = bounds.maxX() * scale;
        final double localMaxY = bounds.maxY() * scale;
        final double localMaxZ = bounds.maxZ() * scale;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d point = pose.transformPoint(new Vector3d(
                    (corner & 1) == 0 ? localMinX : localMaxX,
                    (corner & 2) == 0 ? localMinY : localMaxY,
                    (corner & 4) == 0 ? localMinZ : localMaxZ
            ));
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    /**
     * Merged collision boxes for a network snapshot, rebuilt once per geometry
     * revision. Pose-only updates reuse the cached list.
     */
    private static List<VoxelBox> networkBoxes(ClientStructureSnapshot snapshot) {
        final long syncId = ClientStructureCache.activeSyncId();
        if (syncId != networkGeometrySyncId) {
            NETWORK_GEOMETRY.clear();
            networkGeometrySyncId = syncId;
        }
        final CachedGeometry cached = NETWORK_GEOMETRY.get(snapshot.bodyId());
        if (cached != null && cached.geometryRevision() == snapshot.geometryRevision()) {
            return cached.boxes();
        }
        final VoxelColliderData data = VoxelColliderBuilder.build(
                new SnapshotVolume(snapshot),
                VoxelColliderOptions.DEFAULT.withBlockScale(snapshot.blockScale()));
        final List<VoxelBox> boxes = data.boxes();
        NETWORK_GEOMETRY.put(snapshot.bodyId(), new CachedGeometry(
                snapshot.geometryRevision(), boxes));
        return boxes;
    }

    private static Aabb toCore(AABB box) {
        return new Aabb(
                new Vector3d(box.minX, box.minY, box.minZ),
                new Vector3d(box.maxX, box.maxY, box.maxZ)
        );
    }

    private record Candidate(ClientStructureCollisionResolver.Collider collider, Aabb bounds) {
    }

    private record CachedGeometry(long geometryRevision, List<VoxelBox> boxes) {
    }

    /** Adapts a network snapshot to the collider builder's solid-cell view. */
    private record SnapshotVolume(ClientStructureSnapshot snapshot) implements VoxelVolume {
        @Override
        public IntBounds3 bounds() {
            return snapshot.bounds();
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return snapshot.isSolid(x, y, z);
        }
    }
}
