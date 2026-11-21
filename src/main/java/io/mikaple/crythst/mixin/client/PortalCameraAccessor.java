package io.mikaple.crythst.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Narrow access to the vanilla camera pose for the portal second-pass
 * extraction.
 *
 * <p>{@code Camera} keeps its position, rotation and frustums private or
 * {@code protected} and has no public setters. The portal capture needs to
 * temporarily point the <em>real</em> camera at the destination so that
 * {@code LevelExtractor.extract} can rebuild entity, block-entity, sky and
 * particle render state for that camera. Using these accessors keeps the
 * mutation local to the capture and lets the caller restore the exact
 * previous state afterwards.</p>
 */
@Mixin(Camera.class)
public interface PortalCameraAccessor {
    @Invoker("setPosition")
    void crythst$setPosition(Vec3 position);

    @Invoker("setRotation")
    void crythst$setRotation(float yRot, float xRot);

    @Accessor("cullFrustum")
    void crythst$setCullFrustum(Frustum frustum);

    @Accessor("capturedFrustum")
    void crythst$setCapturedFrustum(Frustum frustum);
}
