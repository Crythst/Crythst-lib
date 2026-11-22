package io.mikaple.crythst.mixin.client;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Bounds vanilla's {@code offsetToFullyIncludeCameraCube} loop.
 *
 * <p>The vanilla loop has no iteration cap. If a camera/frustum pair is
 * degenerate (non-finite position, zero/NaN projection, tiny FOV, or state
 * left over from a portal pass), it spins forever and freezes the render
 * thread. This reimplementation keeps the same algorithm but stops after a
 * bounded number of steps, leaving the frustum usable for culling.</p>
 */
@Mixin(Frustum.class)
public abstract class FrustumOffsetBoundedMixin {
    /** Distance moved along the view vector for each vanilla offset iteration. */
    private static final double STEP = 4.0;
    private static final int MAX_OFFSET_ITERATIONS = 256;

    @Shadow
    private FrustumIntersection intersection;
    @Shadow
    private Vector4f viewVector;
    @Shadow
    private double camX;
    @Shadow
    private double camY;
    @Shadow
    private double camZ;

    /**
     * @author Crythst
     * @reason Bound the otherwise unbounded frustum-offset loop.
     */
    @Overwrite
    public Frustum offsetToFullyIncludeCameraCube(int cubeSize) {
        if (cubeSize <= 0 || this.intersection == null || this.viewVector == null) {
            return (Frustum) (Object) this;
        }
        final double originalX = this.camX;
        final double originalY = this.camY;
        final double originalZ = this.camZ;
        // Offset the frustum around the cube containing the original camera.
        // Recomputing the cube as the frustum moves makes it chase the offset.
        final double minX = Math.floor(originalX / cubeSize) * cubeSize;
        final double minY = Math.floor(originalY / cubeSize) * cubeSize;
        final double minZ = Math.floor(originalZ / cubeSize) * cubeSize;
        final double maxX = Math.ceil(originalX / cubeSize) * cubeSize;
        final double maxY = Math.ceil(originalY / cubeSize) * cubeSize;
        final double maxZ = Math.ceil(originalZ / cubeSize) * cubeSize;
        for (int iteration = 0; iteration < MAX_OFFSET_ITERATIONS; iteration++) {
            final int result = this.intersection.intersectAab(
                    (float) (minX - this.camX),
                    (float) (minY - this.camY),
                    (float) (minZ - this.camZ),
                    (float) (maxX - this.camX),
                    (float) (maxY - this.camY),
                    (float) (maxZ - this.camZ));
            if (result == FrustumIntersection.INSIDE) {
                return (Frustum) (Object) this;
            }
            this.camX -= this.viewVector.x * STEP;
            this.camY -= this.viewVector.y * STEP;
            this.camZ -= this.viewVector.z * STEP;
        }
        this.camX = originalX;
        this.camY = originalY;
        this.camZ = originalZ;
        return (Frustum) (Object) this;
    }
}
