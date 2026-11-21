package io.mikaple.crythst.client.render.structure.batch;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

/**
 * Pure culling helpers shared by the batch renderer, the section cache and
 * tests.
 *
 * <p>Section bounds are the axis-aligned hull of all eight transformed section
 * corners, expanded by a conservative model margin. An affine transform maps a
 * convex box to the convex hull of the transformed corners, so an AABB that
 * contains those corners cannot clip geometry that is inside the box. This is
 * exactly the property the per-section frustum test needs: it may keep a
 * section that is just outside the frustum, but it must never drop one that is
 * still visible.</p>
 *
 * <p>Occlusion culling (the per-body section BFS) can be disabled for A/B
 * testing with {@code -Dcrythst.render.occlusionCulling=false} or at runtime
 * through {@link BatchedStructureRenderer#setOcclusionCullingEnabled(boolean)}.
 * It defaults to enabled.</p>
 */
final class SectionCulling {
    /**
     * Extra section-local metres allowed for a baked model that overhangs its
     * voxel (fences, plants, modded models) or for a special renderer. One
     * full block is deliberately generous; a too-small margin is the one
     * mistake that produces popping.
     */
    static final double MODEL_MARGIN_BLOCKS = 1.0;

    private static final String OCCLUSION_PROPERTY = "crythst.render.occlusionCulling";
    private static final Direction[] DIRECTIONS = Direction.values();

    private static volatile boolean occlusionCulling =
            Boolean.parseBoolean(System.getProperty(OCCLUSION_PROPERTY, "true"));

    private SectionCulling() {
    }

    static boolean occlusionCullingEnabled() {
        return occlusionCulling;
    }

    static void setOcclusionCullingEnabled(boolean enabled) {
        occlusionCulling = enabled;
    }

    /**
     * Returns the world-space AABB of a section-local box after the rigid
     * structure pose is applied, expanded by {@code margin} on every axis.
     *
     * <p>The corner order is irrelevant; all eight combinations are tested.
     * The result is conservative for any rotation and translation.</p>
     */
    static AABB sectionBounds(Transform3d pose,
                              double localMinX, double localMinY, double localMinZ,
                              double localMaxX, double localMaxY, double localMaxZ,
                              double margin) {
        final double minX = localMinX - margin;
        final double minY = localMinY - margin;
        final double minZ = localMinZ - margin;
        final double maxX = localMaxX + margin;
        final double maxY = localMaxY + margin;
        final double maxZ = localMaxZ + margin;

        double boundMinX = Double.POSITIVE_INFINITY;
        double boundMinY = Double.POSITIVE_INFINITY;
        double boundMinZ = Double.POSITIVE_INFINITY;
        double boundMaxX = Double.NEGATIVE_INFINITY;
        double boundMaxY = Double.NEGATIVE_INFINITY;
        double boundMaxZ = Double.NEGATIVE_INFINITY;

        final Vector3d local = new Vector3d();
        final Vector3d world = new Vector3d();
        for (int corner = 0; corner < 8; corner++) {
            local.set(
                    (corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ);
            pose.transformPoint(local, world);
            boundMinX = Math.min(boundMinX, world.x);
            boundMinY = Math.min(boundMinY, world.y);
            boundMinZ = Math.min(boundMinZ, world.z);
            boundMaxX = Math.max(boundMaxX, world.x);
            boundMaxY = Math.max(boundMaxY, world.y);
            boundMaxZ = Math.max(boundMaxZ, world.z);
        }
        return new AABB(boundMinX, boundMinY, boundMinZ,
                boundMaxX, boundMaxY, boundMaxZ);
    }

    /**
     * Sable-style enclosed-block test: a full solid block whose six direct
     * neighbours are also full solid blocks contributes no visible quads.
     *
     * <p>{@link BlockState#isSolidRender()} is stricter than
     * {@code canOcclude()}: it means the block's occlusion shape is the whole
     * unit cube. Stairs, slabs, glass and other shaped or partially transparent
     * blocks therefore never count as an enclosing neighbour, so a top or side
     * face that can still be seen is never skipped.</p>
     */
    static boolean isFullyEnclosed(StructureVoxelView view, BlockState state,
                                   int x, int y, int z) {
        if (view == null || state == null || !state.isSolidRender()) {
            return false;
        }
        for (Direction direction : DIRECTIONS) {
            if (!view.isFullyOpaque(
                    x + direction.getStepX(),
                    y + direction.getStepY(),
                    z + direction.getStepZ())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every cell on one face of a section fully covers that face.
     * Used by the section occlusion BFS: only a completely opaque face stops
     * propagation, so a single hole keeps the neighbouring section visible.
     */
    static boolean isFaceFullyOpaque(StructureVoxelView view, int sectionMinX,
                                     int sectionMinY, int sectionMinZ,
                                     int sizeX, int sizeY, int sizeZ,
                                     Direction direction) {
        final int stepX = direction.getStepX();
        final int stepY = direction.getStepY();
        final int stepZ = direction.getStepZ();
        final int fixedX;
        final int fixedY;
        final int fixedZ;
        if (stepX != 0) {
            fixedX = sectionMinX + (stepX > 0 ? sizeX - 1 : 0);
            fixedY = sectionMinY;
            fixedZ = sectionMinZ;
        } else if (stepY != 0) {
            fixedX = sectionMinX;
            fixedY = sectionMinY + (stepY > 0 ? sizeY - 1 : 0);
            fixedZ = sectionMinZ;
        } else {
            fixedX = sectionMinX;
            fixedY = sectionMinY;
            fixedZ = sectionMinZ + (stepZ > 0 ? sizeZ - 1 : 0);
        }

        if (stepX != 0) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (!view.isFullyOpaque(fixedX, fixedY + y, fixedZ + z)) {
                        return false;
                    }
                }
            }
        } else if (stepY != 0) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (!view.isFullyOpaque(fixedX + x, fixedY, fixedZ + z)) {
                        return false;
                    }
                }
            }
        } else {
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    if (!view.isFullyOpaque(fixedX + x, fixedY + y, fixedZ)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
