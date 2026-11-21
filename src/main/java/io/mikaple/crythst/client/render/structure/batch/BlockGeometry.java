package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import java.util.List;

/**
 * Pose-independent geometry for one {@code BlockState}.
 *
 * <p>A geometry is split into the three sections of the vanilla block render
 * pipeline. The split happens per <em>quad</em>, using
 * {@code BakedQuad.materialInfo().layer()}, rather than per block. That is the
 * core visual fix over the legacy renderer: a block with one translucent face
 * (glass, ice, waterlogged plants) no longer forces its opaque and cutout faces
 * through the translucent pass, and vice versa.</p>
 *
 * <p>The flat arrays are immutable after construction. They hold block-local
 * coordinates in the range {@code [0, 1]}; a section build adds the block
 * offset and never touches these arrays again.</p>
 */
final class BlockGeometry {
    /**
     * The three render layers used by the moving-block pipeline.
     */
    enum Layer {
        SOLID,
        CUTOUT,
        TRANSLUCENT;

        RenderType renderType() {
            return switch (this) {
                case SOLID -> RenderTypes.solidMovingBlock();
                case CUTOUT -> RenderTypes.cutoutMovingBlock();
                case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            };
        }

        static Layer of(ChunkSectionLayer layer) {
            if (layer == null) {
                return CUTOUT;
            }
            return switch (layer) {
                case SOLID -> SOLID;
                case CUTOUT -> CUTOUT;
                case TRANSLUCENT -> TRANSLUCENT;
            };
        }

        int index() {
            return ordinal();
        }
    }

    static final Layer[] LAYERS = Layer.values();

    /**
     * Flat per-layer quad soup: four vertices per quad, triangle-list order
     * preserved exactly as the baked model produced it.
     *
     * <p>Layout per quad:</p>
     * <ul>
     *   <li>{@code positions}: 12 floats (4 * xyz), block-local {@code [0,1]}</li>
     *   <li>{@code uvs}: 8 floats (4 * uv), already atlas-relative</li>
     *   <li>{@code normals}: 12 floats (4 * xyz)</li>
     *   <li>{@code colors}: 1 ARGB value, shared by the four vertices</li>
     *   <li>{@code lightEmission}: 1 value in {@code [0,15]}</li>
     * </ul>
     */
    static final class Quads {
        static final Quads EMPTY = new Quads(
                new float[0], new float[0], new float[0], new int[0], new int[0], 0);

        final float[] positions;
        final float[] uvs;
        final float[] normals;
        final int[] colors;
        final int[] lightEmission;
        final int quadCount;

        Quads(float[] positions, float[] uvs, float[] normals,
              int[] colors, int[] lightEmission, int quadCount) {
            this.positions = positions;
            this.uvs = uvs;
            this.normals = normals;
            this.colors = colors;
            this.lightEmission = lightEmission;
            this.quadCount = quadCount;
        }

        int vertexCount() {
            return quadCount * 4;
        }

        boolean isEmpty() {
            return quadCount == 0;
        }
    }

    static final BlockGeometry EMPTY = new BlockGeometry(
            List.of(), RenderTypes.cutoutMovingBlock(),
            new Quads[]{Quads.EMPTY, Quads.EMPTY, Quads.EMPTY}, true, 0);

    /** Model parts retained only for the bounded per-block fallback path. */
    final List<BlockStateModelPart> parts;
    /** Render type used by {@code submitBlockModel} when this section is not cached. */
    final RenderType fallbackRenderType;
    final Quads[] quads;
    final boolean empty;
    final int vertexCount;

    BlockGeometry(List<BlockStateModelPart> parts, RenderType fallbackRenderType,
                  Quads[] quads, boolean empty, int vertexCount) {
        this.parts = parts;
        this.fallbackRenderType = fallbackRenderType;
        this.quads = quads;
        this.empty = empty;
        this.vertexCount = vertexCount;
    }

    Quads quads(Layer layer) {
        return quads[layer.index()];
    }
}
