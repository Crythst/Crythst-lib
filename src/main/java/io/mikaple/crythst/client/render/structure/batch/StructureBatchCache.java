package io.mikaple.crythst.client.render.structure.batch;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.render.block.SpecialBlockRenderers;
import io.mikaple.crythst.client.render.light.StructureLightOccluder;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Body/section cache for {@link BatchedStructureRenderer}.
 *
 * <p>Geometry is cached in three layers:</p>
 * <ol>
 *   <li>a global {@link BlockGeometryCache} palette, keyed by {@code BlockState};</li>
 *   <li>one {@link Body} per physics body, owning 16x16x16 sections whose
 *       {@code BlockState} arrays are copied from the immutable snapshot;</li>
 *   <li>one {@link SectionMesh} per section and render layer, containing flat
 *       section-local vertices.</li>
 * </ol>
 *
 * <p>Only the third layer is touched by a frame: sections are culled, sorted and
 * submitted. Rebuilds are triggered by a content compare against a new snapshot
 * (the snapshot object is replaced every few server ticks, even when nothing
 * changed), by a changed block scale / centre-of-mass offset, by a palette
 * generation change, or by the vertex budget freeing enough space.</p>
 */
final class StructureBatchCache {
    static final int SECTION_SIZE = 16;
    /** Keep a body cache around for this long after it disappears from the client view. */
    private static final long BODY_EXPIRY_NANOS = 2_000_000_000L;
    /** Maximum vertices resident across all section caches. */
    private static final long DEFAULT_VERTEX_BUDGET = 1_000_000L;
    /** Bound on special-rendered cells retained per section. */
    static final int MAX_SPECIAL_CELLS_PER_SECTION = 512;
    private static final SpecialBlockCell[] NO_SPECIAL_CELLS = new SpecialBlockCell[0];

    private static final Map<BodyKey, Body> BODIES = new ConcurrentHashMap<>();
    private static final AtomicLong CACHED_VERTICES = new AtomicLong();
    private static final Vector3d SCRATCH_A = new Vector3d();

    private static ResourceKey<Level> currentDimension;
    private static long paletteGeneration = -1L;
    private static int specialRegistrySize = -1;
    private static long frame;
    private static long vertexBudget = DEFAULT_VERTEX_BUDGET;

    private StructureBatchCache() {
    }

    /**
     * Starts a frame: handles dimension changes and palette reloads, and expires
     * bodies that have not been seen for a while.
     */
    static void beginFrame(ResourceKey<Level> dimension) {
        frame++;
        final long generation = BlockGeometryCache.generation();
        if (paletteGeneration != generation) {
            clearAll();
            paletteGeneration = generation;
        }
        // Registering a special renderer changes which blocks contribute normal
        // quads. The registry has no unregister API, so a size change is the
        // only normal <-> special transition that needs a geometry rebuild.
        final int specialSize = SpecialBlockRenderers.size();
        if (specialRegistrySize != specialSize) {
            clearAll();
            specialRegistrySize = specialSize;
        }
        if (!Objects.equals(currentDimension, dimension)) {
            clearAll();
            currentDimension = dimension;
        }
        prune(System.nanoTime());
    }

    static void setVertexBudget(long budget) {
        vertexBudget = Math.max(0L, budget);
    }

    static long vertexBudget() {
        return vertexBudget;
    }

    static long frame() {
        return frame;
    }

    /**
     * Returns the cache for a body, creating or refreshing it as needed.
     */
    static Body getOrCreate(ResourceKey<Level> dimension,
                            StructureBatchSnapshots.Snapshot snapshot,
                            Transform3d pose,
                            double blockScale) {
        final BodyKey key = new BodyKey(dimension, snapshot.bodyId());
        final long generation = BlockGeometryCache.generation();
        Body body = BODIES.get(key);

        if (body != null && !body.matches(snapshot, blockScale, generation)) {
            remove(key, body);
            body = null;
        }

        if (body == null) {
            body = Body.create(key, snapshot, pose, blockScale, generation);
            BODIES.put(key, body);
        } else {
            body.refresh(snapshot);
        }

        body.lastSeenNanos = System.nanoTime();
        return body;
    }

    /**
     * Builds dirty sections until the per-frame vertex budget is exhausted.
     *
     * @return the number of vertices built this call
     */
    static long buildPending(Body body, long vertexBudgetForFrame) {
        long built = 0L;
        for (Section section : body.sections) {
            if (!section.dirty || section.overBudget) {
                continue;
            }

            final long required = section.pendingVertices >= 0L
                    ? section.pendingVertices
                    : section.countVertices();
            section.pendingVertices = required;
            if (required == 0L) {
                final int enclosed = section.pendingEnclosedBlocks;
                section.invalidate();
                section.enclosedBlockCount = enclosed;
                section.dirty = false;
                section.built = true;
                continue;
            }

            final long after = CACHED_VERTICES.get() - section.vertexCount + required;
            if (after > vertexBudget) {
                // This section can never fit within the global cache budget
                // while other bodies hold vertices. Fall back to the bounded
                // per-block path instead of thrashing the cache.
                section.invalidate();
                section.overBudget = true;
                section.dirty = false;
                section.built = true;
                continue;
            }

            if (built + required > vertexBudgetForFrame) {
                break;
            }

            final int[] enclosed = new int[1];
            final SectionMesh[] meshes = SectionMesh.build(
                    section.states, section.sizeX, section.sizeY, section.sizeZ,
                    section.originX, section.originY, section.originZ, body, enclosed);
            section.install(meshes, enclosed[0]);
            built += required;
        }
        return built;
    }

    /**
     * Re-queues over-budget sections after the cache has shrunk enough that
     * they might fit now.
     */
    static void retryOverBudgetSections() {
        if (CACHED_VERTICES.get() >= vertexBudget / 2L) {
            return;
        }
        for (Body body : BODIES.values()) {
            for (Section section : body.sections) {
                if (section.overBudget) {
                    section.overBudget = false;
                    section.built = false;
                    section.dirty = true;
                }
            }
        }
    }

    static void clearAll() {
        BODIES.clear();
        CACHED_VERTICES.set(0L);
    }

    static int bodyCount() {
        return BODIES.size();
    }

    static int sectionCount() {
        int count = 0;
        for (Body body : BODIES.values()) {
            for (Section section : body.sections) {
                if (section.vertexCount > 0L) {
                    count++;
                }
            }
        }
        return count;
    }

    static int batchCount() {
        int count = 0;
        for (Body body : BODIES.values()) {
            for (Section section : body.sections) {
                for (SectionMesh mesh : section.meshes) {
                    if (mesh != null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns the current solid-occupancy mask for a cached body. Used by the
     * public renderer API so other systems can later share the same occlusion
     * data without touching the mesh caches.
     */
    static StructureLightOccluder occluder(ResourceKey<Level> dimension, long bodyId) {
        final Body body = BODIES.get(new BodyKey(dimension, bodyId));
        return body == null ? StructureLightOccluder.EMPTY : body.occluder;
    }

    static long vertexCount() {
        return CACHED_VERTICES.get();
    }

    static int pendingCount() {
        int count = 0;
        for (Body body : BODIES.values()) {
            for (Section section : body.sections) {
                if (section.dirty) {
                    count++;
                }
            }
        }
        return count;
    }

    static int overBudgetCount() {
        int count = 0;
        for (Body body : BODIES.values()) {
            for (Section section : body.sections) {
                if (section.overBudget) {
                    count++;
                }
            }
        }
        return count;
    }

    static int specialCellCount() {
        int count = 0;
        for (Body body : BODIES.values()) {
            for (Section section : body.sections) {
                count += section.specialCellCount;
            }
        }
        return count;
    }

    /**
     * Full solid blocks whose six neighbours are also full solid blocks and
     * which therefore contributed no quads to the resident section meshes.
     */
    static int enclosedBlockCount() {
        int count = 0;
        for (Body body : BODIES.values()) {
            for (Section section : body.sections) {
                if (section.built) {
                    count += section.enclosedBlockCount;
                }
            }
        }
        return count;
    }

    private static void prune(long now) {
        BODIES.entrySet().removeIf(entry -> {
            final Body body = entry.getValue();
            if (now - body.lastSeenNanos <= BODY_EXPIRY_NANOS) {
                return false;
            }
            subtract(body.vertexCount);
            return true;
        });
    }

    private static void remove(BodyKey key, Body body) {
        BODIES.remove(key, body);
        subtract(body.vertexCount);
    }

    private static void subtract(long vertices) {
        if (vertices != 0L) {
            CACHED_VERTICES.addAndGet(-vertices);
        }
    }

    record BodyKey(ResourceKey<Level> dimension, long bodyId) {
    }

    /**
     * One structure body's section grid.
     */
    static final class Body implements StructureVoxelView {
        final BodyKey key;
        final int minX;
        final int minY;
        final int minZ;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final int sectionsX;
        final int sectionsY;
        final int sectionsZ;
        final double blockScale;
        final long generation;
        final Section[] sections;

        StructureBatchSnapshots.Snapshot snapshot;
        /** Occupancy for AO ({@code canOcclude()}). */
        StructureLightOccluder occluder = StructureLightOccluder.EMPTY;
        /** Exact whole-cube occupancy for enclosed-block culling. */
        StructureLightOccluder opaqueOccluder = StructureLightOccluder.EMPTY;
        long contentRevision;
        long lastSeenNanos;
        long vertexCount;

        // Per-frame state, filled by the renderer before culling and submission.
        Transform3d framePose;
        final Quaternionf frameQuaternion = new Quaternionf();
        float frameScale;
        double frameCameraX;
        double frameCameraY;
        double frameCameraZ;

        // Camera in structure-local voxel coordinates and the optional
        // occlusion BFS result for this frame.
        double frameCameraVoxelX;
        double frameCameraVoxelY;
        double frameCameraVoxelZ;
        boolean frameCameraInsideBody;
        boolean frameOcclusionReady;
        /** {@code null} means "everything visible" (disabled or camera outside). */
        boolean[] frameOcclusion;

        private Body(BodyKey key, StructureBatchSnapshots.Snapshot snapshot,
                     Transform3d pose, double blockScale,
                     long generation, Section[] sections,
                     int sectionsX, int sectionsY, int sectionsZ) {
            this.key = key;
            this.snapshot = snapshot;
            this.contentRevision = snapshot.contentRevision();
            this.minX = snapshot.minX();
            this.minY = snapshot.minY();
            this.minZ = snapshot.minZ();
            this.sizeX = snapshot.sizeX();
            this.sizeY = snapshot.sizeY();
            this.sizeZ = snapshot.sizeZ();
            this.sectionsX = sectionsX;
            this.sectionsY = sectionsY;
            this.sectionsZ = sectionsZ;
            this.blockScale = blockScale;
            this.generation = generation;
            this.sections = sections;
            this.framePose = pose;
            this.frameScale = (float) blockScale;
        }

        static Body create(BodyKey key, StructureBatchSnapshots.Snapshot snapshot,
                           Transform3d pose, double blockScale,
                           long generation) {
            final int sectionsX = (snapshot.sizeX() + SECTION_SIZE - 1) / SECTION_SIZE;
            final int sectionsY = (snapshot.sizeY() + SECTION_SIZE - 1) / SECTION_SIZE;
            final int sectionsZ = (snapshot.sizeZ() + SECTION_SIZE - 1) / SECTION_SIZE;
            final List<Section> sections = new ArrayList<>(
                    sectionsX * sectionsY * sectionsZ);

            for (int sectionY = 0; sectionY < sectionsY; sectionY++) {
                for (int sectionZ = 0; sectionZ < sectionsZ; sectionZ++) {
                    for (int sectionX = 0; sectionX < sectionsX; sectionX++) {
                        final int originX = snapshot.minX() + sectionX * SECTION_SIZE;
                        final int originY = snapshot.minY() + sectionY * SECTION_SIZE;
                        final int originZ = snapshot.minZ() + sectionZ * SECTION_SIZE;
                        final int sectionSizeX = Math.min(SECTION_SIZE, snapshot.sizeX() - sectionX * SECTION_SIZE);
                        final int sectionSizeY = Math.min(SECTION_SIZE, snapshot.sizeY() - sectionY * SECTION_SIZE);
                        final int sectionSizeZ = Math.min(SECTION_SIZE, snapshot.sizeZ() - sectionZ * SECTION_SIZE);

                        sections.add(Section.create(snapshot,
                                originX, originY, originZ,
                                sectionSizeX, sectionSizeY, sectionSizeZ,
                                blockScale));
                    }
                }
            }

            final Body body = new Body(key, snapshot, pose, blockScale,
                    generation, sections.toArray(Section[]::new),
                    sectionsX, sectionsY, sectionsZ);
            for (Section section : body.sections) {
                section.body = body;
            }
            body.rebuildOccluder();
            return body;
        }

        /**
         * Rebuilds both per-body occupancy masks from the current section
         * states. {@link #occluder} follows {@code canOcclude()} for AO and the
         * public occlusion API; {@link #opaqueOccluder} keeps only whole-cube
         * states and backs the enclosed-block predicate.
         */
        void rebuildOccluder() {
            final StructureLightOccluder.Builder builder =
                    StructureLightOccluder.builder(minX, minY, minZ, sizeX, sizeY, sizeZ);
            final StructureLightOccluder.Builder opaqueBuilder =
                    StructureLightOccluder.builder(minX, minY, minZ, sizeX, sizeY, sizeZ);
            for (Section section : sections) {
                final int layerStride = section.sizeX * section.sizeZ;
                for (int index = 0; index < section.states.length; index++) {
                    final BlockState state = section.states[index];
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    final boolean canOcclude = state.canOcclude();
                    final boolean fullCube = state.isSolidRender();
                    if (!canOcclude && !fullCube) {
                        continue;
                    }
                    final int x = index % section.sizeX;
                    final int z = (index / section.sizeX) % section.sizeZ;
                    final int y = index / layerStride;
                    final int worldX = section.originX + x;
                    final int worldY = section.originY + y;
                    final int worldZ = section.originZ + z;
                    if (canOcclude) {
                        builder.setSolid(worldX, worldY, worldZ, true);
                    }
                    if (fullCube) {
                        opaqueBuilder.setSolid(worldX, worldY, worldZ, true);
                    }
                }
            }
            occluder = builder.build();
            opaqueOccluder = opaqueBuilder.build();
        }

        @Override
        public boolean isOccluded(int x, int y, int z) {
            return occluder.isSolid(x, y, z);
        }

        @Override
        public boolean isFullyOpaque(int x, int y, int z) {
            return opaqueOccluder.isSolid(x, y, z);
        }

        @Override
        public BlockState stateAt(int x, int y, int z) {
            if (x < minX || y < minY || z < minZ
                    || x >= minX + sizeX || y >= minY + sizeY || z >= minZ + sizeZ) {
                return null;
            }
            final int sectionX = (x - minX) / SECTION_SIZE;
            final int sectionY = (y - minY) / SECTION_SIZE;
            final int sectionZ = (z - minZ) / SECTION_SIZE;
            final Section section = sections[(sectionY * sectionsZ + sectionZ) * sectionsX + sectionX];
            final int localX = x - section.originX;
            final int localY = y - section.originY;
            final int localZ = z - section.originZ;
            final int index = (localY * section.sizeZ + localZ) * section.sizeX + localX;
            return section.states[index];
        }

        boolean matches(StructureBatchSnapshots.Snapshot snapshot,
                        double blockScale, long generation) {
            return this.generation == generation
                    && this.blockScale == blockScale
                    && this.minX == snapshot.minX()
                    && this.minY == snapshot.minY()
                    && this.minZ == snapshot.minZ()
                    && this.sizeX == snapshot.sizeX()
                    && this.sizeY == snapshot.sizeY()
                    && this.sizeZ == snapshot.sizeZ();
        }

        /**
         * Called when a new snapshot object is published. Compares contents per
         * section and only invalidates sections whose blocks actually changed.
         */
        void refresh(StructureBatchSnapshots.Snapshot snapshot) {
            if (this.contentRevision == snapshot.contentRevision()
                    && this.snapshot.bodyId() == snapshot.bodyId()
                    && this.minX == snapshot.minX()
                    && this.minY == snapshot.minY()
                    && this.minZ == snapshot.minZ()
                    && this.sizeX == snapshot.sizeX()
                    && this.sizeY == snapshot.sizeY()
                    && this.sizeZ == snapshot.sizeZ()) {
                return;
            }
            this.snapshot = snapshot;
            this.contentRevision = snapshot.contentRevision();

            int[] changed = null;
            int changedCount = 0;
            for (int i = 0; i < sections.length; i++) {
                if (!sections[i].refresh(snapshot)) {
                    continue;
                }
                if (changed == null) {
                    changed = new int[Math.min(16, sections.length)];
                } else if (changedCount == changed.length) {
                    changed = Arrays.copyOf(changed, Math.min(sections.length, changedCount * 2));
                }
                changed[changedCount++] = i;
            }
            if (changedCount == 0) {
                return;
            }

            rebuildOccluder();
            // Culling and AO read one cell across a section border, so a
            // changed border block also invalidates its neighbours. For a
            // large simultaneous edit, rebuilding everything is cheaper than
            // testing every changed section against every section.
            if (changedCount > 16) {
                for (Section section : sections) {
                    section.markDirty();
                }
                return;
            }
            for (int i = 0; i < changedCount; i++) {
                markNeighboursDirty(sections[changed[i]]);
            }
        }

        /**
         * Marks all sections whose voxel range is within one cell of the source
         * section (including the source itself).
         */
        private void markNeighboursDirty(Section source) {
            final int minBlockX = source.originX - 1;
            final int minBlockY = source.originY - 1;
            final int minBlockZ = source.originZ - 1;
            final int maxBlockX = source.originX + source.sizeX;
            final int maxBlockY = source.originY + source.sizeY;
            final int maxBlockZ = source.originZ + source.sizeZ;
            for (Section section : sections) {
                if (section.originX > maxBlockX
                        || section.originY > maxBlockY
                        || section.originZ > maxBlockZ
                        || section.originX + section.sizeX <= minBlockX
                        || section.originY + section.sizeY <= minBlockY
                        || section.originZ + section.sizeZ <= minBlockZ) {
                    continue;
                }
                section.markDirty();
            }
        }

        void beginFrame(Transform3d pose, double blockScale, CameraRenderState camera) {
            this.framePose = pose;
            final Quaterniondc rotation = pose.rotation();
            this.frameQuaternion.set(
                    (float) rotation.x(), (float) rotation.y(),
                    (float) rotation.z(), (float) rotation.w()).normalize();
            this.frameScale = (float) blockScale;
            this.frameCameraX = camera.pos.x;
            this.frameCameraY = camera.pos.y;
            this.frameCameraZ = camera.pos.z;

            // Occlusion is only valid from inside the body. Convert the camera
            // to structure-local voxel coordinates once; the BFS is lazy so a
            // body that is fully frustum-culled pays nothing for it.
            frameOcclusionReady = false;
            frameOcclusion = null;
            if (blockScale > 1.0e-9) {
                final Vector3d local = pose.inverseTransformPoint(
                        new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z));
                final double inverseScale = 1.0 / blockScale;
                frameCameraVoxelX = local.x * inverseScale;
                frameCameraVoxelY = local.y * inverseScale;
                frameCameraVoxelZ = local.z * inverseScale;
                frameCameraInsideBody = frameCameraVoxelX >= minX
                        && frameCameraVoxelY >= minY
                        && frameCameraVoxelZ >= minZ
                        && frameCameraVoxelX < minX + sizeX
                        && frameCameraVoxelY < minY + sizeY
                        && frameCameraVoxelZ < minZ + sizeZ;
            } else {
                frameCameraVoxelX = 0.0;
                frameCameraVoxelY = 0.0;
                frameCameraVoxelZ = 0.0;
                frameCameraInsideBody = false;
            }
        }

        /**
         * @return whether this body actually used section occlusion this frame
         */
        boolean occlusionCullingActive() {
            ensureFrameOcclusion();
            return frameOcclusion != null;
        }

        /**
         * Occlusion result for one section. Any camera-outside / disabled /
         * malformed case returns visible; the caller then keeps the normal
         * distance and frustum culling path.
         */
        boolean isSectionOcclusionVisible(int sectionIndex) {
            if (!SectionCulling.occlusionCullingEnabled()) {
                return true;
            }
            ensureFrameOcclusion();
            return frameOcclusion == null || frameOcclusion[sectionIndex];
        }

        private void ensureFrameOcclusion() {
            if (frameOcclusionReady) {
                return;
            }
            frameOcclusionReady = true;
            if (!SectionCulling.occlusionCullingEnabled()
                    || !frameCameraInsideBody
                    || sections.length == 0) {
                frameOcclusion = null;
                return;
            }

            final int startX = (int) Math.floor(frameCameraVoxelX - minX) / SECTION_SIZE;
            final int startY = (int) Math.floor(frameCameraVoxelY - minY) / SECTION_SIZE;
            final int startZ = (int) Math.floor(frameCameraVoxelZ - minZ) / SECTION_SIZE;
            frameOcclusion = SectionOcclusion.computeVisible(
                    sectionsX, sectionsY, sectionsZ,
                    startX, startY, startZ, true,
                    (sectionIndex, direction) -> sections[sectionIndex].faceFullyOpaque(direction));
        }
    }

    /**
     * One 16x16x16 slice of a body. The state array only covers the part of
     * the section that intersects the snapshot bounds.
     */
    static final class Section {
        Body body;
        final int originX;
        final int originY;
        final int originZ;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final double localMinX;
        final double localMinY;
        final double localMinZ;
        final double localMaxX;
        final double localMaxY;
        final double localMaxZ;
        final BlockState[] states;
        final SectionMesh[] meshes = new SectionMesh[3];

        SpecialBlockCell[] specialCells = NO_SPECIAL_CELLS;
        int specialCellCount;

        boolean dirty;
        boolean built;
        boolean overBudget;
        long vertexCount;
        /** Full solid blocks skipped by enclosed-block culling at build time. */
        int enclosedBlockCount;
        /** Cached per-face opacity used by the section occlusion BFS. */
        boolean[] faceOpaque;
        /** Cached result of {@link #countVertices()} for a dirty section. */
        long pendingVertices = -1L;
        /** Enclosed blocks seen while counting a dirty section's vertices. */
        int pendingEnclosedBlocks;

        double frameDistanceSq;
        double frameCenterX;
        double frameCenterY;
        double frameCenterZ;

        private Section(int originX, int originY, int originZ,
                        int sizeX, int sizeY, int sizeZ,
                        double localMinX, double localMinY, double localMinZ,
                        double localMaxX, double localMaxY, double localMaxZ,
                        BlockState[] states) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.localMinX = localMinX;
            this.localMinY = localMinY;
            this.localMinZ = localMinZ;
            this.localMaxX = localMaxX;
            this.localMaxY = localMaxY;
            this.localMaxZ = localMaxZ;
            this.states = states;
        }

        static Section create(StructureBatchSnapshots.Snapshot snapshot,
                              int originX, int originY, int originZ,
                              int sizeX, int sizeY, int sizeZ,
                              double blockScale) {
            // Section coordinates are structure-local metres. The structure
            // pose (not the body pose) already handles centre-of-mass offset;
            // subtracting it here would double-shift the rendering.
            final double localMinX = originX * blockScale;
            final double localMinY = originY * blockScale;
            final double localMinZ = originZ * blockScale;
            final double localMaxX = (originX + sizeX) * blockScale;
            final double localMaxY = (originY + sizeY) * blockScale;
            final double localMaxZ = (originZ + sizeZ) * blockScale;

            final BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
            final Section section = new Section(originX, originY, originZ,
                    sizeX, sizeY, sizeZ,
                    localMinX, localMinY, localMinZ,
                    localMaxX, localMaxY, localMaxZ,
                    states);
            section.refresh(snapshot);
            section.dirty = true;
            return section;
        }

        boolean refresh(StructureBatchSnapshots.Snapshot snapshot) {
            boolean changed = false;
            for (int index = 0; index < states.length; index++) {
                final int x = index % sizeX;
                final int z = (index / sizeX) % sizeZ;
                final int y = index / (sizeX * sizeZ);
                final BlockState current = snapshot.stateAt(originX + x, originY + y, originZ + z);
                if (states[index] != current) {
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                return false;
            }
            for (int index = 0; index < states.length; index++) {
                final int x = index % sizeX;
                final int z = (index / sizeX) % sizeZ;
                final int y = index / (sizeX * sizeZ);
                states[index] = snapshot.stateAt(originX + x, originY + y, originZ + z);
            }
            faceOpaque = null;
            collectSpecialCells();
            markDirty();
            return true;
        }

        /**
         * Cached "this section face is a solid wall" test for the occlusion
         * BFS. It is recomputed only after a content change.
         */
        boolean faceFullyOpaque(Direction direction) {
            boolean[] cached = faceOpaque;
            if (cached == null) {
                cached = new boolean[6];
                for (Direction face : Direction.values()) {
                    cached[face.ordinal()] = SectionCulling.isFaceFullyOpaque(
                            body, originX, originY, originZ,
                            sizeX, sizeY, sizeZ, face);
                }
                faceOpaque = cached;
            }
            return cached[direction.ordinal()];
        }

        /** Drops the built mesh and queues a rebuild. */
        void markDirty() {
            pendingVertices = -1L;
            invalidate();
            dirty = true;
            built = false;
            overBudget = false;
        }

        /**
         * Keeps the cells that need a {@code SpecialBlockRenderer}. They are
         * collected even while the normal section mesh is pending or over
         * budget, so a special block never disappears just because its
         * neighbours are still being batched.
         */
        private void collectSpecialCells() {
            if (SpecialBlockRenderers.isEmpty()) {
                specialCells = NO_SPECIAL_CELLS;
                specialCellCount = 0;
                return;
            }

            SpecialBlockCell[] found = null;
            int count = 0;
            for (int index = 0; index < states.length; index++) {
                final BlockState state = states[index];
                if (state == null || state.isAir()
                        || !SpecialBlockRenderers.isRegistered(state.getBlock())) {
                    continue;
                }
                if (count == MAX_SPECIAL_CELLS_PER_SECTION) {
                    break;
                }
                if (found == null) {
                    found = new SpecialBlockCell[Math.min(16, MAX_SPECIAL_CELLS_PER_SECTION)];
                } else if (count == found.length) {
                    found = Arrays.copyOf(found,
                            Math.min(MAX_SPECIAL_CELLS_PER_SECTION, count * 2));
                }
                final int x = index % sizeX;
                final int z = (index / sizeX) % sizeZ;
                final int y = index / (sizeX * sizeZ);
                found[count++] = new SpecialBlockCell(x, y, z, state);
            }

            specialCells = count == 0 ? NO_SPECIAL_CELLS : found;
            specialCellCount = count;
        }

        long countVertices() {
            long count = 0L;
            int enclosed = 0;
            final boolean anySpecial = !SpecialBlockRenderers.isEmpty();
            final StructureVoxelView view = body;
            final int layerStride = sizeX * sizeZ;
            for (int index = 0; index < states.length; index++) {
                final BlockState state = states[index];
                if (state == null || state.isAir()
                        || (anySpecial && SpecialBlockRenderers.isRegistered(state.getBlock()))) {
                    continue;
                }
                final int x = index % sizeX;
                final int z = (index / sizeX) % sizeZ;
                final int y = index / layerStride;
                final int worldX = originX + x;
                final int worldY = originY + y;
                final int worldZ = originZ + z;
                if (SectionCulling.isFullyEnclosed(view, state, worldX, worldY, worldZ)) {
                    enclosed++;
                    continue;
                }
                final BlockGeometry geometry = BlockGeometryCache.get(state);
                if (geometry.empty) {
                    continue;
                }
                for (BlockGeometry.Layer layer : BlockGeometry.LAYERS) {
                    final BlockGeometry.Quads quads = geometry.quads(layer);
                    for (int quad = 0; quad < quads.quadCount; quad++) {
                        if (!SectionMesh.isCulled(view, state, quads, quad,
                                worldX, worldY, worldZ)) {
                            count += 4L;
                        }
                    }
                }
            }
            pendingEnclosedBlocks = enclosed;
            return count;
        }

        void install(SectionMesh[] newMeshes, int enclosedBlocks) {
            long installed = 0L;
            for (int i = 0; i < meshes.length; i++) {
                meshes[i] = newMeshes[i];
                if (newMeshes[i] != null) {
                    installed += newMeshes[i].vertexCount();
                }
            }
            if (vertexCount != 0L) {
                subtract(vertexCount);
                body.vertexCount -= vertexCount;
            }
            vertexCount = installed;
            if (installed != 0L) {
                CACHED_VERTICES.addAndGet(installed);
                body.vertexCount += installed;
            }
            pendingVertices = -1L;
            enclosedBlockCount = enclosedBlocks;
            dirty = false;
            built = true;
            overBudget = false;
        }

        void invalidate() {
            if (vertexCount != 0L) {
                subtract(vertexCount);
                body.vertexCount -= vertexCount;
                vertexCount = 0L;
            }
            enclosedBlockCount = 0;
            for (int i = 0; i < meshes.length; i++) {
                meshes[i] = null;
            }
        }

        boolean hasOpaque() {
            return meshes[BlockGeometry.Layer.SOLID.index()] != null
                    || meshes[BlockGeometry.Layer.CUTOUT.index()] != null;
        }

        boolean hasTranslucent() {
            return meshes[BlockGeometry.Layer.TRANSLUCENT.index()] != null;
        }

        enum CullReason {
            VISIBLE,
            DISTANCE,
            FRUSTUM,
            OCCLUSION
        }

        /**
         * Computes the section's world centre, distance and conservative
         * frustum visibility.
         */
        CullReason prepareFrame(CameraRenderState camera, double maxDistanceSq) {
            final Transform3d pose = body.framePose;
            final double centerX = (localMinX + localMaxX) * 0.5;
            final double centerY = (localMinY + localMaxY) * 0.5;
            final double centerZ = (localMinZ + localMaxZ) * 0.5;
            pose.transformPoint(SCRATCH_A.set(centerX, centerY, centerZ), SCRATCH_A);

            final double dx = SCRATCH_A.x - body.frameCameraX;
            final double dy = SCRATCH_A.y - body.frameCameraY;
            final double dz = SCRATCH_A.z - body.frameCameraZ;
            frameDistanceSq = dx * dx + dy * dy + dz * dz;
            if (frameDistanceSq > maxDistanceSq) {
                return CullReason.DISTANCE;
            }
            frameCenterX = SCRATCH_A.x;
            frameCenterY = SCRATCH_A.y;
            frameCenterZ = SCRATCH_A.z;

            if (camera.cullFrustum == null || !camera.initialized) {
                return CullReason.VISIBLE;
            }

            // Every transformed corner must be inside the hull. A one-block
            // model margin keeps fences/plants/modded overhangs alive at the
            // frustum edge. The hull is exact for the rotated section box, so
            // this test can only keep extra sections, never drop a visible one.
            final double margin = body.blockScale * SectionCulling.MODEL_MARGIN_BLOCKS;
            final AABB bounds = SectionCulling.sectionBounds(pose,
                    localMinX, localMinY, localMinZ,
                    localMaxX, localMaxY, localMaxZ,
                    margin);
            return camera.cullFrustum.isVisible(bounds)
                    ? CullReason.VISIBLE
                    : CullReason.FRUSTUM;
        }

        /**
         * Applies the interpolated structure transform plus a camera-relative
         * translation to {@code pose}. The cached vertices are section-local.
         */
        void applyPose(PoseStack pose) {
            final Transform3d transform = body.framePose;
            transform.transformPoint(SCRATCH_A.set(localMinX, localMinY, localMinZ), SCRATCH_A);
            pose.setIdentity();
            pose.translate(
                    (float) (SCRATCH_A.x - body.frameCameraX),
                    (float) (SCRATCH_A.y - body.frameCameraY),
                    (float) (SCRATCH_A.z - body.frameCameraZ));
            pose.rotate(body.frameQuaternion);
            pose.scale(body.frameScale, body.frameScale, body.frameScale);
        }
    }
}
