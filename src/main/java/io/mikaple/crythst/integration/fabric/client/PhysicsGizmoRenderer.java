package io.mikaple.crythst.integration.fabric.client;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.collision.ContactEvent;
import io.mikaple.crythst.physics.collision.ContactPoint;
import io.mikaple.crythst.physics.debug.BodyDebugInfo;
import io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Debug-only gizmo renderer used to validate the physics integration.
 *
 * <p>The renderer visualises the merged voxel boxes of every physics structure
 * as tilted {@link OrientedBox}es, their overall bounds, their centre of mass,
 * and the raw {@link PhysicsDebugSnapshot} exported by the physics world. It is
 * intended for singleplayer debugging only and is disabled unless
 * {@code debug} is enabled in the Crythst config.</p>
 *
 * <p>This renderer is an independent implementation written against the Crythst
 * data model and the vanilla gizmo API. It intentionally does not copy or reuse
 * any Sable rendering code.</p>
 */
public final class PhysicsGizmoRenderer {
    private static final int COLOR_CYAN = 0xFF00FFFF;
    private static final int FILL_CYAN = 0x1800FFFF;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_GRAY = 0xFF505050;
    private static final int COLOR_YELLOW = 0xFFFFFF00;
    private static final int COLOR_GREEN = 0xFF00FF00;
    private static final int COLOR_MAGENTA = 0xFFFF00FF;
    private static final int COLOR_ORANGE = 0xFFFF8C1A;
    private static final int COLOR_ORIGIN = 0xFFFFC400;
    private static final int COLOR_CONTACT = 0xFFFF3030;
    private static final int COLOR_CONTACT_NORMAL = 0xFFFFAA00;

    private static final int COLOR_ICE = 0xFF7FDBFF;
    private static final int COLOR_WOOD = 0xFF8B5A2B;
    private static final int COLOR_METAL = 0xFFB0B0B0;
    private static final int COLOR_DIRT = 0xFF7B6B3A;

    private PhysicsGizmoRenderer() {
    }

    /**
     * Draws the current physics debug view. Invoked from
     * {@code LevelRenderEvents.BEFORE_GIZMOS}, while the gizmo collector is
     * active.
     */
    public static void render(LevelRenderContext context) {
        if (Minecraft.getInstance().level == null || !CrythstFabric.config().debug) {
            return;
        }

        final float alpha = Minecraft.getInstance().getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        final List<PhysicsStructure> structures = ClientPhysicsView.structures();
        final Set<Long> structureBodyIds = new HashSet<>(Math.max(16, structures.size() * 2));

        for (PhysicsStructure structure : structures) {
            final StructureRenderState state = structure.renderState(alpha);
            structureBodyIds.add(state.bodyId());
            drawStructure(state);
        }

        final PhysicsDebugSnapshot debug = ClientPhysicsView.debug();
        if (debug != null) {
            drawDebugSnapshot(debug, structureBodyIds);
        }
    }

    /**
     * Draws every merged voxel box in structure-local metres, transformed by the
     * interpolated structure pose.
     */
    private static void drawStructure(StructureRenderState state) {
        final Transform3d pose = state.pose();
        final Vector3d centerOfMassOffset = state.centerOfMassOffset();
        final double blockScale = state.blockScale();

        for (VoxelBox box : state.collider().boxes()) {
            final Vector3d localCenter = new Vector3d(
                    (box.minX() + box.sizeX() * 0.5) * blockScale,
                    (box.minY() + box.sizeY() * 0.5) * blockScale,
                    (box.minZ() + box.sizeZ() * 0.5) * blockScale
            ).sub(centerOfMassOffset);
            final Vector3d center = pose.transformPoint(localCenter);
            final Vector3d halfExtents = new Vector3d(
                    box.sizeX() * blockScale * 0.5,
                    box.sizeY() * blockScale * 0.5,
                    box.sizeZ() * blockScale * 0.5
            );
            final int rgb = colorFor(box.material());
            drawOrientedBox(new OrientedBox(center, halfExtents, pose.rotation()),
                    0xFF000000 | rgb, 0x18000000 | rgb, 1.0f);
        }

        drawOrientedBox(state.bounds(), COLOR_CYAN, FILL_CYAN, 2.0f);

        final Vector3d centerOfMass = pose.transformPoint(centerOfMassOffset);
        Gizmos.point(toVec3(centerOfMass), COLOR_YELLOW, 0.25f);
    }

    /**
     * Draws bodies that do not belong to a structure, plus the world origin and
     * the gravity/up axes.
     */
    private static void drawDebugSnapshot(PhysicsDebugSnapshot debug, Set<Long> structureBodyIds) {
        for (BodyDebugInfo body : debug.bodies()) {
            if (body.terrain() || structureBodyIds.contains(body.id())) {
                continue;
            }
            drawOrientedBox(body.obb(), COLOR_WHITE, 0x40A0A0A0, 1.0f);
            Gizmos.cuboid(toAabb(body.aabb()), GizmoStyle.stroke(COLOR_GRAY, 1.0f));
        }

        final Vector3d origin = debug.origin();
        final Vec3 originPoint = toVec3(origin);
        Gizmos.point(originPoint, COLOR_ORIGIN, 0.35f);

        final Vector3d up = debug.up();
        if (up.lengthSquared() > 1.0e-12) {
            final Vec3 tip = toVec3(new Vector3d(up).mul(2.0).add(origin));
            Gizmos.arrow(originPoint, tip, COLOR_GREEN, 2.0f);
        }

        final Vector3d gravity = debug.gravity();
        if (gravity.lengthSquared() > 1.0e-12) {
            final Vec3 tip = toVec3(new Vector3d(gravity).normalize().mul(2.0).add(origin));
            Gizmos.arrow(originPoint, tip, COLOR_MAGENTA, 2.0f);
        }

        for (ContactEvent contact : debug.contacts()) {
            for (ContactPoint point : contact.points()) {
                final Vec3 position = toVec3(point.position());
                Gizmos.point(position, COLOR_CONTACT, 0.12f);
                final Vector3d normal = point.normal();
                if (normal.lengthSquared() > 1.0e-12) {
                    final Vec3 tip = toVec3(new Vector3d(point.position())
                            .add(new Vector3d(normal).normalize().mul(0.35)));
                    Gizmos.arrow(position, tip, COLOR_CONTACT_NORMAL, 1.0f);
                }
            }
        }
    }

    /**
     * Draws the twelve edges and, when {@code argbFill} is translucent, the six
     * faces of an oriented box.
     */
    private static void drawOrientedBox(OrientedBox obb, int argbEdge, int argbFill, float width) {
        final List<Vector3d> corners = obb.corners();
        final Vec3[] points = new Vec3[8];
        for (int i = 0; i < points.length; i++) {
            points[i] = toVec3(corners.get(i));
        }

        if (((argbFill >>> 24) & 0xFF) > 0) {
            final GizmoStyle fill = GizmoStyle.fill(argbFill);
            Gizmos.rect(points[0], points[2], points[3], points[1], fill);
            Gizmos.rect(points[4], points[5], points[7], points[6], fill);
            Gizmos.rect(points[0], points[1], points[5], points[4], fill);
            Gizmos.rect(points[2], points[6], points[7], points[3], fill);
            Gizmos.rect(points[0], points[4], points[6], points[2], fill);
            Gizmos.rect(points[1], points[3], points[7], points[5], fill);
        }

        Gizmos.line(points[0], points[1], argbEdge, width);
        Gizmos.line(points[2], points[3], argbEdge, width);
        Gizmos.line(points[4], points[5], argbEdge, width);
        Gizmos.line(points[6], points[7], argbEdge, width);

        Gizmos.line(points[0], points[2], argbEdge, width);
        Gizmos.line(points[1], points[3], argbEdge, width);
        Gizmos.line(points[4], points[6], argbEdge, width);
        Gizmos.line(points[5], points[7], argbEdge, width);

        Gizmos.line(points[0], points[4], argbEdge, width);
        Gizmos.line(points[1], points[5], argbEdge, width);
        Gizmos.line(points[2], points[6], argbEdge, width);
        Gizmos.line(points[3], points[7], argbEdge, width);
    }

    private static int colorFor(PhysicsMaterial material) {
        if (PhysicsMaterial.ICE.equals(material)) {
            return COLOR_ICE;
        }
        if (PhysicsMaterial.WOOD.equals(material)) {
            return COLOR_WOOD;
        }
        if (PhysicsMaterial.METAL.equals(material)) {
            return COLOR_METAL;
        }
        if (PhysicsMaterial.DIRT.equals(material)) {
            return COLOR_DIRT;
        }
        return COLOR_ORANGE;
    }

    private static Vec3 toVec3(Vector3dc vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static AABB toAabb(Aabb aabb) {
        return new AABB(aabb.min().x, aabb.min().y, aabb.min().z,
                aabb.max().x, aabb.max().y, aabb.max().z);
    }
}
