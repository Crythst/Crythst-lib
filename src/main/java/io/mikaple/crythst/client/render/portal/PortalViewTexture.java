package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * A {@link AbstractTexture} facade over a portal capture {@link RenderTarget}.
 *
 * <p>The texture manager resolves {@code Sampler0} from this object on every
 * {@code PreparedRenderType} build, so swapping the view here immediately
 * changes what a portal RenderType samples without a resource reload. The
 * render target owns the GPU resources; this wrapper never closes them.</p>
 */
public final class PortalViewTexture extends AbstractTexture {
    private final String label;

    PortalViewTexture(String label, RenderTarget target, GpuSampler sampler) {
        this.label = label;
        setTarget(target, sampler);
    }

    void setTarget(RenderTarget target, GpuSampler sampler) {
        texture = target.getColorTexture();
        textureView = target.getColorTextureView();
        this.sampler = sampler;
    }

    String label() {
        return label;
    }

    /**
     * The render target owns these resources. Do not let texture-manager
     * shutdown/reload close them behind the capture manager's back.
     */
    @Override
    public void close() {
        // Intentionally empty.
    }

    @Override
    protected void releaseTextures() {
        // Intentionally empty.
    }
}
