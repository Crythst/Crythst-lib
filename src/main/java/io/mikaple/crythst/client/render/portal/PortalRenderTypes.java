package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Render types for the portal surface.
 *
 * <p>Two pipelines share the same vertex shader and vertex layout:</p>
 * <ul>
 *     <li>{@code crythst:core/portal} - procedural animated star surface
 *     (fallback, and used for nested portals during a capture pass);</li>
 *     <li>{@code crythst:core/portal_view} - samples the destination scene
 *     captured into an offscreen texture and mapped with screen-space UVs.</li>
 * </ul>
 *
 * <p>Both pipelines are <b>opaque</b> (depth write enabled, no blending). The
 * captured destination scene is already fully composited, and an opaque
 * surface avoids the 26.3 OIT pipeline set requirement for custom translucent
 * render types. The surface is drawn in the solid feature phase and depth-tests
 * correctly against terrain in front of the portal frame.</p>
 *
 * <p>Pipelines are compiled on demand from the current resource pack. No
 * resource reload or dynamic texture reload is triggered.</p>
 */
@Deprecated(forRemoval = true)
public final class PortalRenderTypes {
    /** Procedural surface shader: {@code crythst:core/portal}. */
    public static final Identifier PORTAL_SHADER =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/portal");
    /** Destination-view surface shader: {@code crythst:core/portal_view}. */
    public static final Identifier PORTAL_VIEW_SHADER =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "core/portal_view");
    public static final Identifier PORTAL_PIPELINE =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_surface");
    public static final Identifier PORTAL_VIEW_PIPELINE =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_view_surface");
    public static final Identifier PORTAL_DEBUG_BLIT_PIPELINE =
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "portal_debug_blit");

    private static final String PROCEDURAL_TYPE_NAME = "crythst_portal_surface";

    private static final Map<Identifier, RenderType> VIEW_TYPES = new ConcurrentHashMap<>();
    private static final Map<Identifier, RenderType> DEBUG_BLIT_TYPES = new ConcurrentHashMap<>();

    private static volatile RenderType proceduralType;
    private static volatile boolean failed;

    private PortalRenderTypes() {
    }

    /** The animated procedural surface (fallback / recursion depth limit). */
    public static RenderType procedural() {
        RenderType result = proceduralType;
        if (result != null) {
            return result;
        }
        synchronized (PortalRenderTypes.class) {
            result = proceduralType;
            if (result == null) {
                result = createProcedural();
                proceduralType = result;
            }
            return result;
        }
    }

    /** Compatibility alias for the previous milestone. */
    public static RenderType portal() {
        return procedural();
    }

    /**
     * A render type sampling {@code textureId} as {@code Sampler0}.
     *
     * @param textureId a texture registered with the vanilla texture manager
     *                  (the portal capture manager registers one per portal)
     */
    public static RenderType view(Identifier textureId) {
        return VIEW_TYPES.computeIfAbsent(textureId, PortalRenderTypes::createView);
    }

    /**
     * Diagnostic full-screen blit of the captured texture (no depth test,
     * no blending). Used to prove the texture is bound and sampled correctly
     * independently of the portal quad UVs.
     */
    public static RenderType debugBlit(Identifier textureId) {
        return DEBUG_BLIT_TYPES.computeIfAbsent(textureId, PortalRenderTypes::createDebugBlit);
    }

    /** Drops cached RenderTypes after a target texture view is replaced. */
    public static void invalidate(Identifier textureId) {
        VIEW_TYPES.remove(textureId);
        DEBUG_BLIT_TYPES.remove(textureId);
    }

    /** True when the procedural pipeline could be created. */
    public static boolean available() {
        if (failed) {
            return false;
        }
        try {
            procedural();
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

    private static RenderType createProcedural() {
        final RenderPipeline pipeline = basePipeline(PORTAL_PIPELINE, PORTAL_SHADER, PORTAL_SHADER)
                .build();
        return CrythstRenderTypes.create(PROCEDURAL_TYPE_NAME, setup(pipeline));
    }

    private static RenderType createView(Identifier textureId) {
        final RenderPipeline pipeline = basePipeline(PORTAL_VIEW_PIPELINE, PORTAL_SHADER, PORTAL_VIEW_SHADER)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .build();
        final RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", textureId)
                .setOutline(RenderSetup.OutlineProperty.NONE)
                .createRenderSetup();
        return CrythstRenderTypes.create("crythst_portal_view_" + textureId.getPath().replace('/', '_'),
                setup);
    }

    private static RenderType createDebugBlit(Identifier textureId) {
        final RenderPipeline pipeline = basePipeline(
                PORTAL_DEBUG_BLIT_PIPELINE, PORTAL_SHADER, PORTAL_VIEW_SHADER)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .build();
        final RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", textureId)
                .setOutline(RenderSetup.OutlineProperty.NONE)
                .createRenderSetup();
        return CrythstRenderTypes.create(
                "crythst_portal_debug_blit_" + textureId.getPath().replace('/', '_'), setup);
    }

    private static RenderSetup setup(RenderPipeline pipeline) {
        return RenderSetup.builder(pipeline)
                .setOutline(RenderSetup.OutlineProperty.NONE)
                .createRenderSetup();
    }

    private static RenderPipeline.Builder basePipeline(Identifier location,
                                                       Identifier vertexShader,
                                                       Identifier fragmentShader) {
        return RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(vertexShader)
                .withFragmentShader(fragmentShader)
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                // Triangles so near-plane clipping can emit a 3-5 vertex
                // polygon as a fan without dropping the whole portal.
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                .withCull(false);
    }
}
