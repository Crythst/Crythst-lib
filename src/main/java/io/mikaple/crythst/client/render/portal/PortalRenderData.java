package io.mikaple.crythst.client.render.portal;

import io.mikaple.crythst.integration.portal.PortalSnapshot;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * One already-extracted destination view for one recursion layer.
 *
 * <p>Everything here is produced during {@code LevelExtractor.extract} and is
 * immutable for the rest of the frame. The render pass only swaps the section
 * list and render state in and out; it never re-extracts the world.</p>
 */
record PortalRenderData(
        PortalSnapshot portal,
        PortalViewCamera view,
        LevelRenderState state,
        List<SectionRenderDispatcher.RenderSection> sections,
        Matrix4f screenViewProjection,
        Vec3 screenCameraPosition,
        PortalMaskGeometry.Mask mask,
        int layer,
        int parentLayer,
        int sectionCount) {

    boolean visible() {
        return mask != null && !mask.isEmpty();
    }
}
