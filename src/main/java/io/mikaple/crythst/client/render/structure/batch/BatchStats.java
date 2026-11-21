package io.mikaple.crythst.client.render.structure.batch;

/**
 * Immutable per-frame statistics for {@link BatchedStructureRenderer}.
 *
 * <p>The counters are deliberately cheap to read from a debug HUD or a
 * command: the renderer already walks every cached section once per frame for
 * culling, so the extra iteration used to produce this snapshot has the same
 * order of magnitude as the work it describes.</p>
 *
 * @param frame                 monotonically increasing render-frame counter
 * @param cachedBodies          structure bodies with a live section cache
 * @param cachedSections        sections that currently own a vertex cache
 * @param cachedBatches         non-empty section/render-layer vertex buffers
 * @param cachedVertices        vertices resident in all section caches
 * @param submittedBatches      {@code submitCustomGeometry} nodes this frame
 * @param submittedVertices     vertices replayed into those nodes this frame
 * @param culledSections        sections rejected by any culling stage
 * @param culledByDistance      sections rejected by the render-distance cut-off
 * @param culledByFrustum       sections rejected by the conservative frustum hull
 * @param culledByOcclusion     sections rejected by the section occlusion BFS
 * @param enclosedBlocks        full solid blocks skipped by enclosed-block culling
 * @param occlusionCulling      whether the section BFS ran for any body this frame
 * @param pendingSections       sections still waiting for a build budget slot
 * @param overBudgetSections    sections that cannot be cached within the vertex budget
 * @param fallbackBodies        structures drawn as coloured collider cuboids
 * @param fallbackBlocks        individual blocks submitted through the legacy path
 * @param cachedSpecialCells    special-rendered cells retained in section caches
 * @param submittedSpecialBlocks special block renderers invoked this frame
 * @param modelCacheEntries     {@code BlockState} geometries in the palette cache
 * @param buildMillisLastFrame  time spent building section vertex caches
 * @param submitMillisLastFrame time spent culling and submitting this frame
 */
public record BatchStats(
        long frame,
        int cachedBodies,
        int cachedSections,
        int cachedBatches,
        long cachedVertices,
        int submittedBatches,
        long submittedVertices,
        int culledSections,
        int culledByDistance,
        int culledByFrustum,
        int culledByOcclusion,
        int enclosedBlocks,
        boolean occlusionCulling,
        int pendingSections,
        int overBudgetSections,
        int fallbackBodies,
        int fallbackBlocks,
        int cachedSpecialCells,
        int submittedSpecialBlocks,
        int modelCacheEntries,
        double buildMillisLastFrame,
        double submitMillisLastFrame
) {
    public static final BatchStats EMPTY = new BatchStats(
            0L,                 // frame
            0, 0, 0, 0L,        // cached bodies/sections/batches/vertices
            0, 0L,              // submitted batches/vertices
            0,                  // culled sections (total)
            0, 0, 0,            // distance/frustum/occlusion
            0,                  // enclosed blocks
            false,              // occlusion culling active
            0, 0,               // pending / over-budget sections
            0, 0,               // fallback bodies / blocks
            0, 0,               // cached special cells / submitted special blocks
            0,                  // model cache entries
            0.0, 0.0);          // build / submit millis

    /**
     * One-line summary suitable for a HUD or log message.
     */
    public String summary() {
        return "batched structures: "
                + submittedBatches + " batches / " + submittedVertices + " vertices submitted, "
                + cachedBodies + " bodies, " + cachedSections + " sections, "
                + cachedBatches + " cached batches, " + cachedVertices + " cached vertices, "
                + culledSections + " culled ("
                + culledByDistance + " distance, " + culledByFrustum + " frustum, "
                + culledByOcclusion + " occlusion), "
                + enclosedBlocks + " enclosed blocks, "
                + (occlusionCulling ? "occlusion on" : "occlusion off") + ", "
                + pendingSections + " pending, "
                + overBudgetSections + " over-budget, "
                + fallbackBodies + " fallback bodies / " + fallbackBlocks + " fallback blocks, "
                + submittedSpecialBlocks + " special blocks (" + cachedSpecialCells + " cached), "
                + modelCacheEntries + " models, "
                + String.format(java.util.Locale.ROOT, "build %.3f ms, frame %.3f ms",
                buildMillisLastFrame, submitMillisLastFrame);
    }

    /**
     * Compact culling line for {@code /crythst timing}.
     */
    public String cullingSummary() {
        return "culling: " + culledByDistance + " distance / "
                + culledByFrustum + " frustum / " + culledByOcclusion + " occlusion, "
                + enclosedBlocks + " enclosed blocks, "
                + (occlusionCulling ? "occlusion active" : "occlusion idle");
    }
}
