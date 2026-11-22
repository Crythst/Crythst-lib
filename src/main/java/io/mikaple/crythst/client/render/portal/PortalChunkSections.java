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

import java.util.ArrayList;
import java.util.EnumMap;
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
        final Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawsPerLayer.put(layer, new ArrayList<>());
        }
        final List<DynamicGpuData.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        int largestIndexCount = 0;

        if (dispatcher != null) {
            dispatcher.lock();
            final long fadeDuration = Util.toMillis(options.chunkSectionFadeInTime);
            final long now = Util.getMillis();
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
                            firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
                        }
                        final int baseVertex = (int) (slice.vertexBufferOffset()
                                / layer.pipeline(false).getVertexFormatBinding(0).getVertexSize());

                        final int uniformIndex = sectionInfoIndex;
                        drawsPerLayer.get(layer).add(new RenderPass.Draw<>(
                                0, vertexBuffer, indexBuffer, indexType,
                                firstIndex, draw.indexCount(), baseVertex,
                                (sectionUbos, uploader) ->
                                        uploader.setUniform("ChunkSection", sectionUbos[uniformIndex])));
                    }
                }
            } finally {
                dispatcher.unlock();
            }
        }
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            if (layer.translucent()) {
                drawsPerLayer.put(layer, drawsPerLayer.get(layer).reversed());
            }
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
}
