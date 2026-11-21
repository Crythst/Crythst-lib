package io.mikaple.crythst.client.render.structure.batch;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.client.render.block.SpecialBlockLight;
import io.mikaple.crythst.client.render.block.SpecialBlockRenderContext;
import io.mikaple.crythst.client.render.block.SpecialBlockRenderer;
import io.mikaple.crythst.client.render.block.SpecialBlockRenderers;
import io.mikaple.crythst.client.render.structure.RealStructureBlockRenderer;
import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.fabric.client.StructureBlockRenderer;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batched replacement for the per-block structure renderer.
 *
 * <h2>Why</h2>
 * {@link RealStructureBlockRenderer} resolves a {@code BlockStateModel} per block
 * and calls {@code SubmitNodeCollector.submitBlockModel} once per block. A
 * 32,768-block structure therefore produces up to 32,768 submit nodes, and
 * every frame the feature renderer walks all of their model parts and quads.
 * In 26.3 that is the dominant cost for large moving structures.
 *
 * <h2>Approach</h2>
 * This renderer mirrors the "Option C" idea used by sable's
 * {@code SubLevelBatchedTerrainRenderer}: group geometry at section granularity
 * and submit as few batches as possible. Since Crythst structures are not
 * vanilla chunks, it cannot reuse the terrain pipeline directly, so it caches a
 * compact section-local vertex soup and replays it through
 * {@link SubmitNodeCollector#submitCustomGeometry}:
 *
 * <ul>
 *   <li>One {@link StructureBatchCache.Body} per physics body, split into
 *       16x16x16 local sections.</li>
 *   <li>Per section and render layer ({@link BlockGeometry.Layer}), one
 *       {@link SectionMesh} containing positions, atlas UVs, normals, tint
 *       colours and light emission.</li>
 *   <li>One submit node per non-empty section/layer per frame instead of one
 *       per block. A typical structure drops from tens of thousands of nodes
 *       to dozens.</li>
 *   <li>The interpolated structure transform is applied through a PoseStack
 *       built from a camera-relative section origin, so only small floats reach
 *       the vertex data.</li>
 * </ul>
 *
 * <h2>Correctness</h2>
 * Geometry is split per <em>quad's</em> {@code ChunkSectionLayer}, not per block,
 * so opaque, cutout and translucent faces of the same block go to the right
 * moving-block render type. UVs come from {@code BakedQuad.packedUV} via
 * vanilla's {@code UVPair}; normals come from the baked quad direction (with a
 * winding fallback); light emission is preserved. Tinted states use the client
 * block tint source when it can resolve without a world, and white otherwise.
 *
 * <h2>Registration</h2>
 * <pre>{@code
 * BatchedStructureRenderer.register();
 * }</pre>
 * Put that line in {@code CrythstClient.onInitializeClient()} (the file is
 * owned by another task and is intentionally not edited here). It registers
 * this renderer, the snapshot producer, a resource-reload listener, and
 * disables the per-block renderer so structures are not drawn twice.
 */
public final class BatchedStructureRenderer {
    /** Maximum section/layer submit nodes per frame. */
    private static final int MAX_BATCHES_PER_FRAME = 8_192;
    /** Maximum vertices built into caches per frame, to smooth first-use hitches. */
    private static final long MAX_BUILD_VERTICES_PER_FRAME = 250_000L;
    /** Maximum individual blocks submitted through the legacy fallback path per frame. */
    private static final int MAX_FALLBACK_BLOCKS_PER_FRAME = 4_096;
    /** Maximum debug cuboids emitted for snapshot-less structures per frame. */
    private static final int MAX_FALLBACK_BOXES = 4_096;
    /** Maximum snapshot-less structures considered per frame. */
    private static final int MAX_FALLBACK_BODIES = 64;
    /** Maximum special block renderer calls per frame. */
    private static final int MAX_SPECIAL_BLOCKS_PER_FRAME = 1_024;
    /** Default distance cut-off in blocks. */
    private static final double DEFAULT_RENDER_DISTANCE = 512.0;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final PoseStack FRAME_POSE = new PoseStack();
    private static final Vector3d SCRATCH = new Vector3d();

    private static final List<StructureBatchCache.Section> OPAQUE = new ArrayList<>();
    private static final List<StructureBatchCache.Section> TRANSLUCENT = new ArrayList<>();
    private static final List<StructureBatchCache.Section> FALLBACK_SECTIONS = new ArrayList<>();
    private static final List<StructureBatchCache.Section> SPECIAL_SECTIONS = new ArrayList<>();
    private static final List<StructureRenderState> FALLBACK_BODIES = new ArrayList<>();
    private static final NetworkStructureSource NETWORK = new NetworkStructureSource();
    private static final Comparator<StructureBatchCache.Section> NEAR_FIRST =
            Comparator.comparingDouble(section -> section.frameDistanceSq);
    private static final Comparator<StructureBatchCache.Section> FAR_FIRST = NEAR_FIRST.reversed();

    private static volatile boolean enabled = true;
    private static volatile double renderDistance = DEFAULT_RENDER_DISTANCE;
    private static volatile BatchStats stats = BatchStats.EMPTY;

    private static int submittedBatches;
    private static long submittedVertices;
    private static int culledSections;
    private static int fallbackBlocks;
    private static int fallbackBodies;
    private static int specialBlocks;

    private BatchedStructureRenderer() {
    }

    /**
     * Registers the batched renderer. Idempotent.
     *
     * <p>Also registers the server-thread snapshot producer and a client
     * resource-reload listener, and disables both the legacy coloured renderer
     * and the per-block real renderer. F9 still toggles the legacy view; while
     * it is on, this renderer suspends itself so the two never overlap.</p>
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        StructureBlockSnapshots.register();
        LevelRenderEvents.BEFORE_GIZMOS.register(BatchedStructureRenderer::render);
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "batched_structure_geometry"),
                new ResourceManagerReloadListener() {
                    @Override
                    public void onResourceManagerReload(ResourceManager resourceManager) {
                        // Body meshes reference palette UVs, so both layers must
                        // go. Clearing bodies first guarantees no mesh outlives
                        // the palette generation it was built from.
                        StructureBatchCache.clearAll();
                        NETWORK.clear();
                        BlockGeometryCache.clear();
                    }
                });
        RealStructureBlockRenderer.setEnabled(false);
        StructureBlockRenderer.setBlockRenderingEnabled(false);
        Crythst.LOGGER.info("Crythst batched structure renderer registered");
    }

    /** Runtime toggle. Disabling clears the resident vertex cache. */
    public static boolean toggle() {
        final boolean next = !enabled;
        setEnabled(next);
        return next;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            StructureBatchCache.clearAll();
            NETWORK.clear();
        }
    }

    /** Distance cut-off in blocks. Values below 16 are clamped. */
    public static void setRenderDistance(double blocks) {
        renderDistance = Math.max(16.0, blocks);
    }

    public static double renderDistance() {
        return renderDistance;
    }

    /** Resident vertex budget. Lower it on memory-constrained launchers. */
    public static void setVertexBudget(long vertices) {
        StructureBatchCache.setVertexBudget(vertices);
    }

    public static long vertexBudget() {
        return StructureBatchCache.vertexBudget();
    }

    /** Most recent frame's counters. */
    public static BatchStats stats() {
        return stats;
    }

    /** Most recent frame's counters as a single human-readable line. */
    public static String statsText() {
        return stats.summary();
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
        // F9 selects the legacy coloured cuboids. Honor it as a hard switch so
        // the user never sees two representations at once.
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

        final long frameStart = System.nanoTime();
        final float alpha = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        final List<PhysicsStructure> structures = ClientPhysicsView.structures();
        final Set<Long> serverBodyIds = new HashSet<>(Math.max(16, structures.size() * 2));

        StructureBatchCache.beginFrame(dimension);
        NETWORK.beginFrame();
        OPAQUE.clear();
        TRANSLUCENT.clear();
        FALLBACK_SECTIONS.clear();
        SPECIAL_SECTIONS.clear();
        FALLBACK_BODIES.clear();
        submittedBatches = 0;
        submittedVertices = 0L;
        culledSections = 0;
        fallbackBlocks = 0;
        fallbackBodies = 0;
        specialBlocks = 0;

        final double maxDistanceSq = renderDistance * renderDistance;
        long buildBudget = MAX_BUILD_VERTICES_PER_FRAME;
        final long buildStart = System.nanoTime();

        // Singleplayer/unintegrated path: live physics render states plus the
        // server-thread block snapshots. These body ids win over network data.
        for (PhysicsStructure structure : structures) {
            final StructureRenderState state = structure.renderState(alpha);
            serverBodyIds.add(state.bodyId());
            if (!isVisible(camera, state.aabb())) {
                continue;
            }

            final StructureBlockSnapshots.Snapshot snapshot =
                    StructureBlockSnapshots.get(dimension, state.bodyId());
            if (snapshot == null || snapshot.nonAirBlocks() <= 0) {
                // The singleplayer block-snapshot producer caps large or
                // unsupported volumes. If network sync already delivered the
                // same body, use that geometry instead of dropping to cuboids.
                final ClientStructureSnapshot network = ClientStructureCache.get(state.bodyId());
                if (network != null && network.nonAirBlocks() > 0) {
                    final StructureBatchSnapshots.Snapshot geometry = NETWORK.geometry(network);
                    final double blockScale = network.blockScale();
                    final StructureBatchCache.Body body = StructureBatchCache.getOrCreate(
                            dimension, geometry, state.pose(), blockScale);
                    body.beginFrame(state.pose(), blockScale, camera);

                    if (buildBudget > 0L) {
                        buildBudget -= StructureBatchCache.buildPending(body, buildBudget);
                    }
                    collectSections(body, camera, maxDistanceSq);
                    continue;
                }
                if (FALLBACK_BODIES.size() < MAX_FALLBACK_BODIES) {
                    FALLBACK_BODIES.add(state);
                }
                continue;
            }

            final StructureBatchCache.Body body = StructureBatchCache.getOrCreate(
                    dimension,
                    StructureBatchSnapshots.fromServer(snapshot),
                    state.pose(),
                    state.blockScale());
            body.beginFrame(state.pose(), state.blockScale(), camera);

            if (buildBudget > 0L) {
                buildBudget -= StructureBatchCache.buildPending(body, buildBudget);
            }
            collectSections(body, camera, maxDistanceSq);
        }

        // Multiplayer path: snapshots received from the server. Structures that
        // already came from ClientPhysicsView are skipped so a body is never
        // drawn twice in singleplayer.
        for (ClientStructureSnapshot network : ClientStructureCache.snapshots()) {
            if (network.nonAirBlocks() <= 0 || serverBodyIds.contains(network.bodyId())) {
                continue;
            }

            final StructureBatchSnapshots.Snapshot geometry = NETWORK.geometry(network);
            final Transform3d pose = network.pose();
            final double blockScale = network.blockScale();
            if (!isVisible(camera, StructureBatchSnapshots.worldBounds(
                    geometry, pose, blockScale))) {
                continue;
            }

            final StructureBatchCache.Body body = StructureBatchCache.getOrCreate(
                    dimension, geometry, pose, blockScale);
            body.beginFrame(pose, blockScale, camera);

            if (buildBudget > 0L) {
                buildBudget -= StructureBatchCache.buildPending(body, buildBudget);
            }
            collectSections(body, camera, maxDistanceSq);
        }

        final long buildEnd = System.nanoTime();
        StructureBatchCache.retryOverBudgetSections();

        OPAQUE.sort(NEAR_FIRST);
        for (StructureBatchCache.Section section : OPAQUE) {
            if (submittedBatches >= MAX_BATCHES_PER_FRAME) {
                break;
            }
            submitOpaque(section, collector);
        }

        TRANSLUCENT.sort(FAR_FIRST);
        for (StructureBatchCache.Section section : TRANSLUCENT) {
            if (submittedBatches >= MAX_BATCHES_PER_FRAME) {
                break;
            }
            submitTranslucent(section, collector);
        }

        if (!FALLBACK_SECTIONS.isEmpty()) {
            FALLBACK_SECTIONS.sort(NEAR_FIRST);
            int remaining = MAX_FALLBACK_BLOCKS_PER_FRAME;
            for (StructureBatchCache.Section section : FALLBACK_SECTIONS) {
                if (remaining <= 0) {
                    break;
                }
                final int submitted = submitFallbackSection(section, collector, remaining);
                remaining -= submitted;
                fallbackBlocks += submitted;
            }
        }

        if (!SPECIAL_SECTIONS.isEmpty()) {
            SPECIAL_SECTIONS.sort(NEAR_FIRST);
            specialBlocks += submitSpecialSections(client.level, camera, collector, alpha);
        }

        if (!FALLBACK_BODIES.isEmpty()) {
            int remaining = MAX_FALLBACK_BOXES;
            for (StructureRenderState state : FALLBACK_BODIES) {
                if (remaining <= 0) {
                    break;
                }
                final int emitted = FallbackCuboids.submit(state, camera, collector, remaining);
                remaining -= emitted;
                if (emitted > 0) {
                    fallbackBodies++;
                }
            }
        }

        final long frameEnd = System.nanoTime();
        final double buildMillis = (buildEnd - buildStart) / 1_000_000.0;
        final double submitMillis = (frameEnd - frameStart) / 1_000_000.0 - buildMillis;

        stats = new BatchStats(
                StructureBatchCache.frame(),
                StructureBatchCache.bodyCount(),
                StructureBatchCache.sectionCount(),
                StructureBatchCache.batchCount(),
                StructureBatchCache.vertexCount(),
                submittedBatches,
                submittedVertices,
                culledSections,
                StructureBatchCache.pendingCount(),
                StructureBatchCache.overBudgetCount(),
                fallbackBodies,
                fallbackBlocks,
                StructureBatchCache.specialCellCount(),
                specialBlocks,
                BlockGeometryCache.size(),
                buildMillis,
                Math.max(0.0, submitMillis));
    }

    private static void collectSections(StructureBatchCache.Body body,
                                        CameraRenderState camera, double maxDistanceSq) {
        for (StructureBatchCache.Section section : body.sections) {
            final boolean hasNormal = section.vertexCount > 0L;
            final boolean hasSpecial = section.specialCellCount > 0;
            final boolean pending = section.dirty || section.overBudget;
            if (!hasNormal && !hasSpecial && !pending) {
                continue;
            }
            if (!section.prepareFrame(camera, maxDistanceSq)) {
                culledSections++;
                continue;
            }
            if (hasSpecial) {
                SPECIAL_SECTIONS.add(section);
            }
            if (hasNormal) {
                if (section.hasOpaque()) {
                    OPAQUE.add(section);
                }
                if (section.hasTranslucent()) {
                    TRANSLUCENT.add(section);
                }
            } else if (pending) {
                FALLBACK_SECTIONS.add(section);
            }
        }
    }

    private static void submitOpaque(StructureBatchCache.Section section,
                                     SubmitNodeCollector collector) {
        section.applyPose(FRAME_POSE);
        final int light = lightAt(section.frameCenterX, section.frameCenterY, section.frameCenterZ);
        for (BlockGeometry.Layer layer : BlockGeometry.LAYERS) {
            if (layer == BlockGeometry.Layer.TRANSLUCENT) {
                continue;
            }
            final SectionMesh mesh = section.meshes[layer.index()];
            if (mesh == null) {
                continue;
            }
            if (submittedBatches >= MAX_BATCHES_PER_FRAME) {
                return;
            }
            mesh.setFrameLight(light);
            collector.submitCustomGeometry(FRAME_POSE, layer.renderType(), mesh.renderer());
            submittedBatches++;
            submittedVertices += mesh.vertexCount();
        }
    }

    private static void submitTranslucent(StructureBatchCache.Section section,
                                          SubmitNodeCollector collector) {
        final SectionMesh mesh = section.meshes[BlockGeometry.Layer.TRANSLUCENT.index()];
        if (mesh == null) {
            return;
        }
        section.applyPose(FRAME_POSE);
        mesh.setFrameLight(lightAt(section.frameCenterX, section.frameCenterY, section.frameCenterZ));
        collector.submitCustomGeometry(FRAME_POSE,
                BlockGeometry.Layer.TRANSLUCENT.renderType(), mesh.renderer());
        submittedBatches++;
        submittedVertices += mesh.vertexCount();
    }

    /**
     * Submits special-rendered blocks after the normal section batches.
     *
     * <p>The pose origin is the block's rotated minimum corner:
     * {@code T(worldCenter - camera) * R * S * T(-0.5,-0.5,-0.5)}. A renderer
     * that follows the {@link SpecialBlockRenderer} contract translates by
     * {@code +0.5} inside that pose, which cancels the trailing shift and places
     * its unit cube at the rotated block centre. The exact centre is also
     * passed through the additive {@link SpecialBlockRenderer} overload for
     * renderers that need it directly.</p>
     */
    private static int submitSpecialSections(ClientLevel level, CameraRenderState camera,
                                             SubmitNodeCollector collector, float partialTick) {
        int submitted = 0;
        int remaining = MAX_SPECIAL_BLOCKS_PER_FRAME;

        outer:
        for (StructureBatchCache.Section section : SPECIAL_SECTIONS) {
            final StructureBatchCache.Body body = section.body;
            final double scale = body.blockScale;
            for (int cellIndex = 0; cellIndex < section.specialCellCount; cellIndex++) {
                if (remaining <= 0) {
                    break outer;
                }
                final SpecialBlockCell cell = section.specialCells[cellIndex];
                final SpecialBlockRenderer renderer =
                        SpecialBlockRenderers.get(cell.state().getBlock());
                if (renderer == null) {
                    continue;
                }

                final double localX = (section.originX + cell.x() + 0.5) * scale;
                final double localY = (section.originY + cell.y() + 0.5) * scale;
                final double localZ = (section.originZ + cell.z() + 0.5) * scale;
                body.framePose.transformPoint(SCRATCH.set(localX, localY, localZ), SCRATCH);

                final Vec3 worldCenter = new Vec3(SCRATCH.x, SCRATCH.y, SCRATCH.z);
                final BlockPos representativePos =
                        BlockPos.containing(SCRATCH.x, SCRATCH.y, SCRATCH.z);
                final double distanceSqr = worldCenter.distanceToSqr(camera.pos);
                final SpecialBlockRenderContext blockContext = new SpecialBlockRenderContext(
                        representativePos, cell.state(), level, partialTick, camera.pos, distanceSqr);

                final int light = specialLight(level, representativePos, cell.state(),
                        SCRATCH.x, SCRATCH.y, SCRATCH.z);

                FRAME_POSE.setIdentity();
                FRAME_POSE.translate(
                        (float) (SCRATCH.x - body.frameCameraX),
                        (float) (SCRATCH.y - body.frameCameraY),
                        (float) (SCRATCH.z - body.frameCameraZ));
                FRAME_POSE.rotate(body.frameQuaternion);
                FRAME_POSE.scale(body.frameScale, body.frameScale, body.frameScale);
                FRAME_POSE.translate(-0.5f, -0.5f, -0.5f);

                remaining--;
                try {
                    renderer.submit(blockContext, worldCenter, FRAME_POSE, collector,
                            light, OverlayTexture.NO_OVERLAY);
                    submitted++;
                } catch (RuntimeException exception) {
                    Crythst.LOGGER.debug(
                            "Special block renderer failed for " + cell.state(), exception);
                }
            }
        }
        return submitted;
    }

    /**
     * Bounded per-block fallback for a section whose vertex cache is still
     * pending or cannot fit in the global budget.
     */
    private static int submitFallbackSection(StructureBatchCache.Section section,
                                             SubmitNodeCollector collector, int budget) {
        final StructureBatchCache.Body body = section.body;
        final Transform3d pose = body.framePose;
        final double scale = body.blockScale;
        final int light = lightAt(section.frameCenterX, section.frameCenterY, section.frameCenterZ);
        final boolean anySpecial = !SpecialBlockRenderers.isEmpty();
        int submitted = 0;

        for (int index = 0; index < section.states.length && submitted < budget; index++) {
            final BlockState blockState = section.states[index];
            if (blockState == null || blockState.isAir()
                    || (anySpecial && SpecialBlockRenderers.isRegistered(blockState.getBlock()))) {
                continue;
            }
            final BlockGeometry geometry = BlockGeometryCache.get(blockState);
            if (geometry.empty) {
                continue;
            }

            final int x = index % section.sizeX;
            final int z = (index / section.sizeX) % section.sizeZ;
            final int y = index / (section.sizeX * section.sizeZ);

            final double localX = section.localMinX + x * scale;
            final double localY = section.localMinY + y * scale;
            final double localZ = section.localMinZ + z * scale;
            pose.transformPoint(SCRATCH.set(localX, localY, localZ), SCRATCH);

            FRAME_POSE.setIdentity();
            FRAME_POSE.translate(
                    (float) (SCRATCH.x - body.frameCameraX),
                    (float) (SCRATCH.y - body.frameCameraY),
                    (float) (SCRATCH.z - body.frameCameraZ));
            FRAME_POSE.rotate(body.frameQuaternion);
            FRAME_POSE.scale(body.frameScale, body.frameScale, body.frameScale);
            collector.submitBlockModel(FRAME_POSE, geometry.fallbackRenderType, geometry.parts,
                    BlockModelRenderState.EMPTY_TINTS, light,
                    OverlayTexture.NO_OVERLAY, 0);
            submitted++;
        }
        return submitted;
    }

    private static boolean isVisible(CameraRenderState camera, Aabb bounds) {
        if (camera.cullFrustum == null || !camera.initialized) {
            return true;
        }
        // Models can overhang their collider by a block; keep the structure
        // alive at the frustum edge rather than popping the whole body.
        return camera.cullFrustum.isVisible(new AABB(
                bounds.min().x - 1.0, bounds.min().y - 1.0, bounds.min().z - 1.0,
                bounds.max().x + 1.0, bounds.max().y + 1.0, bounds.max().z + 1.0));
    }

    private static int specialLight(ClientLevel level, BlockPos pos, BlockState state,
                                    double worldX, double worldY, double worldZ) {
        try {
            return SpecialBlockLight.getLightCoords(level, pos, state);
        } catch (RuntimeException ignored) {
            return lightAt(worldX, worldY, worldZ);
        }
    }

    private static int lightAt(double x, double y, double z) {
        final Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return LightCoordsUtil.FULL_BRIGHT;
        }
        try {
            return LightCoordsUtil.getLightCoords(client.level, BlockPos.containing(x, y, z));
        } catch (RuntimeException ignored) {
            return LightCoordsUtil.FULL_BRIGHT;
        }
    }
}
