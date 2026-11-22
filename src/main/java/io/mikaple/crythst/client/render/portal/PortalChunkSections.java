package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated chunk-section GPU data for the portal destination pass.
 *
 * <p>{@code LevelRenderer.prepareChunkRenders} writes chunk-section records into
 * the shared {@code DynamicGpuData}. That storage is either the mapped or the
 * instanced variant, and toggling between the two closes/recreates it at
 * capacity 2 every frame. It is also shared with the main pass, so a portal
 * prepare can force a mid-frame resize. This class owns one mapped storage for
 * chunk-section records and one for terrain transforms, pre-sized so the normal
 * portal budget never logs or reallocates. The data is written once per frame
 * during extraction, before the framegraph executes.</p>
 */
final class PortalChunkSections implements AutoCloseable {
    /**
     * Four layers (two roots plus one child each) times
     * {@code PortalSectionVisibility.MAX_VISIBLE_SECTIONS} (2048). Rounded to a
     * power of two by the storage; no resize logs at this budget.
     */
    static final int CHUNK_SECTION_CAPACITY = 16_384;
    private static final int TERRAIN_CAPACITY = 8;

    private final DynamicGpuDataStorageMapped<DynamicGpuData.ChunkSectionInfo> chunkSections =
            new DynamicGpuDataStorageMapped<>("Crythst portal Chunk Sections UBO",
                    DynamicGpuData.CHUNK_SECTION_UBO_SIZE, 128, CHUNK_SECTION_CAPACITY);
    private final DynamicGpuDataStorageMapped<DynamicGpuData.TerrainTransform> terrainTransforms =
            new DynamicGpuDataStorageMapped<>("Crythst portal Terrain UBO",
                    DynamicGpuData.TERRAIN_TRANSFORM_UBO_SIZE, 128, TERRAIN_CAPACITY);

    PortalChunkSections() {
        RenderSystem.assertOnRenderThread();
    }

    /** Rotates to a fresh ring slot; call once per main-camera extraction. */
    void beginFrame() {
        this.chunkSections.endFrame();
        this.terrainTransforms.endFrame();
    }

    ChunkSectionsToRender prepare(SectionRenderDispatcher dispatcher,
                                  Matrix4fc modelView,
                                  List<SectionRenderDispatcher.RenderSection> sections,
                                  int atlasWidth, int atlasHeight,
                                  OptionsRenderState options) {
        final Map<ChunkSectionLayer, List<DrawGroup>> drawGroups = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawGroups.put(layer, new ArrayList<>());
        }
        final List<DynamicGpuData.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        final Map<Integer, DrawGroup> drawGroupCache = new HashMap<>();
        int largestIndexCount = 0;

        if (dispatcher != null) {
            dispatcher.lock();
            final long fadeDuration = Util.toMillis(options.chunkSectionFadeInTime);
            final long now = Util.getMillis();
            Integer lastTransparentGroupHash = null;
            try {
                for (SectionRenderDispatcher.RenderSection section : sections) {
                    final SectionMesh sectionMesh = section.getSectionMesh();
                    final BlockPos renderOffset = section.getRenderOrigin();
                    int sectionInfoIndex = -1;
                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        final SectionMesh.SectionDraw draw = sectionMesh.getSectionDraw(layer);
                        final SectionRenderDispatcher.RenderSectionBufferSlice slice =
                                dispatcher.getRenderSectionSlice(sectionMesh, layer);
                        if (slice == null || draw == null
                                || (draw.hasCustomIndexBuffer() && slice.indexBuffer() == null)) {
                            continue;
                        }
                        if (sectionInfoIndex == -1) {
                            sectionInfoIndex = sectionInfos.size();
                            sectionInfos.add(new DynamicGpuData.ChunkSectionInfo(
                                    renderOffset.getX(), renderOffset.getY(), renderOffset.getZ(),
                                    section.getVisibility(now, fadeDuration)));
                        }

                        final GpuBuffer vertexBuffer = slice.vertexBuffer();
                        int combinedHash = 31 * 173 + vertexBuffer.hashCode();
                        final int firstIndex;
                        final IndexType indexType;
                        final GpuBuffer indexBuffer;
                        if (!draw.hasCustomIndexBuffer()) {
                            largestIndexCount = Math.max(largestIndexCount, draw.indexCount());
                            firstIndex = 0;
                            indexType = null;
                            indexBuffer = null;
                        } else {
                            indexBuffer = slice.indexBuffer();
                            indexType = draw.indexType();
                            combinedHash = 31 * combinedHash + indexBuffer.hashCode();
                            combinedHash = 31 * combinedHash + indexType.hashCode();
                            firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
                        }
                        final int baseVertex = (int) (slice.vertexBufferOffset()
                                / layer.pipeline(false).getVertexFormatBinding(0).getVertexSize());

                        DrawGroup drawGroup = null;
                        if (layer.translucent()) {
                            if (lastTransparentGroupHash != null
                                    && combinedHash == lastTransparentGroupHash) {
                                drawGroup = drawGroups.get(layer).getLast();
                            }
                            lastTransparentGroupHash = combinedHash;
                        } else {
                            drawGroup = drawGroupCache.get(combinedHash);
                        }
                        if (drawGroup == null) {
                            drawGroup = new DrawGroup(vertexBuffer.slice(),
                                    indexBuffer == null ? null : indexBuffer.slice(),
                                    indexType, new ArrayList<>());
                            drawGroupCache.put(combinedHash, drawGroup);
                            drawGroups.get(layer).add(drawGroup);
                        }
                        drawGroup.draws().add(new DynamicGpuData.IndexedDraw(
                                draw.indexCount(), 1, firstIndex, baseVertex, sectionInfoIndex));
                    }
                }
            } finally {
                dispatcher.unlock();
            }
        }

        final Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            final List<RenderPass.Draw<GpuBufferSlice[]>> draws = new ArrayList<>();
            final List<DrawGroup> groups = new ArrayList<>(drawGroups.get(layer));
            if (layer.translucent()) {
                Collections.reverse(groups);
            }
            for (DrawGroup group : groups) {
                final List<DynamicGpuData.IndexedDraw> indexed = new ArrayList<>(group.draws());
                if (layer.translucent()) {
                    Collections.reverse(indexed);
                }
                for (DynamicGpuData.IndexedDraw draw : indexed) {
                    draws.add(new RenderPass.Draw<>(
                            0,
                            group.vertexBuffer().buffer(),
                            group.indexBuffer() == null ? null : group.indexBuffer().buffer(),
                            group.indexType(),
                            draw.firstIndex(),
                            draw.indexCount(),
                            draw.baseVertex(),
                            (sectionUbos, uploader) ->
                                    uploader.setUniform("ChunkSection", sectionUbos[draw.baseInstance()])));
                }
            }
            drawsPerLayer.put(layer, draws);
        }

        if (largestIndexCount != 0) {
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
                    .requestIndexCount(largestIndexCount);
        }

        final GpuBufferSlice terrainTransform = this.terrainTransforms.writeData(
                new DynamicGpuData.TerrainTransform(modelView, atlasWidth, atlasHeight));
        final GpuBufferSlice[] sectionInfoSlices = this.chunkSections.writeData(
                sectionInfos.toArray(new DynamicGpuData.ChunkSectionInfo[0]));
        return new ChunkSectionsToRender.DrawSeparate(
                terrainTransform, drawsPerLayer, largestIndexCount, sectionInfoSlices);
    }

    @Override
    public void close() {
        this.chunkSections.close();
        this.terrainTransforms.close();
    }

    private record DrawGroup(GpuBufferSlice vertexBuffer,
                             @Nullable GpuBufferSlice indexBuffer,
                             @Nullable IndexType indexType,
                             List<DynamicGpuData.IndexedDraw> draws) {
    }
}
