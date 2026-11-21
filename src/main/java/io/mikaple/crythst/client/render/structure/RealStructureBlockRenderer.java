package io.mikaple.crythst.client.render.structure;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.fabric.client.StructureBlockRenderer;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Draws physics structures with their real Minecraft block models instead of
 * the legacy coloured debug cuboids.
 *
 * <p>The render snapshot published by {@link ClientPhysicsView} supplies the
 * interpolated pose, centre-of-mass offset and block scale. The block states
 * themselves come from {@link StructureBlockSnapshots}, which copies them on
 * the server thread. Nothing in this class reads a server level, voxel volume or
 * native body from the render thread.</p>
 *
 * <p>In 26.3 the per-block path is:
 * {@code ModelManager#getBlockStateModelSet()} to resolve a
 * {@link BlockStateModel}, {@link BlockStateModel#collectParts(RandomSource,
 * List)} to obtain immutable model parts, and
 * {@link SubmitNodeCollector#submitBlockModel(PoseStack, RenderType, List, int[],
 * int, int, int)} to submit those parts with the moving-block render types. The
 * model parts are cached per {@link BlockState}, so only the pose and lighting
 * are recomputed each frame. A resource reload clears the cache.</p>
 *
 * <p>Registration is deliberately explicit because {@code CrythstClient} is
 * owned by another task. Add this line there:</p>
 * <pre>{@code
 * RealStructureBlockRenderer.register();
 * }</pre>
 *
 * <p>The old {@link StructureBlockRenderer} remains registered but is switched
 * off by {@link #register()} to avoid drawing both representations. Pressing
 * F9 (the legacy solid-block toggle) switches back to the debug cuboids and
 * disables this renderer, so the two views never overlap.</p>
 */
public final class RealStructureBlockRenderer {
    /** Upper bound on real block submissions in one frame. */
    private static final int MAX_BLOCKS_PER_FRAME = 16_384;
    /** Upper bound on legacy fallback cuboids when no snapshot exists. */
    private static final int MAX_FALLBACK_BOXES = 4_096;
    /** BlockStateModel.materialFlags() bit for translucent material. */
    private static final int MATERIAL_TRANSLUCENT = 1;

    private static final int COLOR_STONE = 0x9C9C9C;
    private static final int COLOR_DIRT = 0x8A6A48;
    private static final int COLOR_WOOD = 0xA47B4F;
    private static final int COLOR_METAL = 0xB9BEC6;
    private static final int COLOR_ICE = 0x8FD0EF;
    private static final int COLOR_RUBBER = 0x4C4C4C;
    private static final int COLOR_DEFAULT = 0xB0A18C;

    private static final Map<BlockState, CachedModel> MODELS = new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Direction[] DIRECTIONS = Direction.values();

    private static volatile boolean enabled = true;

    private RealStructureBlockRenderer() {
    }

    /**
     * Registers the renderer and the server-thread snapshot producer. Safe to
     * call more than once.
     *
     * <p>This method also disables {@link StructureBlockRenderer}, whose
     * coloured cuboids are still registered by {@code CrythstClient}. F9 keeps
     * working as a switch between the real-block view and the legacy debug
     * view.</p>
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        StructureBlockSnapshots.register();
        LevelRenderEvents.BEFORE_GIZMOS.register(RealStructureBlockRenderer::render);
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "real_structure_block_models"),
                new ResourceManagerReloadListener() {
                    @Override
                    public void onResourceManagerReload(ResourceManager resourceManager) {
                        MODELS.clear();
                    }
                });
        // CrythstClient still registers StructureBlockRenderer; switch it off so
        // structures are not drawn twice. The F9 toggle can bring it back.
        StructureBlockRenderer.setBlockRenderingEnabled(false);
        Crythst.LOGGER.info("Crythst real structure block renderer registered");
    }

    /** Runtime toggle for the real-block view. */
    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Collects this frame's structure geometry. Invoked from
     * {@code LevelRenderEvents.BEFORE_GIZMOS}.
     */
    public static void render(LevelRenderContext context) {
        if (!enabled || Minecraft.getInstance().level == null
                || !CrythstFabric.config().enabled) {
            return;
        }
        // The legacy renderer wins while its F9 toggle is on. This makes F9 a
        // clean switch rather than a double-render switch.
        if (StructureBlockRenderer.blockRenderingEnabled()) {
            return;
        }

        final Minecraft client = Minecraft.getInstance();
        final ResourceKey<Level> dimension = client.level.dimension();
        final LevelRenderState levelState = context.levelState();
        final CameraRenderState camera = levelState == null ? null : levelState.cameraRenderState;
        final SubmitNodeCollector collector = context.submitNodeCollector();
        if (camera == null || camera.pos == null || collector == null) {
            return;
        }

        // Sample the timestamped pose timeline at one frame time for every
        // structure. Server ticks are not phase-locked to the client partial
        // tick, so the old alpha-based sample could jump mid-frame.
        final long frameNanos = System.nanoTime();
        final List<PhysicsStructure> structures = ClientPhysicsView.structures();
        final List<FallbackBox> fallback = new ArrayList<>();
        final PoseStack pose = new PoseStack();
        int remaining = MAX_BLOCKS_PER_FRAME;

        for (PhysicsStructure structure : structures) {
            final StructureRenderState state = structure.renderStateAt(frameNanos);
            if (!isVisible(camera, state.aabb())) {
                continue;
            }

            final StructureBlockSnapshots.Snapshot snapshot =
                    StructureBlockSnapshots.get(dimension, state.bodyId());
            if (snapshot != null && snapshot.nonAirBlocks() > 0) {
                remaining = submitSnapshot(collector, camera, state, snapshot, pose, remaining);
            } else {
                collectFallback(state, camera, fallback);
            }
            if (remaining <= 0) {
                break;
            }
        }

        if (!fallback.isEmpty()) {
            fallback.sort(Comparator.comparingDouble((FallbackBox box) -> box.distanceSquared).reversed());
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(),
                    (fallbackPose, consumer) -> emitFallback(fallback, fallbackPose, consumer));
        }
    }

    private static int submitSnapshot(SubmitNodeCollector collector, CameraRenderState camera,
                                      StructureRenderState state,
                                      StructureBlockSnapshots.Snapshot snapshot,
                                      PoseStack pose, int remaining) {
        final Transform3d transform = state.pose();
        final Vector3d position = transform.position();
        final Quaterniondc rotation = transform.rotation();
        final double scale = state.blockScale();

        final double m00 = rotationMatrix00(rotation);
        final double m01 = rotationMatrix01(rotation);
        final double m02 = rotationMatrix02(rotation);
        final double m10 = rotationMatrix10(rotation);
        final double m11 = rotationMatrix11(rotation);
        final double m12 = rotationMatrix12(rotation);
        final double m20 = rotationMatrix20(rotation);
        final double m21 = rotationMatrix21(rotation);
        final double m22 = rotationMatrix22(rotation);

        final float quatX = (float) rotation.x();
        final float quatY = (float) rotation.y();
        final float quatZ = (float) rotation.z();
        final float quatW = (float) rotation.w();
        final Quaternionf quaternion = new Quaternionf(quatX, quatY, quatZ, quatW).normalize();
        final float scaleF = (float) scale;
        final double camX = camera.pos.x;
        final double camY = camera.pos.y;
        final double camZ = camera.pos.z;
        final int light = lightAt(position);

        int submitted = 0;
        final int minX = snapshot.minX();
        final int minY = snapshot.minY();
        final int minZ = snapshot.minZ();
        final int maxX = minX + snapshot.sizeX();
        final int maxY = minY + snapshot.sizeY();
        final int maxZ = minZ + snapshot.sizeZ();

        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    final BlockState blockState = snapshot.stateAt(x, y, z);
                    if (blockState.isAir()) {
                        continue;
                    }
                    final CachedModel model = modelFor(blockState);
                    if (model.empty) {
                        continue;
                    }

                    // Structure-local minimum corner of this voxel, in metres.
                    // The structure pose already accounts for the centre of
                    // mass, so no COM subtraction belongs here.
                    final double localX = x * scale;
                    final double localY = y * scale;
                    final double localZ = z * scale;

                    // Full pose in double precision, then subtract the camera so
                    // only small camera-relative floats reach the PoseStack.
                    final double worldX = position.x
                            + m00 * localX + m01 * localY + m02 * localZ;
                    final double worldY = position.y
                            + m10 * localX + m11 * localY + m12 * localZ;
                    final double worldZ = position.z
                            + m20 * localX + m21 * localY + m22 * localZ;

                    pose.setIdentity();
                    pose.translate((float) (worldX - camX),
                            (float) (worldY - camY),
                            (float) (worldZ - camZ));
                    pose.rotate(quaternion);
                    pose.scale(scaleF, scaleF, scaleF);

                    collector.submitBlockModel(pose, model.renderType, model.parts,
                            BlockModelRenderState.EMPTY_TINTS, light,
                            OverlayTexture.NO_OVERLAY, 0);

                    submitted++;
                    if (submitted >= remaining) {
                        return 0;
                    }
                }
            }
        }
        return remaining - submitted;
    }

    private static CachedModel modelFor(BlockState state) {
        final CachedModel cached = MODELS.get(state);
        if (cached != null) {
            return cached;
        }
        final CachedModel built = buildModel(state);
        final CachedModel existing = MODELS.putIfAbsent(state, built);
        return existing == null ? built : existing;
    }

    private static CachedModel buildModel(BlockState state) {
        final BlockStateModelSet modelSet = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet();
        final BlockStateModel model = modelSet.get(state);
        final List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0L), parts);

        boolean empty = true;
        boolean translucent = model.hasMaterialFlag(MATERIAL_TRANSLUCENT);
        for (BlockStateModelPart part : parts) {
            translucent |= (part.materialFlags() & MATERIAL_TRANSLUCENT) != 0;
            if (empty) {
                for (Direction direction : DIRECTIONS) {
                    if (!part.getQuads(direction).isEmpty()) {
                        empty = false;
                        break;
                    }
                }
                if (empty && !part.getQuads(null).isEmpty()) {
                    empty = false;
                }
            }
        }

        final RenderType renderType = translucent
                ? RenderTypes.translucentMovingBlock()
                : RenderTypes.cutoutMovingBlock();
        return new CachedModel(empty, List.copyOf(parts), renderType);
    }

    /**
     * Approximate lighting: one light sample for the whole structure. Per-block
     * or per-face lighting would require extracting the client level's light
     * engine on the render thread, and the structure is moving anyway.
     */
    private static int lightAt(Vector3d position) {
        final Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return LightCoordsUtil.FULL_BRIGHT;
        }
        try {
            final BlockPos pos = BlockPos.containing(position.x, position.y, position.z);
            return LightCoordsUtil.getLightCoords(client.level, pos);
        } catch (RuntimeException ignored) {
            return LightCoordsUtil.FULL_BRIGHT;
        }
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
     * Legacy fallback for structures that have no block-state snapshot: one
     * coloured cuboid per merged collider box, matching the old debug view.
     */
    private static void collectFallback(StructureRenderState state, CameraRenderState camera,
                                        List<FallbackBox> out) {
        final Transform3d transform = state.pose();
        final Vector3d position = transform.position();
        final Quaterniondc rotation = transform.rotation();
        final double scale = state.blockScale();
        final double m00 = rotationMatrix00(rotation);
        final double m01 = rotationMatrix01(rotation);
        final double m02 = rotationMatrix02(rotation);
        final double m10 = rotationMatrix10(rotation);
        final double m11 = rotationMatrix11(rotation);
        final double m12 = rotationMatrix12(rotation);
        final double m20 = rotationMatrix20(rotation);
        final double m21 = rotationMatrix21(rotation);
        final double m22 = rotationMatrix22(rotation);

        for (VoxelBox box : state.collider().boxes()) {
            // Structure-local box centre in metres. The structure pose already
            // includes the centre-of-mass offset.
            final double localX = (box.minX() + box.sizeX() * 0.5) * scale;
            final double localY = (box.minY() + box.sizeY() * 0.5) * scale;
            final double localZ = (box.minZ() + box.sizeZ() * 0.5) * scale;
            final double worldX = position.x + m00 * localX + m01 * localY + m02 * localZ;
            final double worldY = position.y + m10 * localX + m11 * localY + m12 * localZ;
            final double worldZ = position.z + m20 * localX + m21 * localY + m22 * localZ;

            final float cx = (float) (worldX - camera.pos.x);
            final float cy = (float) (worldY - camera.pos.y);
            final float cz = (float) (worldZ - camera.pos.z);
            final double halfScale = scale * 0.5;

            out.add(new FallbackBox(cx, cy, cz,
                    (float) (m00 * box.sizeX() * halfScale),
                    (float) (m10 * box.sizeX() * halfScale),
                    (float) (m20 * box.sizeX() * halfScale),
                    (float) (m01 * box.sizeY() * halfScale),
                    (float) (m11 * box.sizeY() * halfScale),
                    (float) (m21 * box.sizeY() * halfScale),
                    (float) (m02 * box.sizeZ() * halfScale),
                    (float) (m12 * box.sizeZ() * halfScale),
                    (float) (m22 * box.sizeZ() * halfScale),
                    colorFor(box.material()),
                    cx * cx + cy * cy + cz * cz));
            if (out.size() >= MAX_FALLBACK_BOXES) {
                return;
            }
        }
    }

    private static void emitFallback(List<FallbackBox> boxes, PoseStack.Pose pose,
                                     VertexConsumer consumer) {
        for (FallbackBox box : boxes) {
            box.emit(pose, consumer);
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

    private record CachedModel(boolean empty, List<BlockStateModelPart> parts, RenderType renderType) {
    }

    /**
     * Camera-relative oriented cuboid used only when no block-state snapshot is
     * available.
     */
    private static final class FallbackBox {
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

        private FallbackBox(float cx, float cy, float cz,
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

            final float limitX = axX * axX + axY * axY + axZ * axZ;
            final float limitY = ayX * ayX + ayY * ayY + ayZ * ayZ;
            final float limitZ = azX * azX + azY * azY + azZ * azZ;
            final float dotX = axX * cx + axY * cy + axZ * cz;
            final float dotY = ayX * cx + ayY * cy + ayZ * cz;
            final float dotZ = azX * cx + azY * cy + azZ * cz;

            if (dotX + limitX < 0.0f) {
                emitQuad(pose, consumer, red, green, blue, 0.80f,
                        cx + axX - ayX - azX, cy + axY - ayY - azY, cz + axZ - ayZ - azZ,
                        cx + axX + ayX - azX, cy + axY + ayY - azY, cz + axZ + ayZ - azZ,
                        cx + axX + ayX + azX, cy + axY + ayY + azY, cz + axZ + ayZ + azZ,
                        cx + axX - ayX + azX, cy + axY - ayY + azY, cz + axZ - ayZ + azZ);
            }
            if (dotX > limitX) {
                emitQuad(pose, consumer, red, green, blue, 0.68f,
                        cx - axX - ayX - azX, cy - axY - ayY - azY, cz - axZ - ayZ - azZ,
                        cx - axX - ayX + azX, cy - axY - ayY + azY, cz - axZ - ayZ + azZ,
                        cx - axX + ayX + azX, cy - axY + ayY + azY, cz - axZ + ayZ + azZ,
                        cx - axX + ayX - azX, cy - axY + ayY - azY, cz - axZ + ayZ - azZ);
            }
            if (dotY + limitY < 0.0f) {
                emitQuad(pose, consumer, red, green, blue, 1.0f,
                        cx - axX + ayX - azX, cy - axY + ayY - azY, cz - axZ + ayZ - azZ,
                        cx - axX + ayX + azX, cy - axY + ayY + azY, cz - axZ + ayZ + azZ,
                        cx + axX + ayX + azX, cy + axY + ayY + azY, cz + axZ + ayZ + azZ,
                        cx + axX + ayX - azX, cy + axY + ayY - azY, cz + axZ + ayZ - azZ);
            }
            if (dotY > limitY) {
                emitQuad(pose, consumer, red, green, blue, 0.55f,
                        cx - axX - ayX - azX, cy - axY - ayY - azY, cz - axZ - ayZ - azZ,
                        cx + axX - ayX - azX, cy + axY - ayY - azY, cz + axZ - ayZ - azZ,
                        cx + axX - ayX + azX, cy + axY - ayY + azY, cz + axZ - ayZ + azZ,
                        cx - axX - ayX + azX, cy - axY - ayY + azY, cz - axZ - ayZ + azZ);
            }
            if (dotZ + limitZ < 0.0f) {
                emitQuad(pose, consumer, red, green, blue, 0.74f,
                        cx - axX - ayX + azX, cy - axY - ayY + azY, cz - axZ - ayZ + azZ,
                        cx + axX - ayX + azX, cy + axY - ayY + azY, cz + axZ - ayZ + azZ,
                        cx + axX + ayX + azX, cy + axY + ayY + azY, cz + axZ + ayZ + azZ,
                        cx - axX + ayX + azX, cy - axY + ayY + azY, cz - axZ + ayZ + azZ);
            }
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
