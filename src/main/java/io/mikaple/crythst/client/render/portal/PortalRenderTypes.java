package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.mikaple.crythst.Crythst;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.rendertype.CrythstRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * The procedural portal-surface render type.
 *
 * <p>The pipeline is built directly (rather than copying a vanilla pipeline)
 * so its vertex format can carry both UV and colour while the fragment shader
 * generates the animated star surface from {@code GameTime}. No texture is
 * bound; there is therefore no resource reload or dynamic texture involved.</p>
 *
 * <p>If the pipeline cannot be created at all, {@link #available()} returns
 * false and {@link PortalRenderer} draws a plain translucent quad instead.</p>
 */
public final class PortalRenderTypes {
    /** Shader id: {@code crythst:core/portal}. */
    public static final Identifier PORTAL_SHADER =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/portal");
    /** Pipeline location: {@code crythst:portal_surface}. */
    public static final Identifier PORTAL_PIPELINE =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_surface");
    private static final String RENDER_TYPE_NAME = "crythst_portal_surface";

    private static volatile RenderType portalType;
    private static volatile boolean failed;

    private PortalRenderTypes() {
    }

    /** Returns the portal render type, creating it on first use. */
    public static RenderType portal() {
        RenderType result = portalType;
        if (result != null) {
            return result;
        }
        synchronized (PortalRenderTypes.class) {
            result = portalType;
            if (result == null) {
                result = create();
                portalType = result;
            }
            return result;
        }
    }

    /** True when the custom pipeline was created successfully. */
    public static boolean available() {
        if (failed) {
            return false;
        }
        try {
            portal();
            return true;
        } catch (Throwable error) {
            failed = true;
            Crythst.LOGGER.warn("Crythst portal surface shader unavailable; using fallback quads", error);
            return false;
        }
    }

    public static boolean failed() {
        return failed;
    }

    /** Called by the renderer when a submission with the shader throws. */
    public static void markFailed(Throwable error) {
        if (failed) {
            return;
        }
        failed = true;
        Crythst.LOGGER.warn("Crythst portal surface shader failed at render time; "
                + "falling back to coloured quads", error);
    }

    private static RenderType create() {
        final RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(PORTAL_PIPELINE)
                .withVertexShader(PORTAL_SHADER)
                .withFragmentShader(PORTAL_SHADER)
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .withCull(false)
                .build();

        final RenderSetup setup = RenderSetup.builder(pipeline)
                .setOutline(RenderSetup.OutlineProperty.NONE)
                .createRenderSetup();
        return CrythstRenderTypes.create(RENDER_TYPE_NAME, setup);
    }
}
