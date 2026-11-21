package io.mikaple.crythst.integration.fabric.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.debug.BodyDebugInfo;
import io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws physics structures and simple dynamic bodies as solid block-form
 * cuboids so the simulation can be validated without the wireframe overlay.
 *
 * <p>Every solid voxel of a {@link StructureRenderState} is rendered as its own
 * shaded cube (merged colliders are expanded back into cells), using the
 * structure's interpolated render pose and centre-of-mass offset. Bodies that
 * do not belong to a structure are drawn from the {@link PhysicsDebugSnapshot}
 * as filled oriented boxes. Geometry is submitted through the vanilla
 * {@link SubmitNodeCollector} with {@link RenderTypes#debugFilledBox()}, so it
 * is depth-tested against terrain and participates in the normal 26.3 render
 * pipeline.</p>
 *
 * <p>This class only reads immutable snapshots produced on the server thread.
 * It never calls {@code body()}, {@code pose()} or {@code obb()} on a
 * {@link PhysicsStructure}, and it does not mutate the supplied pose stack.</p>
 *
 * <p>Unlike {@link PhysicsGizmoRenderer}, this renderer is independent of the
 * {@code debug} config flag: block-form geometry remains visible while the
 * wireframe overlays are disabled. It still requires the Crythst integration
 * to be enabled, and it is a no-op outside singleplayer because
 * {@link ClientPhysicsView} only publishes snapshots for an integrated
 * server.</p>
 */
public final class StructureBlockRenderer {
    /** Upper bound on cells expanded per structure before falling back to merged boxes. */
    private static final long MAX_CELLS_PER_STRUCTURE = 4096L;
    /** Upper bound on total cuboids emitted in one frame. */
    private static final int MAX_CUBOIDS_PER_FRAME = 16384;

    private static final int COLOR_STONE = 0x9C9C9C;
    private static final int COLOR_DIRT = 0x8A6A48;
    private static final int COLOR_WOOD = 0xA47B4F;
    private static final int COLOR_METAL = 0xB9BEC6;
    private static final int COLOR_ICE = 0x8FD0EF;
    private static final int COLOR_RUBBER = 0x4C4C4C;
    private static final int COLOR_DEFAULT = 0xB0A18C;

    private static final int COLOR_DYNAMIC = 0xD6D6D6;
    private static final int COLOR_KINEMATIC = 0xA6C6E4;
    private static final int COLOR_STATIC = 0x8C8C8C;

    private static final Vector3d ORIGIN = new Vector3d();

    private static volatile boolean blockRenderingEnabled = true;

    private StructureBlockRenderer() {
    }

    /**
     * Toggles solid block rendering at runtime (wired to F9 by
     * {@link CrythstClient}). Wireframe gizmos are controlled separately by the
     * {@code debug} config flag.
     *
     * @return the new enabled state
     */
    public static boolean toggleBlockRendering() {
        blockRenderingEnabled = !blockRenderingEnabled;
        return blockRenderingEnabled;
    }

    public static boolean blockRenderingEnabled() {
        return blockRenderingEnabled;
    }

    public static void setBlockRenderingEnabled(boolean enabled) {
        blockRenderingEnabled = enabled;
    }

    /**
     * Collects and submits this frame's block geometry. Invoked from
     * {@code LevelRenderEvents.BEFORE_GIZMOS}, where a valid submit collector
     * and identity pose stack are available.
     */
    public static void render(LevelRenderContext context) {
        if (!blockRenderingEnabled || Minecraft.getInstance().level == null
                || !CrythstFabric.config().enabled) {
            return;
        }

        final LevelRenderState levelState = context.levelState();
        final CameraRenderState camera = levelState == null ? null : levelState.cameraRenderState;
        final SubmitNodeCollector collector = context.submitNodeCollector();
        if (camera == null || camera.pos == null || collector == null || context.poseStack() == null) {
            return;
        }

        final float alpha = Minecraft.getInstance().getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        final List<PhysicsStructure> structures = ClientPhysicsView.structures();
        final PhysicsDebugSnapshot debug = ClientPhysicsView.debug();

        final List<Cuboid> cuboids = new ArrayList<>();
        final Set<Long> structureBodyIds = new HashSet<>(Math.max(16, structures.size() * 2));

        for (PhysicsStructure structure : structures) {
            final StructureRenderState state = structure.renderState(alpha);
            structureBodyIds.add(state.bodyId());
            if (!isVisible(camera, state.aabb())) {
                continue;
            }
            collectStructure(state, camera, cuboids);
            if (cuboids.size() >= MAX_CUBOIDS_PER_FRAME) {
                break;
            }
        }

        // The debug snapshot is only refreshed while debug output is enabled,
        // so only consume it then (otherwise it may be one tick stale forever).
        if (debug != null && CrythstFabric.config().debug
                && cuboids.size() < MAX_CUBOIDS_PER_FRAME) {
            collectBodies(debug, structureBodyIds, camera, cuboids);
        }

        if (cuboids.isEmpty()) {
            return;
        }

        // The render type neither culls nor writes depth, so draw far cuboids
        // first and only their camera-facing sides. The result is a stable
        // painter's algorithm without needing a custom pipeline.
        cuboids.sort((first, second) -> Float.compare(second.distanceSquared, first.distanceSquared));

        // The event's pose stack is normally identity here. Submit with a fresh
        // identity stack so a transform left behind by another listener cannot
        // shift the camera-relative geometry.
        collector.submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(),
                (pose, consumer) -> emitCuboids(cuboids, pose, consumer));
    }

    private static boolean isVisible(CameraRenderState camera, Aabb bounds) {
        if (camera.cullFrustum == null || !camera.initialized) {
            return true;
        }
        return camera.cullFrustum.isVisible(new AABB(
                bounds.min().x, bounds.min().y, bounds.min().z,
                bounds.max().x, bounds.max().y, bounds.max().z));
    }

    /**
     * Expands the structure's merged collider back into individual voxel cells
     * and records one cuboid per cell. Very large structures fall back to one
     * cuboid per merged box to keep the frame budget bounded.
     */
    private static void collectStructure(StructureRenderState state, CameraRenderState camera,
                                         List<Cuboid> out) {
        final Transform3d pose = state.pose();
        final Vector3d centerOfMass = state.centerOfMassOffset();
        final double scale = state.blockScale();
        final double half = scale * 0.5;
        final Vector3d position = pose.position();
        final Quaterniondc rotation = pose.rotation();
        final double m00 = rotationMatrix00(rotation);
        final double m01 = rotationMatrix01(rotation);
        final double m02 = rotationMatrix02(rotation);
        final double m10 = rotationMatrix10(rotation);
        final double m11 = rotationMatrix11(rotation);
        final double m12 = rotationMatrix12(rotation);
        final double m20 = rotationMatrix20(rotation);
        final double m21 = rotationMatrix21(rotation);
        final double m22 = rotationMatrix22(rotation);
        final double camX = camera.pos.x;
        final double camY = camera.pos.y;
        final double camZ = camera.pos.z;

        long cellCount = 0L;
        for (VoxelBox box : state.collider().boxes()) {
            cellCount += box.cellCount();
            if (cellCount > MAX_CELLS_PER_STRUCTURE) {
                break;
            }
        }
        final boolean perVoxel = cellCount <= MAX_CELLS_PER_STRUCTURE;

        for (VoxelBox box : state.collider().boxes()) {
            final int rgb = colorFor(box.material());
            if (!perVoxel) {
                // One solid cuboid for the whole merged box.
                final double localX = (box.minX() + box.sizeX() * 0.5) * scale - centerOfMass.x;
                final double localY = (box.minY() + box.sizeY() * 0.5) * scale - centerOfMass.y;
                final double localZ = (box.minZ() + box.sizeZ() * 0.5) * scale - centerOfMass.z;
                addCuboid(out, position, m00, m01, m02, m10, m11, m12, m20, m21, m22,
                        localX, localY, localZ,
                        box.sizeX() * half, box.sizeY() * half, box.sizeZ() * half,
                        shade(rgb, box.minX() * 31 + box.minY() * 17 + box.minZ() * 13),
                        camX, camY, camZ);
                continue;
            }

            for (int dy = 0; dy < box.sizeY(); dy++) {
                for (int dz = 0; dz < box.sizeZ(); dz++) {
                    for (int dx = 0; dx < box.sizeX(); dx++) {
                        final double localX = (box.minX() + dx + 0.5) * scale - centerOfMass.x;
                        final double localY = (box.minY() + dy + 0.5) * scale - centerOfMass.y;
                        final double localZ = (box.minZ() + dz + 0.5) * scale - centerOfMass.z;
                        final int variation = (box.minX() + dx) * 31
                                + (box.minY() + dy) * 17
                                + (box.minZ() + dz) * 13;
                        addCuboid(out, position, m00, m01, m02, m10, m11, m12, m20, m21, m22,
                                localX, localY, localZ, half, half, half,
                                shade(rgb, variation), camX, camY, camZ);
                        if (out.size() >= MAX_CUBOIDS_PER_FRAME) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static void collectBodies(PhysicsDebugSnapshot debug, Set<Long> structureBodyIds,
                                      CameraRenderState camera, List<Cuboid> out) {
        final double camX = camera.pos.x;
        final double camY = camera.pos.y;
        final double camZ = camera.pos.z;

        for (BodyDebugInfo body : debug.bodies()) {
            if (body.terrain() || structureBodyIds.contains(body.id())) {
                continue;
            }
            final OrientedBox obb = body.obb();
            final Vector3d center = obb.center();
            final Vector3d halfExtents = obb.halfExtents();
            if (!isVisible(camera, body.aabb())) {
                continue;
            }
            final Quaterniondc rotation = obb.rotation();
            final int rgb = colorFor(body.type());
            addCuboid(out, ORIGIN,
                    rotationMatrix00(rotation), rotationMatrix01(rotation), rotationMatrix02(rotation),
                    rotationMatrix10(rotation), rotationMatrix11(rotation), rotationMatrix12(rotation),
                    rotationMatrix20(rotation), rotationMatrix21(rotation), rotationMatrix22(rotation),
                    center.x, center.y, center.z,
                    halfExtents.x, halfExtents.y, halfExtents.z,
                    shade(rgb, body.id()), camX, camY, camZ);
            if (out.size() >= MAX_CUBOIDS_PER_FRAME) {
                return;
            }
        }
    }

    /**
     * Transforms a local cuboid centre into world space, converts it to
     * camera-relative coordinates and stores the rotated half axes.
     */
    private static void addCuboid(List<Cuboid> out,
                                  Vector3d origin,
                                  double m00, double m01, double m02,
                                  double m10, double m11, double m12,
                                  double m20, double m21, double m22,
                                  double localX, double localY, double localZ,
                                  double halfX, double halfY, double halfZ,
                                  int rgb, double camX, double camY, double camZ) {
        final double worldX = origin.x + m00 * localX + m01 * localY + m02 * localZ;
        final double worldY = origin.y + m10 * localX + m11 * localY + m12 * localZ;
        final double worldZ = origin.z + m20 * localX + m21 * localY + m22 * localZ;

        final float cx = (float) (worldX - camX);
        final float cy = (float) (worldY - camY);
        final float cz = (float) (worldZ - camZ);

        final float axX = (float) (m00 * halfX);
        final float axY = (float) (m10 * halfX);
        final float axZ = (float) (m20 * halfX);
        final float ayX = (float) (m01 * halfY);
        final float ayY = (float) (m11 * halfY);
        final float ayZ = (float) (m21 * halfY);
        final float azX = (float) (m02 * halfZ);
        final float azY = (float) (m12 * halfZ);
        final float azZ = (float) (m22 * halfZ);

        out.add(new Cuboid(cx, cy, cz, axX, axY, axZ, ayX, ayY, ayZ, azX, azY, azZ, rgb,
                cx * cx + cy * cy + cz * cz));
    }

    private static void emitCuboids(List<Cuboid> cuboids, PoseStack.Pose pose, VertexConsumer consumer) {
        for (Cuboid cuboid : cuboids) {
            cuboid.emit(pose, consumer);
        }
    }

    private static int colorFor(PhysicsMaterial material) {
        if (material == null) {
            return COLOR_DEFAULT;
        }
        if (material.equals(PhysicsMaterial.STONE)) {
            return COLOR_STONE;
        }
        if (material.equals(PhysicsMaterial.DIRT)) {
            return COLOR_DIRT;
        }
        if (material.equals(PhysicsMaterial.WOOD)) {
            return COLOR_WOOD;
        }
        if (material.equals(PhysicsMaterial.METAL)) {
            return COLOR_METAL;
        }
        if (material.equals(PhysicsMaterial.ICE)) {
            return COLOR_ICE;
        }
        if (material.equals(PhysicsMaterial.RUBBER)) {
            return COLOR_RUBBER;
        }
        return COLOR_DEFAULT;
    }

    private static int colorFor(BodyType type) {
        return switch (type) {
            case DYNAMIC -> COLOR_DYNAMIC;
            case KINEMATIC -> COLOR_KINEMATIC;
            case STATIC -> COLOR_STATIC;
        };
    }

    /**
     * Adds a small deterministic per-cell brightness variation so adjacent
     * blocks remain distinguishable even when they share a material.
     */
    private static int shade(int rgb, long salt) {
        final int delta = (int) (Math.floorMod(salt * 0x9E3779B9L, 13L)) - 6;
        final int r = clamp(((rgb >> 16) & 0xFF) + delta);
        final int g = clamp(((rgb >> 8) & 0xFF) + delta);
        final int b = clamp((rgb & 0xFF) + delta);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // JOML quaternion -> row-major rotation matrix (column vectors are axes).
    private static double rotationMatrix00(Quaterniondc q) {
        return 1.0 - 2.0 * (q.y() * q.y() + q.z() * q.z());
    }

    private static double rotationMatrix01(Quaterniondc q) {
        return 2.0 * (q.x() * q.y() - q.w() * q.z());
    }

    private static double rotationMatrix02(Quaterniondc q) {
        return 2.0 * (q.x() * q.z() + q.w() * q.y());
    }

    private static double rotationMatrix10(Quaterniondc q) {
        return 2.0 * (q.x() * q.y() + q.w() * q.z());
    }

    private static double rotationMatrix11(Quaterniondc q) {
        return 1.0 - 2.0 * (q.x() * q.x() + q.z() * q.z());
    }

    private static double rotationMatrix12(Quaterniondc q) {
        return 2.0 * (q.y() * q.z() - q.w() * q.x());
    }

    private static double rotationMatrix20(Quaterniondc q) {
        return 2.0 * (q.x() * q.z() - q.w() * q.y());
    }

    private static double rotationMatrix21(Quaterniondc q) {
        return 2.0 * (q.y() * q.z() + q.w() * q.x());
    }

    private static double rotationMatrix22(Quaterniondc q) {
        return 1.0 - 2.0 * (q.x() * q.x() + q.y() * q.y());
    }

    /**
     * A camera-relative oriented box. Half axes already include the cuboid
     * half extents and the structure rotation.
     */
    private static final class Cuboid {
        private final float cx;
        private final float cy;
        private final float cz;
        private final float axX;
        private final float axY;
        private final float axZ;
        private final float ayX;
        private final float ayY;
        private final float ayZ;
        private final float azX;
        private final float azY;
        private final float azZ;
        private final int rgb;
        private final float distanceSquared;

        private Cuboid(float cx, float cy, float cz,
                       float axX, float axY, float axZ,
                       float ayX, float ayY, float ayZ,
                       float azX, float azY, float azZ,
                       int rgb, float distanceSquared) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.axX = axX;
            this.axY = axY;
            this.axZ = axZ;
            this.ayX = ayX;
            this.ayY = ayY;
            this.ayZ = ayZ;
            this.azX = azX;
            this.azY = azY;
            this.azZ = azZ;
            this.rgb = rgb;
            this.distanceSquared = distanceSquared;
        }

        private void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            final int red = (rgb >> 16) & 0xFF;
            final int green = (rgb >> 8) & 0xFF;
            final int blue = rgb & 0xFF;

            // Face visibility uses the face plane, not the centre plane: with
            // the camera at the origin, the +axis face is visible only when
            // dot(axis, centre) + |axis|^2 < 0, and the -axis face when
            // dot(axis, centre) > |axis|^2.
            final float limitX = axX * axX + axY * axY + axZ * axZ;
            final float limitY = ayX * ayX + ayY * ayY + ayZ * ayZ;
            final float limitZ = azX * azX + azY * azY + azZ * azZ;
            final float dotX = axX * cx + axY * cy + axZ * cz;
            final float dotY = ayX * cx + ayY * cy + ayZ * cz;
            final float dotZ = azX * cx + azY * cy + azZ * cz;

            // +X
            if (dotX + limitX < 0.0f) {
                emitQuad(pose, consumer, red, green, blue, 0.80f,
                        cx + axX - ayX - azX, cy + axY - ayY - azY, cz + axZ - ayZ - azZ,
                        cx + axX + ayX - azX, cy + axY + ayY - azY, cz + axZ + ayZ - azZ,
                        cx + axX + ayX + azX, cy + axY + ayY + azY, cz + axZ + ayZ + azZ,
                        cx + axX - ayX + azX, cy + axY - ayY + azY, cz + axZ - ayZ + azZ);
            }
            // -X
            if (dotX > limitX) {
                emitQuad(pose, consumer, red, green, blue, 0.68f,
                        cx - axX - ayX - azX, cy - axY - ayY - azY, cz - axZ - ayZ - azZ,
                        cx - axX - ayX + azX, cy - axY - ayY + azY, cz - axZ - ayZ + azZ,
                        cx - axX + ayX + azX, cy - axY + ayY + azY, cz - axZ + ayZ + azZ,
                        cx - axX + ayX - azX, cy - axY + ayY - azY, cz - axZ + ayZ - azZ);
            }
            // +Y (top)
            if (dotY + limitY < 0.0f) {
                emitQuad(pose, consumer, red, green, blue, 1.0f,
                        cx - axX + ayX - azX, cy - axY + ayY - azY, cz - axZ + ayZ - azZ,
                        cx - axX + ayX + azX, cy - axY + ayY + azY, cz - axZ + ayZ + azZ,
                        cx + axX + ayX + azX, cy + axY + ayY + azY, cz + axZ + ayZ + azZ,
                        cx + axX + ayX - azX, cy + axY + ayY - azY, cz + axZ + ayZ - azZ);
            }
            // -Y (bottom)
            if (dotY > limitY) {
                emitQuad(pose, consumer, red, green, blue, 0.55f,
                        cx - axX - ayX - azX, cy - axY - ayY - azY, cz - axZ - ayZ - azZ,
                        cx + axX - ayX - azX, cy + axY - ayY - azY, cz + axZ - ayZ - azZ,
                        cx + axX - ayX + azX, cy + axY - ayY + azY, cz + axZ - ayZ + azZ,
                        cx - axX - ayX + azX, cy - axY - ayY + azY, cz - axZ - ayZ + azZ);
            }
            // +Z
            if (dotZ + limitZ < 0.0f) {
                emitQuad(pose, consumer, red, green, blue, 0.74f,
                        cx - axX - ayX + azX, cy - axY - ayY + azY, cz - axZ - ayZ + azZ,
                        cx + axX - ayX + azX, cy + axY - ayY + azY, cz + axZ - ayZ + azZ,
                        cx + axX + ayX + azX, cy + axY + ayY + azY, cz + axZ + ayZ + azZ,
                        cx - axX + ayX + azX, cy - axY + ayY + azY, cz - axZ + ayZ + azZ);
            }
            // -Z
            if (dotZ > limitZ) {
                emitQuad(pose, consumer, red, green, blue, 0.62f,
                        cx - axX - ayX - azX, cy - axY - ayY - azY, cz - axZ - ayZ - azZ,
                        cx - axX + ayX - azX, cy - axY + ayY - azY, cz - axZ + ayZ - azZ,
                        cx + axX + ayX - azX, cy + axY + ayY - azY, cz + axZ + ayZ - azZ,
                        cx + axX - ayX - azX, cy + axY - ayY - azY, cz + axZ - ayZ - azZ);
            }
        }

        private static void emitQuad(PoseStack.Pose pose, VertexConsumer consumer,
                                     int red, int green, int blue, float shade,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float x3, float y3, float z3) {
            final float r = (red / 255.0f) * shade;
            final float g = (green / 255.0f) * shade;
            final float b = (blue / 255.0f) * shade;
            consumer.addVertex(pose, x0, y0, z0).setColor(r, g, b, 1.0f);
            consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, 1.0f);
            consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, 1.0f);
            consumer.addVertex(pose, x3, y3, z3).setColor(r, g, b, 1.0f);
        }
    }
}
