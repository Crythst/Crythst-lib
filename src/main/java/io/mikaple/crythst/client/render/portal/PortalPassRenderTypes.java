package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
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
    private static final Identifier DEPTH_CLEAR_SHADER =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/portal_depth_clear");
    private static final Identifier COMPOSITE_FRAGMENT =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/portal_composite");
    private static final Identifier SCREENQUAD =
            Identifier.withDefaultNamespace("core/screenquad");

    private static RenderPipeline mask;
    private static RenderPipeline maskDepth;
    private static RenderPipeline depthClear;
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
                            // Read-only depth test against the pre-layer depth
                            // snapshot. Reverse-Z: nearer fragments have larger
                            // depth, so an occluder in front fails the test.
                            .withDepthStencilState(new DepthStencilState(
                                    CompareOp.GREATER_THAN_OR_EQUAL, false))
                            .withCull(false)
                            .build();
                    mask = result;
                }
            }
        }
        return result;
    }

    /**
     * Writes the opening polygon's own reverse-Z depth into the main depth
     * attachment, so entry-side geometry drawn by later passes is clipped at
     * the portal plane instead of at the destination world's depth.
     *
     * <p>Same vertex data as {@link #mask()} (the polygon carries the plane
     * depth in {@code Position.z}) and the same depth test, but depth writes are
     * enabled and colour writes are masked off. The test keeps pixels where an
     * entry-side occluder sits in front of the plane untouched.</p>
     */
    static RenderPipeline maskDepth() {
        RenderPipeline result = maskDepth;
        if (result == null) {
            synchronized (PortalPassRenderTypes.class) {
                result = maskDepth;
                if (result == null) {
                    result = RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath(
                                    Crythst.MOD_ID, "portal_mask_depth"))
                            .withVertexShader(MASK_SHADER)
                            .withFragmentShader(MASK_SHADER)
                            .withVertexBinding(0, DefaultVertexFormat.POSITION)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withColorTargetState(new ColorTargetState(
                                    Optional.empty(), GpuFormat.RGBA8_UNORM,
                                    ColorTargetState.WRITE_NONE))
                            .withDepthStencilState(new DepthStencilState(
                                    CompareOp.GREATER_THAN_OR_EQUAL, true))
                            .withCull(false)
                            .build();
                    maskDepth = result;
                }
            }
        }
        return result;
    }

    /** Writes reverse-Z far depth through the verified opening polygon. */
    static RenderPipeline depthClear() {
        RenderPipeline result = depthClear;
        if (result == null) {
            synchronized (PortalPassRenderTypes.class) {
                result = depthClear;
                if (result == null) {
                    result = RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath(
                                    Crythst.MOD_ID, "portal_depth_clear"))
                            .withVertexShader(SCREENQUAD)
                            .withFragmentShader(DEPTH_CLEAR_SHADER)
                            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withColorTargetState(new ColorTargetState(
                                    Optional.empty(), GpuFormat.RGBA8_UNORM,
                                    ColorTargetState.WRITE_NONE))
                            .withDepthStencilState(new DepthStencilState(
                                    CompareOp.ALWAYS_PASS, true))
                            .withCull(false)
                            .build();
                    depthClear = result;
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
