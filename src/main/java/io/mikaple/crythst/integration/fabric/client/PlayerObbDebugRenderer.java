package io.mikaple.crythst.integration.fabric.client;

import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.integration.minecraft.player.PlayerObb;
import io.mikaple.crythst.math.OrientedBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;

/**
 * Oriented hitbox for players with directional gravity, shown by F3 + B.
 *
 * <p>Vanilla's hitbox renderer draws an axis-aligned box. Directional-gravity
 * players use an oriented box whose local +Y follows the gravity up vector, so
 * the vanilla box would be misleading. The mixin on
 * {@code EntityHitboxDebugRenderer} suppresses the vanilla box for those
 * players and this renderer draws the real OBB.</p>
 */
public final class PlayerObbDebugRenderer implements DebugRenderer.SimpleDebugRenderer {
    private static final int EDGE = 0xFFFF66CC;
    private static final int FILL = 0x22FF66CC;

    @Override
    public void emitGizmos(double cameraX, double cameraY, double cameraZ,
                           DebugValueAccess debugValueAccess, Frustum frustum,
                           float partialTicks) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || !PlayerGravity.hasDirectionalGravity(player)) {
                continue;
            }
            // Match vanilla's own F3 + B visibility rule: no box around the
            // first-person camera entity.
            if (entity.isInvisible()
                    || (entity == minecraft.getCameraEntity()
                    && minecraft.options.getCameraType().isFirstPerson())) {
                continue;
            }
            // The collision OBB is built from the tick position, which is the
            // feet anchor in the gravity frame. Shift it by the same
            // interpolation the entity renderer uses, so the F3 + B box sits
            // exactly on the rendered avatar.
            final Vec3 tickPosition = player.position();
            final Vec3 renderPosition = player.getPosition(partialTicks);
            final Vec3 delta = renderPosition.subtract(tickPosition);
            final OrientedBox box = PlayerObb.obb(player);
            draw(delta.lengthSqr() < 1.0e-12 ? box : new OrientedBox(
                    box.center().add(delta.x, delta.y, delta.z),
                    box.halfExtents(), box.rotation()), EDGE, FILL);
        }
    }

    private static void draw(OrientedBox box, int edge, int fill) {
        final List<Vector3d> corners = box.corners();
        final Vec3[] points = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            final Vector3d corner = corners.get(i);
            points[i] = new Vec3(corner.x, corner.y, corner.z);
        }
        final GizmoStyle filled = GizmoStyle.fill(fill);
        Gizmos.rect(points[0], points[2], points[3], points[1], filled);
        Gizmos.rect(points[4], points[5], points[7], points[6], filled);
        Gizmos.rect(points[0], points[1], points[5], points[4], filled);
        Gizmos.rect(points[2], points[6], points[7], points[3], filled);
        Gizmos.rect(points[0], points[4], points[6], points[2], filled);
        Gizmos.rect(points[1], points[3], points[7], points[5], filled);

        int[][] edges = {
                {0, 1}, {2, 3}, {4, 5}, {6, 7},
                {0, 2}, {1, 3}, {4, 6}, {5, 7},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] e : edges) {
            Gizmos.line(points[e[0]], points[e[1]], edge, 2.0f);
        }
    }
}
