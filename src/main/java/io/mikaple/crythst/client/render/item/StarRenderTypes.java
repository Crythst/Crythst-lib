package io.mikaple.crythst.client.render.item;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.CrythstRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link RenderType}s used by the star mask overlay.
 *
 * <p>Two overlay types exist because vanilla item rendering splits submissions
 * by {@link RenderType#hasBlending()}:</p>
 * <ul>
 *     <li>{@code crythst_star} - opaque, used for cutout quads. It joins the
 *     solid feature phase and is therefore drawn after the real solid model.</li>
 *     <li>{@code crythst_star_translucent} - blended, used for translucent
 *     quads. It joins the translucent phase, with an {@link OitPipelineSet} so
 *     improved transparency (OIT) works too.</li>
 * </ul>
 *
 * <p>Both copy the bind groups, vertex format, primitive topology and culling
 * of {@link RenderPipelines#ITEM_CUTOUT} / {@link RenderPipelines#ITEM_TRANSLUCENT},
 * then replace the shaders with {@code assets/crythst/shaders/core/star.vsh/.fsh}
 * and force {@code writeDepth = false} with a reversed-Z
 * {@link CompareOp#GREATER_THAN_OR_EQUAL} test. The second pass therefore
 * decorates only pixels where the real model is already the front-most
 * surface; it can neither z-fight with the model nor write depth that would
 * affect later geometry.</p>
 *
 * <p>Pipelines need no registration: {@code PipelineCache} compiles them on
 * demand from the shader sources of the current resource packs.</p>
 */
final class StarRenderTypes {

    private static final String STAR_RENDER_TYPE_NAME = "crythst_star";
    private static final String STAR_TRANSLUCENT_RENDER_TYPE_NAME = "crythst_star_translucent";

    private static final Identifier STAR_TRANSLUCENT_PIPELINE =
            Identifier.fromNamespaceAndPath(CrythstItemSpecialRenderers.STAR_PIPELINE.getNamespace(),
                    "star_item_translucent");

    /** One RenderType per atlas and per blend mode (items.png or blocks.png). */
    private static final Map<AtlasKey, RenderType> STAR_BY_ATLAS = new ConcurrentHashMap<>();

    private static volatile RenderPipeline opaquePipeline;
    private static volatile RenderPipeline translucentPipeline;
    private static volatile OitPipelineSet translucentOitPipelines;

    private StarRenderTypes() {
    }

    /** The star overlay render type bound to the given atlas and blend mode. */
    static RenderType forAtlas(Identifier atlasLocation, boolean translucent) {
        AtlasKey key = new AtlasKey(atlasLocation, translucent);
        return STAR_BY_ATLAS.computeIfAbsent(key, ignored -> translucent
                ? createType(STAR_TRANSLUCENT_RENDER_TYPE_NAME, translucentPipeline(),
                atlasLocation, translucentOitPipelines())
                : createType(STAR_RENDER_TYPE_NAME, opaquePipeline(), atlasLocation, null));
    }

    private static RenderType createType(String name, RenderPipeline pipeline, Identifier atlas,
                                         OitPipelineSet oitPipelines) {
        RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", atlas)
                .useLightmap()
                .useOverlay()
                .setOutline(RenderSetup.OutlineProperty.NONE);
        if (oitPipelines != null) {
            setup.setOitPipelines(oitPipelines).sortOnUpload();
        }
        return CrythstRenderTypes.create(name, setup.createRenderSetup());
    }

    private static RenderPipeline opaquePipeline() {
        RenderPipeline result = opaquePipeline;
        if (result == null) {
            synchronized (StarRenderTypes.class) {
                result = opaquePipeline;
                if (result == null) {
                    result = buildPipeline(RenderPipelines.ITEM_CUTOUT,
                            CrythstItemSpecialRenderers.STAR_PIPELINE);
                    opaquePipeline = result;
                }
            }
        }
        return result;
    }

    private static RenderPipeline translucentPipeline() {
        RenderPipeline result = translucentPipeline;
        if (result == null) {
            synchronized (StarRenderTypes.class) {
                result = translucentPipeline;
                if (result == null) {
                    result = buildPipeline(RenderPipelines.ITEM_TRANSLUCENT,
                            STAR_TRANSLUCENT_PIPELINE);
                    translucentPipeline = result;
                }
            }
        }
        return result;
    }

    private static OitPipelineSet translucentOitPipelines() {
        OitPipelineSet result = translucentOitPipelines;
        if (result == null) {
            synchronized (StarRenderTypes.class) {
                result = translucentOitPipelines;
                if (result == null) {
                    result = buildOitPipelines();
                    translucentOitPipelines = result;
                }
            }
        }
        return result;
    }

    private static RenderPipeline buildPipeline(RenderPipeline base, Identifier location) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                // Points at our shaders: assets/crythst/shaders/core/star.{vsh,fsh}
                .withVertexShader(CrythstItemSpecialRenderers.STAR_SHADER)
                .withFragmentShader(CrythstItemSpecialRenderers.STAR_SHADER)
                .withLocation(location)
                // The overlay only draws effect-mask texels; everything else is
                // discarded instead of sampling the real texture.
                .withShaderDefine("STAR_MASK_ONLY");

        // Preserve the vanilla item pipeline layout (uniforms, vertex format,
        // colour target format/blending). Only depth behaviour and shaders
        // differ.
        for (BindGroupLayout layout : base.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        for (int i = 0; i < base.getVertexFormatBindings().size(); i++) {
            builder.withVertexBinding(i, base.getVertexFormatBinding(i));
        }
        for (int i = 0; i < base.getColorTargetStates().size(); i++) {
            builder.withColorTargetState(i, base.getColorTargetStates().get(i));
        }

        return builder
                .withPrimitiveTopology(base.getPrimitiveTopology())
                // The real pass owns depth. The overlay tests against it, but
                // must never write depth: later geometry (including translucent
                // quads and other items) must not be occluded by the star pass.
                .withDepthStencilState(new DepthStencilState(
                        depthCompare(StarRenderPlan.STAR_DEPTH_TEST),
                        StarRenderPlan.STAR_WRITES_DEPTH))
                .withPolygonMode(base.getPolygonMode())
                .withCull(base.isCull())
                .withPushConstantSize(base.pushConstantSize())
                .build();
    }

    private static CompareOp depthCompare(StarRenderPlan.DepthTest depthTest) {
        return switch (depthTest) {
            case GREATER_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
        };
    }

    /**
     * OIT variants for translucent star quads. The shader already contains the
     * {@code OIT_ALPHA_ONLY}/{@code OIT_ACCUMULATE} branches; the set lets the
     * same RenderType participate in improved transparency instead of
     * crashing with "does not have OIT pipelines set up".
     */
    private static OitPipelineSet buildOitPipelines() {
        RenderPipeline.Snippet base = RenderPipeline.builder(RenderPipelines.OIT_ITEM_SNIPPET)
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withVertexShader(CrythstItemSpecialRenderers.STAR_SHADER)
                .withFragmentShader(CrythstItemSpecialRenderers.STAR_SHADER)
                .withShaderDefine("STAR_MASK_ONLY")
                .buildSnippet();

        return OitPipelineSet.builder("star_item", RenderPipeline.builder(base))
                .withAccumulateModifier(accumulate -> accumulate
                        .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                        .withBindGroupLayout(BindGroupLayouts.SAMPLER2))
                .build();
    }

    private record AtlasKey(Identifier atlas, boolean translucent) {
    }
}
