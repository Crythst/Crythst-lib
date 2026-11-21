package io.mikaple.crythst.client.render.structure.batch;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
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

    private static final Map<BodyKey, Body> BODIES = new ConcurrentHashMap<>();
    private static final AtomicLong CACHED_VERTICES = new AtomicLong();
    private static final Vector3d SCRATCH_A = new Vector3d();
    private static final Vector3d SCRATCH_B = new Vector3d();

    private static ResourceKey<Level> currentDimension;
    private static long paletteGeneration = -1L;
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
                            StructureBlockSnapshots.Snapshot snapshot,
                            StructureRenderState state) {
        final BodyKey key = new BodyKey(dimension, snapshot.bodyId());
        final long generation = BlockGeometryCache.generation();
        Body body = BODIES.get(key);

        if (body != null && !body.matches(snapshot, state, generation)) {
            remove(key, body);
            body = null;
        }

        if (body == null) {
            body = Body.create(key, snapshot, state, generation);
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

            final long required = section.countVertices();
            if (required == 0L) {
                section.invalidate();
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

            final SectionMesh[] meshes = SectionMesh.build(
                    section.states, section.sizeX, section.sizeY, section.sizeZ);
            section.install(meshes);
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
    static final class Body {
        final BodyKey key;
        final int minX;
        final int minY;
        final int minZ;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final double blockScale;
        final double centerOfMassX;
        final double centerOfMassY;
        final double centerOfMassZ;
        final long generation;
        final Section[] sections;

        StructureBlockSnapshots.Snapshot snapshot;
        long lastSeenNanos;
        long vertexCount;

        // Per-frame state, filled by the renderer before culling and submission.
        StructureRenderState frameState;
        final Quaternionf frameQuaternion = new Quaternionf();
        float frameScale;
        double frameCameraX;
        double frameCameraY;
        double frameCameraZ;

        private Body(BodyKey key, StructureBlockSnapshots.Snapshot snapshot,
                     StructureRenderState state, long generation, Section[] sections) {
            this.key = key;
            this.snapshot = snapshot;
            this.minX = snapshot.minX();
            this.minY = snapshot.minY();
            this.minZ = snapshot.minZ();
            this.sizeX = snapshot.sizeX();
            this.sizeY = snapshot.sizeY();
            this.sizeZ = snapshot.sizeZ();
            this.blockScale = state.blockScale();
            this.centerOfMassX = state.centerOfMassOffset().x;
            this.centerOfMassY = state.centerOfMassOffset().y;
            this.centerOfMassZ = state.centerOfMassOffset().z;
            this.generation = generation;
            this.sections = sections;
        }

        static Body create(BodyKey key, StructureBlockSnapshots.Snapshot snapshot,
                           StructureRenderState state, long generation) {
            final int sectionsX = (snapshot.sizeX() + SECTION_SIZE - 1) / SECTION_SIZE;
            final int sectionsY = (snapshot.sizeY() + SECTION_SIZE - 1) / SECTION_SIZE;
            final int sectionsZ = (snapshot.sizeZ() + SECTION_SIZE - 1) / SECTION_SIZE;
            final List<Section> sections = new ArrayList<>(
                    sectionsX * sectionsY * sectionsZ);
            final double blockScale = state.blockScale();
            final Vector3d centerOfMass = state.centerOfMassOffset();

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
                                blockScale, centerOfMass));
                    }
                }
            }

            final Body body = new Body(key, snapshot, state, generation,
                    sections.toArray(Section[]::new));
            for (Section section : body.sections) {
                section.body = body;
            }
            return body;
        }

        boolean matches(StructureBlockSnapshots.Snapshot snapshot,
                        StructureRenderState state, long generation) {
            final Vector3d centerOfMass = state.centerOfMassOffset();
            return this.generation == generation
                    && this.blockScale == state.blockScale()
                    && this.centerOfMassX == centerOfMass.x
                    && this.centerOfMassY == centerOfMass.y
                    && this.centerOfMassZ == centerOfMass.z
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
        void refresh(StructureBlockSnapshots.Snapshot snapshot) {
            if (this.snapshot == snapshot) {
                return;
            }
            this.snapshot = snapshot;
            for (Section section : sections) {
                section.refresh(snapshot);
            }
        }

        void beginFrame(StructureRenderState state, CameraRenderState camera) {
            this.frameState = state;
            final Quaterniondc rotation = state.pose().rotation();
            this.frameQuaternion.set(
                    (float) rotation.x(), (float) rotation.y(),
                    (float) rotation.z(), (float) rotation.w()).normalize();
            this.frameScale = (float) state.blockScale();
            this.frameCameraX = camera.pos.x;
            this.frameCameraY = camera.pos.y;
            this.frameCameraZ = camera.pos.z;
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

        boolean dirty;
        boolean built;
        boolean overBudget;
        long vertexCount;

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

        static Section create(StructureBlockSnapshots.Snapshot snapshot,
                              int originX, int originY, int originZ,
                              int sizeX, int sizeY, int sizeZ,
                              double blockScale, Vector3dc centerOfMass) {
            final double localMinX = originX * blockScale - centerOfMass.x();
            final double localMinY = originY * blockScale - centerOfMass.y();
            final double localMinZ = originZ * blockScale - centerOfMass.z();
            final double localMaxX = (originX + sizeX) * blockScale - centerOfMass.x();
            final double localMaxY = (originY + sizeY) * blockScale - centerOfMass.y();
            final double localMaxZ = (originZ + sizeZ) * blockScale - centerOfMass.z();

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

        void refresh(StructureBlockSnapshots.Snapshot snapshot) {
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
                return;
            }
            for (int index = 0; index < states.length; index++) {
                final int x = index % sizeX;
                final int z = (index / sizeX) % sizeZ;
                final int y = index / (sizeX * sizeZ);
                states[index] = snapshot.stateAt(originX + x, originY + y, originZ + z);
            }
            invalidate();
            dirty = true;
            built = false;
            overBudget = false;
        }

        long countVertices() {
            long count = 0L;
            for (BlockState state : states) {
                if (state == null || state.isAir()) {
                    continue;
                }
                final BlockGeometry geometry = BlockGeometryCache.get(state);
                if (!geometry.empty) {
                    count += geometry.vertexCount;
                }
            }
            return count;
        }

        void install(SectionMesh[] newMeshes) {
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

        /**
         * Computes the section's world centre, distance and frustum visibility.
         *
         * @return whether the section should be submitted this frame
         */
        boolean prepareFrame(CameraRenderState camera, double maxDistanceSq) {
            final Transform3d pose = body.frameState.pose();
            final double centerX = (localMinX + localMaxX) * 0.5;
            final double centerY = (localMinY + localMaxY) * 0.5;
            final double centerZ = (localMinZ + localMaxZ) * 0.5;
            pose.transformPoint(SCRATCH_A.set(centerX, centerY, centerZ), SCRATCH_A);

            final double dx = SCRATCH_A.x - body.frameCameraX;
            final double dy = SCRATCH_A.y - body.frameCameraY;
            final double dz = SCRATCH_A.z - body.frameCameraZ;
            frameDistanceSq = dx * dx + dy * dy + dz * dz;
            if (frameDistanceSq > maxDistanceSq) {
                return false;
            }
            frameCenterX = SCRATCH_A.x;
            frameCenterY = SCRATCH_A.y;
            frameCenterZ = SCRATCH_A.z;

            if (camera.cullFrustum == null || !camera.initialized) {
                return true;
            }

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            // Baked models may overhang their voxel (fences, plants, modded
            // models). A one-block margin keeps an overhanging face from being
            // culled when only the cube itself leaves the frustum.
            final double margin = body.blockScale;
            for (int corner = 0; corner < 8; corner++) {
                SCRATCH_B.set(
                        (corner & 1) == 0 ? localMinX - margin : localMaxX + margin,
                        (corner & 2) == 0 ? localMinY - margin : localMaxY + margin,
                        (corner & 4) == 0 ? localMinZ - margin : localMaxZ + margin);
                pose.transformPoint(SCRATCH_B, SCRATCH_B);
                minX = Math.min(minX, SCRATCH_B.x);
                minY = Math.min(minY, SCRATCH_B.y);
                minZ = Math.min(minZ, SCRATCH_B.z);
                maxX = Math.max(maxX, SCRATCH_B.x);
                maxY = Math.max(maxY, SCRATCH_B.y);
                maxZ = Math.max(maxZ, SCRATCH_B.z);
            }
            return camera.cullFrustum.isVisible(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        }

        /**
         * Applies the interpolated structure transform plus a camera-relative
         * translation to {@code pose}. The cached vertices are section-local.
         */
        void applyPose(PoseStack pose) {
            final Transform3d transform = body.frameState.pose();
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
