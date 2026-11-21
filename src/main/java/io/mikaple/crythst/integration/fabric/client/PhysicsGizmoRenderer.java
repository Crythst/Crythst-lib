package io.mikaple.crythst.integration.fabric.client;

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
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import io.mikaple.crythst.physics.world.TerrainCollisionSnapshot;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gizmo drawing for physics structures, split into independently toggleable
 * debug layers (colliders, bounds, dynamics, contacts). The layers are wired to
 * vanilla F3 + F6 debug entries by {@link CrythstPhysicsDebugRenderer}.
 */
public final class PhysicsGizmoRenderer {
    private static final int COLOR_CYAN = 0xFF00FFFF;
    private static final int FILL_CYAN = 0x1800FFFF;
    private static final int COLOR_YELLOW = 0xFFFFFF00;
    private static final int COLOR_GREEN = 0xFF00FF00;
    private static final int COLOR_MAGENTA = 0xFFFF00FF;
    private static final int COLOR_ORANGE = 0xFFFF8C1A;
    private static final int COLOR_ORIGIN = 0xFFFFC400;
    private static final int COLOR_CONTACT = 0xFFFF3030;
    private static final int COLOR_CONTACT_NORMAL = 0xFFFFAA00;
    private static final int COLOR_AABB = 0xFF40E0D0;
    private static final int COLOR_OUTLINE = 0xFF00FFFF;
    private static final int COLOR_LABEL = 0xFFE6E6E6;

    private static final int COLOR_ICE = 0xFF7FDBFF;
    private static final int COLOR_WOOD = 0xFF8B5A2B;
    private static final int COLOR_METAL = 0xFFB0B0B0;
    private static final int COLOR_DIRT = 0xFF7B6B3A;
    private static final int COLOR_TERRAIN = 0xFF44AA66;
    private static final int COLOR_TERRAIN_PART = 0xFF66DD88;
    private static final int COLOR_TERRAIN_REGION = 0xFF4488FF;
    private static final int COLOR_TERRAIN_MISSING = 0xFFFF4040;

    private PhysicsGizmoRenderer() {
    }


    /**
     * Cached terrain-collision sections and the expanded region each dynamic
     * body requested. Box/part coordinates are absolute world metres.
     */
    public static void emitTerrain(TerrainCollisionSnapshot snapshot) {
        if (snapshot == null || !snapshot.enabled()) {
            return;
        }
        final GizmoStyle boxes = GizmoStyle.stroke(COLOR_TERRAIN, 1.0f);
        final GizmoStyle parts = GizmoStyle.stroke(COLOR_TERRAIN_PART, 1.0f);
        int budget = 4096;
        for (TerrainCollisionSnapshot.Section section : snapshot.sections()) {
            for (VoxelBox box : section.boxes()) {
                if (budget-- <= 0) {
                    break;
                }
                Gizmos.cuboid(new net.minecraft.world.phys.AABB(
                        box.minX(), box.minY(), box.minZ(),
                        box.minX() + box.sizeX(),
                        box.minY() + box.sizeY(),
                        box.minZ() + box.sizeZ()), boxes);
            }
            for (VoxelCellPart part : section.parts()) {
                if (budget-- <= 0) {
                    break;
                }
                Gizmos.cuboid(new net.minecraft.world.phys.AABB(
                        part.minX(), part.minY(), part.minZ(),
                        part.minX() + part.sizeX(),
                        part.minY() + part.sizeY(),
                        part.minZ() + part.sizeZ()), parts);
            }
        }
        for (TerrainCollisionSnapshot.Region region : snapshot.regions()) {
            final int color = region.outsideTerrain() ? COLOR_TERRAIN_MISSING : COLOR_TERRAIN_REGION;
            Gizmos.cuboid(new net.minecraft.world.phys.AABB(
                    region.expandedMinX(), region.expandedMinY(), region.expandedMinZ(),
                    region.expandedMaxX(), region.expandedMaxY(), region.expandedMaxZ()),
                    GizmoStyle.stroke(color, region.outsideTerrain() ? 2.0f : 1.0f));
        }
    }

    /** Per-voxel merged collision boxes, coloured by physics material. */
    public static void emitColliders(float alpha) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            final StructureRenderState state = structure.renderState(alpha);
            final Transform3d pose = state.pose();
            final double blockScale = state.blockScale();
            for (VoxelBox box : state.collider().boxes()) {
                final Vector3d localCenter = new Vector3d(
                        (box.minX() + box.sizeX() * 0.5) * blockScale,
                        (box.minY() + box.sizeY() * 0.5) * blockScale,
                        (box.minZ() + box.sizeZ() * 0.5) * blockScale
                );
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
            // Fractional collision parts are the exact Bullet geometry; the
            // coarse merged boxes above are only the occupancy hint.
            for (io.mikaple.crythst.physics.voxel.VoxelCellPart part
                    : state.collider().customParts()) {
                final Vector3d center = pose.transformPoint(part.center().mul(blockScale));
                final Vector3d halfExtents = new Vector3d(
                        part.sizeX() * blockScale * 0.5,
                        part.sizeY() * blockScale * 0.5,
                        part.sizeZ() * blockScale * 0.5);
                drawOrientedBox(new OrientedBox(center, halfExtents, pose.rotation()),
                        COLOR_YELLOW, 0, 1.5f);
            }
        }
    }

    /** Outer collider OBB plus the axis-aligned world AABB and a body label. */
    public static void emitBounds(float alpha) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            final StructureRenderState state = structure.renderState(alpha);
            drawOrientedBox(state.bounds(), COLOR_OUTLINE, FILL_CYAN, 2.5f);

            final Aabb aabb = state.aabb();
            Gizmos.cuboid(new net.minecraft.world.phys.AABB(
                            aabb.min().x, aabb.min().y, aabb.min().z,
                            aabb.max().x, aabb.max().y, aabb.max().z),
                    GizmoStyle.stroke(COLOR_AABB, 1.5f));

            final Vector3d centre = new Vector3d(
                    (aabb.min().x + aabb.max().x) * 0.5,
                    aabb.max().y + 0.35,
                    (aabb.min().z + aabb.max().z) * 0.5);
            final BodyDebugInfo info = bodyInfo(state.bodyId());
            final String suffix = info == null ? "" : (info.active() ? " active" : " sleeping");
            Gizmos.billboardText("crythst #" + state.bodyId() + suffix,
                    toVec3(centre),
                    TextGizmo.Style.forColorAndCentered(COLOR_LABEL).withScale(0.55f));
        }
    }

    /** Centre of mass, structure origin and pose axes. */
    public static void emitDynamics(float alpha) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            final StructureRenderState state = structure.renderState(alpha);
            final Transform3d pose = state.pose();
            final Vector3d origin = pose.position();
            final Vector3d centerOfMass = pose.transformPoint(state.centerOfMassOffset());

            Gizmos.point(toVec3(origin), COLOR_ORIGIN, 0.22f);
            Gizmos.point(toVec3(centerOfMass), COLOR_YELLOW, 0.28f);
            Gizmos.line(toVec3(origin), toVec3(centerOfMass), COLOR_YELLOW, 1.5f);

            final double localHeight = state.collider().localBounds().max().y
                    - state.collider().localBounds().min().y;
            final double axisLength = Math.max(0.6, Math.min(2.5,
                    localHeight * state.blockScale() * 0.35));
            final Vector3d forward = new Vector3d(0, 0, -1).rotate(pose.rotation());
            final Vector3d up = new Vector3d(0, 1, 0).rotate(pose.rotation());
            final Vector3d right = new Vector3d(1, 0, 0).rotate(pose.rotation());
            Gizmos.arrow(toVec3(origin), toVec3(new Vector3d(origin).fma(axisLength, forward)),
                    COLOR_GREEN, 1.5f);
            Gizmos.arrow(toVec3(origin), toVec3(new Vector3d(origin).fma(axisLength, up)),
                    COLOR_CYAN, 1.5f);
            Gizmos.arrow(toVec3(origin), toVec3(new Vector3d(origin).fma(axisLength, right)),
                    COLOR_MAGENTA, 1.5f);
        }
    }

    /** Physics origin, gravity/up axes and contact points/normals. */
    public static void emitContacts(PhysicsDebugSnapshot debug) {
        if (debug == null) {
            return;
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

    private static BodyDebugInfo bodyInfo(long bodyId) {
        final PhysicsDebugSnapshot debug = ClientPhysicsView.debug();
        if (debug == null) {
            return null;
        }
        for (BodyDebugInfo body : debug.bodies()) {
            if (body.id() == bodyId) {
                return body;
            }
        }
        return null;
    }

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
}
