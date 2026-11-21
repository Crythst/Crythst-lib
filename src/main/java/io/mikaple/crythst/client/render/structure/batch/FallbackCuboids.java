package io.mikaple.crythst.client.render.structure.batch;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Bounded debug-cuboid fallback for structures that have no block snapshot.
 *
 * <p>Snapshots are singleplayer-first and are also skipped for oversized
 * volumes. The legacy coloured view is still useful there, so the batched
 * renderer keeps it as a last-resort fallback. This path is never used together
 * with section batches for the same body: the renderer decides once per frame
 * whether a body has a snapshot.</p>
 */
final class FallbackCuboids {
    private static final int COLOR_STONE = 0x9C9C9C;
    private static final int COLOR_DIRT = 0x8A6A48;
    private static final int COLOR_WOOD = 0xA47B4F;
    private static final int COLOR_METAL = 0xB9BEC6;
    private static final int COLOR_ICE = 0x8FD0EF;
    private static final int COLOR_RUBBER = 0x4C4C4C;
    private static final int COLOR_DEFAULT = 0xB0A18C;

    private static final PoseStack POSE = new PoseStack();

    private FallbackCuboids() {
    }

    /**
     * Submits up to {@code remainingBoxes} oriented cuboids for one structure.
     *
     * @return the number of boxes emitted
     */
    static int submit(StructureRenderState state, CameraRenderState camera,
                      SubmitNodeCollector collector, int remainingBoxes) {
        final List<VoxelBox> boxes = state.collider().boxes();
        if (boxes.isEmpty() || remainingBoxes <= 0) {
            return 0;
        }
        final int limit = Math.min(boxes.size(), remainingBoxes);
        final Transform3d transform = state.pose();
        final Quaternionf rotation = new Quaternionf(
                (float) transform.rotation().x(), (float) transform.rotation().y(),
                (float) transform.rotation().z(), (float) transform.rotation().w()).normalize();
        final double scale = state.blockScale();

        POSE.setIdentity();
        POSE.translate(
                (float) (transform.position().x - camera.pos.x),
                (float) (transform.position().y - camera.pos.y),
                (float) (transform.position().z - camera.pos.z));
        POSE.rotate(rotation);

        collector.submitCustomGeometry(POSE, RenderTypes.debugFilledBox(),
                (pose, consumer) -> {
                    for (int i = 0; i < limit; i++) {
                        final VoxelBox box = boxes.get(i);
                        final int color = colorFor(box.material());
                        final float red = ((color >> 16) & 0xFF) / 255.0f;
                        final float green = ((color >> 8) & 0xFF) / 255.0f;
                        final float blue = (color & 0xFF) / 255.0f;

                        final float x0 = (float) (box.minX() * scale);
                        final float y0 = (float) (box.minY() * scale);
                        final float z0 = (float) (box.minZ() * scale);
                        final float x1 = (float) ((box.minX() + box.sizeX()) * scale);
                        final float y1 = (float) ((box.minY() + box.sizeY()) * scale);
                        final float z1 = (float) ((box.minZ() + box.sizeZ()) * scale);

                        quad(pose, consumer, red, green, blue, 0.80f,
                                x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                        quad(pose, consumer, red, green, blue, 0.68f,
                                x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
                        quad(pose, consumer, red, green, blue, 1.00f,
                                x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                        quad(pose, consumer, red, green, blue, 0.55f,
                                x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
                        quad(pose, consumer, red, green, blue, 0.74f,
                                x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
                        quad(pose, consumer, red, green, blue, 0.62f,
                                x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0);
                    }
                });
        return limit;
    }

    private static void quad(PoseStack.Pose pose,
                             com.mojang.blaze3d.vertex.VertexConsumer consumer,
                             float red, float green, float blue, float shade,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3) {
        final float r = red * shade;
        final float g = green * shade;
        final float b = blue * shade;
        consumer.addVertex(pose, x0, y0, z0).setColor(r, g, b, 1.0f);
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, 1.0f);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, 1.0f);
        consumer.addVertex(pose, x3, y3, z3).setColor(r, g, b, 1.0f);
    }

    private static int colorFor(PhysicsMaterial material) {
        if (material == null) {
            return COLOR_DEFAULT;
        }
        if (material.equals(PhysicsMaterial.STONE)) {
            return COLOR_STONE;
        }
        if (material.equals(PhysicsMaterial.DIRT)) {
            return COLOR_DIRT;
        }
        if (material.equals(PhysicsMaterial.WOOD)) {
            return COLOR_WOOD;
        }
        if (material.equals(PhysicsMaterial.METAL)) {
            return COLOR_METAL;
        }
        if (material.equals(PhysicsMaterial.ICE)) {
            return COLOR_ICE;
        }
        if (material.equals(PhysicsMaterial.RUBBER)) {
            return COLOR_RUBBER;
        }
        return COLOR_DEFAULT;
    }
}
