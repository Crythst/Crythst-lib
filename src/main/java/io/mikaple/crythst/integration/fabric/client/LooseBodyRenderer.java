package io.mikaple.crythst.integration.fabric.client;

import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.debug.BodyDebugInfo;
import io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws loose dynamic/kinematic bodies (for example the block spawned by
 * {@code /crythst cube}) as filled oriented boxes. This is independent of the
 * debug-overlay switch so test bodies are always visible.
 */
public final class LooseBodyRenderer {
    private LooseBodyRenderer() {
    }

    public static void emit(float alpha) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final PhysicsDebugSnapshot debug = ClientPhysicsView.debug();
        if (debug == null) {
            return;
        }
        final Set<Long> structureIds = new HashSet<>();
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            structureIds.add(structure.renderState(alpha).bodyId());
        }
        for (BodyDebugInfo body : debug.bodies()) {
            if (body.terrain() || structureIds.contains(body.id())
                    || body.type() == BodyType.STATIC) {
                continue;
            }
            drawOrientedBox(body.obb(), 0xFFE6E6E6, 0x6680C8FF, 1.5f);
        }
    }

    private static void drawOrientedBox(OrientedBox box, int edgeColor, int fillColor, float width) {
        final List<Vector3d> corners = box.corners();
        final Vec3[] points = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            points[i] = toVec(corners.get(i));
        }
        final GizmoStyle fill = GizmoStyle.fill(fillColor);
        Gizmos.rect(points[0], points[2], points[3], points[1], fill);
        Gizmos.rect(points[4], points[5], points[7], points[6], fill);
        Gizmos.rect(points[0], points[1], points[5], points[4], fill);
        Gizmos.rect(points[2], points[6], points[7], points[3], fill);
        Gizmos.rect(points[0], points[4], points[6], points[2], fill);
        Gizmos.rect(points[1], points[3], points[7], points[5], fill);

        int[][] edges = {
                {0, 1}, {2, 3}, {4, 5}, {6, 7},
                {0, 2}, {1, 3}, {4, 6}, {5, 7},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] edge : edges) {
            Gizmos.line(points[edge[0]], points[edge[1]], edgeColor, width);
        }
    }

    private static Vec3 toVec(Vector3dc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
