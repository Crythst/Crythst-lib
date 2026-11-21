package io.mikaple.crythst.integration.fabric.client;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.client.render.structure.selection.StructureSelectionMath;
import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureRay;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

/**
 * Draws a white block-selection box around the structure voxel under the
 * crosshair, using the same client raycast as structure interaction.
 */
public final class StructureSelectionRenderer {
    private StructureSelectionRenderer() {
    }

    public static void render(LevelRenderContext context) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || !io.mikaple.crythst.integration.fabric.CrythstFabric.config().structureBlockInteractionEnabled) {
            return;
        }
        final ClientStructureRay.Hit hit = ClientStructureRay.raycast(
                minecraft.player, minecraft.player.blockInteractionRange());
        if (hit == null || hit.pose() == null || hit.point() == null) {
            return;
        }

        // Do not draw a selection box through a closer vanilla block. The
        // structure raycast itself only compares structures; this mirrors the
        // player's normal block pick for occlusion.
        final Vec3 eye = minecraft.player.getEyePosition();
        final Vec3 hitPoint = new Vec3(hit.point().x, hit.point().y, hit.point().z);
        final BlockHitResult blockHit = minecraft.level.clip(new ClipContext(
                eye, hitPoint, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        if (blockHit.getType() != HitResult.Type.MISS) {
            final double blockDistance = Math.sqrt(blockHit.getLocation().distanceToSqr(eye));
            if (blockDistance + 0.01 < hit.distance()) {
                return;
            }
        }

        final Vector3d ray = new Vector3d(
                hit.point().x - eye.x, hit.point().y - eye.y, hit.point().z - eye.z);
        final StructureSelectionMath.SolidCell solid = solidCell(hit, minecraft);
        if (solid == null) {
            return;
        }
        final StructureSelectionMath.Cell cell = StructureSelectionMath.findCell(
                hit.pose(), hit.point(), ray, hit.blockScale(), solid);
        if (cell == null) {
            return;
        }

        final Transform3d pose = hit.pose();
        final double scale = hit.blockScale();
        drawCube(pose, cell.x() * scale, cell.y() * scale, cell.z() * scale,
                (cell.x() + 1) * scale, (cell.y() + 1) * scale, (cell.z() + 1) * scale);
    }

    /**
     * Builds the structure-local solidity test for the hit body. Network
     * snapshots are authoritative in multiplayer; in singleplayer the
     * render-thread collider boxes are immutable and already merged.
     */
    private static StructureSelectionMath.SolidCell solidCell(ClientStructureRay.Hit hit,
                                                              Minecraft minecraft) {
        final float alpha = minecraft.getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            final StructureRenderState state = structure.renderState(alpha);
            if (state.bodyId() != hit.bodyId()) {
                continue;
            }
            final List<VoxelBox> boxes = state.collider().boxes();
            return (x, y, z) -> {
                for (VoxelBox box : boxes) {
                    if (x >= box.minX() && x < box.minX() + box.sizeX()
                            && y >= box.minY() && y < box.minY() + box.sizeY()
                            && z >= box.minZ() && z < box.minZ() + box.sizeZ()) {
                        return true;
                    }
                }
                return false;
            };
        }

        final ClientStructureSnapshot network = ClientStructureCache.get(hit.bodyId());
        if (network != null) {
            return (x, y, z) -> {
                try {
                    return network.isSolid(x, y, z);
                } catch (RuntimeException ignored) {
                    return false;
                }
            };
        }
        return null;
    }

    private static void drawCube(Transform3d pose, double x0, double y0, double z0,
                                 double x1, double y1, double z1) {
        final Vector3d[] c = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            c[i] = pose.transformPoint(new Vector3d(
                    (i & 1) == 0 ? x0 : x1,
                    (i & 2) == 0 ? y0 : y1,
                    (i & 4) == 0 ? z0 : z1));
        }
        int[][] edges = {
                {0, 1}, {2, 3}, {4, 5}, {6, 7},
                {0, 2}, {1, 3}, {4, 6}, {5, 7},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] edge : edges) {
            Gizmos.line(toVec(c[edge[0]]), toVec(c[edge[1]]), 0xFFFFFFFF, 2.0f);
        }
    }

    private static Vec3 toVec(Vector3dc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
