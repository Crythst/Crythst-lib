package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.client.render.structure.selection.StructureSelectionMath;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.interaction.StructurePlacementMath;
import io.mikaple.crythst.integration.minecraft.interaction.StructureRayMath;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;

/**
 * Client-side raycast against the render snapshots published by
 * {@link ClientPhysicsView} and the multiplayer snapshots in
 * {@link ClientStructureCache}.
 *
 * <p>The hit includes the structure voxel and surface normal, so the outline,
 * crosshair and interaction code all target exactly the same cell. It never
 * mutates physics state. In singleplayer the integrated server publishes
 * structure snapshots; in multiplayer the synced list is used instead.</p>
 */
public final class ClientStructureRay {
    private static final double EPSILON = 1.0e-9;

    private ClientStructureRay() {
    }

    /**
     * @param structure structure that was hit
     * @param distance  world-space distance from the eye to the hit
     * @param voxelX    structure-local voxel coordinate
     * @param voxelY    structure-local voxel coordinate
     * @param voxelZ    structure-local voxel coordinate
     * @param normal    world-space outward surface normal at the hit
     */
    public record Hit(long bodyId, double distance, Transform3d pose,
                      org.joml.Vector3d point, double blockScale,
                      int voxelX, int voxelY, int voxelZ,
                      org.joml.Vector3d normal) {

        public StructureSelectionMath.Cell cell() {
            return new StructureSelectionMath.Cell(voxelX, voxelY, voxelZ);
        }

        /**
         * Structure-local face of the hit voxel.
         */
        public Direction localFace() {
            return StructureSelectionMath.localFace(pose, point, blockScale, cell());
        }

        /**
         * World-space face, used for the synthetic vanilla hit result.
         */
        public Direction worldFace() {
            return StructurePlacementMath.worldFace(pose, localFace());
        }

        /**
         * Vanilla-shaped click at this structure voxel. The client and server
         * both build the placement context from this same geometry.
         */
        public BlockHitResult clickResult() {
            return StructurePlacementMath.blockHit(point, worldFace());
        }
    }

    /**
     * @return the nearest structure hit within {@code reach}, or {@code null}
     */
    public static Hit raycast(LocalPlayer player, double reach) {
        if (player == null || reach <= 0.0) {
            return null;
        }
        final Vec3 eye = player.getEyePosition();
        final Vector3d direction = PlayerGravity.lookDirection(player);
        final Vector3d origin = new Vector3d(eye.x, eye.y, eye.z);
        if (direction.lengthSquared() < EPSILON) {
            return null;
        }
        direction.normalize();

        final double alpha = Minecraft.getInstance().getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        double bestDistance = reach;
        StructureRenderState bestState = null;
        StructureRayMath.BoxHit bestBoxHit = null;

        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final StructureRenderState state = structure.renderState(alpha);
            final StructureRayMath.BoxHit boxHit = raycast(state, origin, direction, bestDistance);
            if (boxHit != null && boxHit.distance() < bestDistance) {
                bestDistance = boxHit.distance();
                bestState = state;
                bestBoxHit = boxHit;
            }
        }

        if (bestState != null) {
            final Hit hit = resolveCell(bestState, bestBoxHit, origin, direction);
            if (hit != null) {
                return hit;
            }
        }

        // Multiplayer: the integrated server is absent, so use the synced
        // block-state snapshots instead of ClientPhysicsView. Snapshot cells
        // are tested directly, which also gives the exact voxel for the hit.
        double bestSnapshotDistance = reach;
        ClientStructureSnapshot bestSnapshot = null;
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        StructureRayMath.BoxHit bestSnapshotBox = null;
        for (ClientStructureSnapshot snapshot : ClientStructureCache.snapshots()) {
            final SnapshotHit snapshotHit = raycast(snapshot, origin, direction, bestSnapshotDistance);
            if (snapshotHit != null && snapshotHit.distance() < bestSnapshotDistance) {
                bestSnapshotDistance = snapshotHit.distance();
                bestSnapshot = snapshot;
                bestX = snapshotHit.x();
                bestY = snapshotHit.y();
                bestZ = snapshotHit.z();
                bestSnapshotBox = snapshotHit.boxHit();
            }
        }
        if (bestSnapshot == null) {
            return null;
        }
        final Vector3d point = new Vector3d(origin)
                .add(new Vector3d(direction).mul(bestSnapshotDistance));
        final Vector3d normal = new Vector3d(bestSnapshotBox.normalX(),
                bestSnapshotBox.normalY(), bestSnapshotBox.normalZ())
                .rotate(bestSnapshot.pose().rotation());
        return new Hit(bestSnapshot.bodyId(), bestSnapshotDistance, bestSnapshot.pose(),
                point, bestSnapshot.blockScale(), bestX, bestY, bestZ, normal);
    }

    /**
     * Resolves the exact structure voxel from a merged-collider hit. The
     * collider only stores merged boxes, so the cell is recovered from the
     * structure-local hit point and the same solidity test the renderer uses.
     */
    private static Hit resolveCell(StructureRenderState state, StructureRayMath.BoxHit boxHit,
                                   Vector3d origin, Vector3d direction) {
        final Vector3d point = new Vector3d(origin)
                .add(new Vector3d(direction).mul(boxHit.distance()));
        final List<VoxelBox> boxes = state.collider().boxes();
        final StructureSelectionMath.SolidCell solid = (x, y, z) -> {
            for (VoxelBox box : boxes) {
                if (x >= box.minX() && x < box.minX() + box.sizeX()
                        && y >= box.minY() && y < box.minY() + box.sizeY()
                        && z >= box.minZ() && z < box.minZ() + box.sizeZ()) {
                    return true;
                }
            }
            return false;
        };
        StructureSelectionMath.Cell cell = StructureSelectionMath.findCell(
                state.pose(), point, direction, state.blockScale(), solid);
        if (cell == null) {
            // Very small/merged shapes can miss the neighborhood search; fall
            // back to the local point without requiring solidity.
            cell = StructureSelectionMath.cellAt(state.pose(), point, state.blockScale());
        }
        if (cell == null) {
            return null;
        }
        return new Hit(state.bodyId(), boxHit.distance(), state.pose(), point,
                state.blockScale(), cell.x(), cell.y(), cell.z(),
                new Vector3d(boxHit.normalX(), boxHit.normalY(), boxHit.normalZ()));
    }

    private record SnapshotHit(double distance, int x, int y, int z,
                               StructureRayMath.BoxHit boxHit) {
    }

    /**
     * Raycast a synchronized structure in structure-local voxel space. The
     * snapshot has block cells rather than merged collider boxes, so the cells
     * are tested directly (bounded by the volume size).
     */
    private static SnapshotHit raycast(ClientStructureSnapshot snapshot, Vector3d origin,
                                       Vector3d direction, double maxDistance) {
        final Transform3d pose = snapshot.pose();
        final double scale = snapshot.blockScale();
        final Vector3d localOrigin = pose.inverseTransformPoint(origin);
        final Vector3d localDirection = pose.inverseTransformDirection(direction);
        final var bounds = snapshot.bounds();
        final long cells = bounds.volume();
        if (cells <= 0L || cells > 262_144L) {
            return null;
        }

        double best = maxDistance;
        SnapshotHit bestHit = null;
        // Iterate all solid cells; synchronized structures are bounded and the
        // camera reach is short, so this is cheaper than building a collider on
        // the client thread.
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (!snapshot.isSolid(x, y, z)) {
                        continue;
                    }
                    final StructureRayMath.BoxHit boxHit = StructureRayMath.rayBoxHit(
                            localOrigin, localDirection,
                            x * scale, y * scale, z * scale,
                            (x + 1) * scale, (y + 1) * scale, (z + 1) * scale,
                            best);
                    if (boxHit != null && boxHit.distance() < best) {
                        best = boxHit.distance();
                        bestHit = new SnapshotHit(best, x, y, z, boxHit);
                    }
                }
            }
        }
        return bestHit;
    }

    /**
     * @return the nearest collider-box hit, or {@code null} on a miss
     */
    private static StructureRayMath.BoxHit raycast(StructureRenderState state, Vector3d origin,
                                                   Vector3d direction, double maxDistance) {
        if (state.collider().boxes().isEmpty()) {
            return null;
        }
        final Transform3d pose = state.pose();
        final Vector3d centerOfMass = state.centerOfMassOffset();
        final Transform3d body = Transform3d.of(
                new Vector3d(pose.position()).add(new Vector3d(centerOfMass).rotate(pose.rotation())),
                pose.rotation());
        final Vector3d localOrigin = body.inverseTransformPoint(origin);
        final Vector3d localDirection = body.inverseTransformDirection(direction);
        final double scale = state.blockScale();

        double best = maxDistance;
        StructureRayMath.BoxHit bestHit = null;
        for (VoxelBox box : state.collider().boxes()) {
            final double minX = box.minX() * scale - centerOfMass.x;
            final double minY = box.minY() * scale - centerOfMass.y;
            final double minZ = box.minZ() * scale - centerOfMass.z;
            final double maxX = (box.minX() + box.sizeX()) * scale - centerOfMass.x;
            final double maxY = (box.minY() + box.sizeY()) * scale - centerOfMass.y;
            final double maxZ = (box.minZ() + box.sizeZ()) * scale - centerOfMass.z;
            final StructureRayMath.BoxHit hit = StructureRayMath.rayBoxHit(
                    localOrigin, localDirection,
                    minX, minY, minZ, maxX, maxY, maxZ, best);
            if (hit != null && hit.distance() < best) {
                best = hit.distance();
                // Normals are in body-local space; rotate them into world
                // space so callers get a usable world face.
                final Vector3d normal = new Vector3d(hit.normalX(), hit.normalY(), hit.normalZ())
                        .rotate(pose.rotation());
                bestHit = new StructureRayMath.BoxHit(hit.distance(),
                        normal.x, normal.y, normal.z);
            }
        }
        return bestHit;
    }
}
