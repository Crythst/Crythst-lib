package io.mikaple.crythst.client.render.item;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.CrythstRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link RenderType} used by the star effect.
 *
 * <p>In 26.3 the shader is chosen by a {@link RenderPipeline}, while
 * {@link net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo#itemRenderType()}
 * selects the {@link RenderType} for each quad. The flow is therefore:</p>
 * <ol>
 *     <li>Copy every state of vanilla {@link RenderPipelines#ITEM_CUTOUT} (vertex
 *     format, bind groups, depth/blend, ...) and only replace the vertex/fragment
 *     shaders with {@code assets/crythst/shaders/core/star.vsh/.fsh};</li>
 *     <li>Build a new {@link RenderType} from it (Sampler0 = item atlas);</li>
 *     <li>In {@link StarItemModelRenderer}, replace each quad's itemRenderType
 *     with that RenderType.</li>
 * </ol>
 *
 * <p>The whole effect branches per pixel inside one fragment shader: fully
 * transparent pixels become an animated translucent rim, pure-black pixels
 * become a recessed star field with purple walls, everything else samples the
 * original texture.</p>
 *
 * <p>Pipelines need no registration: {@code PipelineCache} compiles them on
 * demand from the shader sources of the current resource packs.</p>
 */
final class StarRenderTypes {

    /** One RenderType per atlas (quads may come from items.png or blocks.png). */
    private static final Map<Identifier, RenderType> STAR_BY_ATLAS = new ConcurrentHashMap<>();
    private static volatile RenderPipeline starPipeline;

    private StarRenderTypes() {
    }

    /** The star-effect render type bound to the given atlas. */
    static RenderType forAtlas(Identifier atlasLocation) {
        return STAR_BY_ATLAS.computeIfAbsent(atlasLocation, atlas ->
                createType(CrythstItemSpecialRenderers.STAR_RENDER_TYPE_NAME, starPipeline(), atlas));
    }

    private static RenderType createType(String name, RenderPipeline pipeline, Identifier atlas) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", atlas)
                .useLightmap()
                .useOverlay()
                .affectsCrumbling()
                .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                .createRenderSetup();
        return CrythstRenderTypes.create(name, setup);
    }

    private static RenderPipeline starPipeline() {
        RenderPipeline result = starPipeline;
        if (result == null) {
            // Model baking happens on worker threads; several items may bake at once.
            synchronized (StarRenderTypes.class) {
                result = starPipeline;
                if (result == null) {
                    result = buildPipeline();
                    starPipeline = result;
                }
            }
        }
        return result;
    }

    private static RenderPipeline buildPipeline() {
        RenderPipeline base = RenderPipelines.ITEM_CUTOUT;
        RenderPipeline.Builder builder = RenderPipeline.builder()
                // Points at our shaders: assets/crythst/shaders/core/star.{vsh,fsh}
                .withVertexShader(CrythstItemSpecialRenderers.STAR_SHADER)
                .withFragmentShader(CrythstItemSpecialRenderers.STAR_SHADER)
                .withLocation(CrythstItemSpecialRenderers.STAR_PIPELINE)
                // Cutout like vanilla item_cutout; the fragment shader decides what
                // happens in transparent regions.
                .withShaderDefine("ALPHA_CUTOUT", 0.1f);

        for (BindGroupLayout layout : base.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        for (int i = 0; i < base.getVertexFormatBindings().size(); i++) {
            builder.withVertexBinding(i, base.getVertexFormatBinding(i));
        }
        for (int i = 0; i < base.getColorTargetStates().size(); i++) {
            // Enable alpha blending: the rim is translucent (opaque pixels keep
            // alpha=1, so the blend result is unchanged).
            builder.withColorTargetState(i, i == 0
                    ? new ColorTargetState(BlendFunction.TRANSLUCENT)
                    : base.getColorTargetStates().get(i));
        }

        return builder
                .withPrimitiveTopology(base.getPrimitiveTopology())
                .withDepthStencilState(base.getDepthStencilState())
                .withPolygonMode(base.getPolygonMode())
                .withCull(base.isCull())
                .withPushConstantSize(base.pushConstantSize())
                .build();
    }
}
