package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.mikaple.crythst.Crythst;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Pipelines used only by the portal destination pass.
 *
 * <p>{@code portal_mask} rasterizes the clipped opening polygon into an R8-like
 * colour attachment. {@code portal_composite} restores the pre-portal frame
 * outside that mask and therefore implements the per-pixel opening without
 * hardware stencil operations (RenderPearl does not expose them).</p>
 */
final class PortalPassRenderTypes {
    static final Identifier MASK_PIPELINE =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_mask");
    static final Identifier COMPOSITE_PIPELINE =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_composite");
    private static final Identifier MASK_SHADER =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/portal_mask");
    private static final Identifier COMPOSITE_FRAGMENT =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/portal_composite");
    private static final Identifier SCREENQUAD =
            Identifier.withDefaultNamespace("core/screenquad");

    private static RenderPipeline mask;
    private static RenderPipeline composite;

    private PortalPassRenderTypes() {
    }

    static RenderPipeline mask() {
        RenderPipeline result = mask;
        if (result == null) {
            synchronized (PortalPassRenderTypes.class) {
                result = mask;
                if (result == null) {
                    result = RenderPipeline.builder()
                            .withLocation(MASK_PIPELINE)
                            .withVertexShader(MASK_SHADER)
                            .withFragmentShader(MASK_SHADER)
                            .withVertexBinding(0, DefaultVertexFormat.POSITION)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withColorTargetState(new ColorTargetState(
                                    Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
                            .withCull(false)
                            .build();
                    mask = result;
                }
            }
        }
        return result;
    }

    static RenderPipeline composite() {
        RenderPipeline result = composite;
        if (result == null) {
            synchronized (PortalPassRenderTypes.class) {
                result = composite;
                if (result == null) {
                    result = RenderPipeline.builder()
                            .withLocation(COMPOSITE_PIPELINE)
                            .withVertexShader(SCREENQUAD)
                            .withFragmentShader(COMPOSITE_FRAGMENT)
                            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withColorTargetState(new ColorTargetState(
                                    Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
                            .withCull(false)
                            .build();
                    composite = result;
                }
            }
        }
        return result;
    }
}
