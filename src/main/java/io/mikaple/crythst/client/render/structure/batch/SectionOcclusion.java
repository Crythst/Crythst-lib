package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.core.Direction;

import java.util.Arrays;

/**
 * Pure section-graph BFS used for optional per-body occlusion culling.
 *
 * <p>The walk starts at the section containing the camera and only crosses a
 * section face when that face is <em>not</em> completely opaque. A fully opaque
 * wall therefore stops propagation, while a single hole (or an empty section)
 * lets the neighbouring section stay visible. This mirrors sable's
 * {@code SubLevelBatchedTerrainRenderer#sable$scanVisibleSections}, but uses a
 * plain flat array because Crythst body sections already live in a dense
 * grid.</p>
 *
 * <p>If the camera is outside the body, or the start section is outside the
 * grid, every section is returned visible. The caller then falls back to
 * distance/frustum culling only; the BFS must never be used to hide sections
 * from an external viewpoint.</p>
 */
final class SectionOcclusion {
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Answers whether one face of a section is completely opaque. */
    interface FaceOcclusion {
        boolean isFaceOpaque(int sectionIndex, Direction direction);
    }

    private SectionOcclusion() {
    }

    static int index(int sizeX, int sizeZ, int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    static boolean inBounds(int sizeX, int sizeY, int sizeZ, int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0
                && x < sizeX && y < sizeY && z < sizeZ;
    }

    /**
     * Computes the reachable section mask.
     *
     * @param cameraInside whether the camera is inside the body's voxel bounds
     * @return a boolean array indexed by {@link #index}; all {@code true} when
     * the camera is outside so callers cannot accidentally hide the body
     */
    static boolean[] computeVisible(int sizeX, int sizeY, int sizeZ,
                                    int startX, int startY, int startZ,
                                    boolean cameraInside,
                                    FaceOcclusion faces) {
        final int total = sizeX * sizeY * sizeZ;
        final boolean[] visible = new boolean[total];
        if (!cameraInside
                || !inBounds(sizeX, sizeY, sizeZ, startX, startY, startZ)) {
            Arrays.fill(visible, true);
            return visible;
        }

        final int[] queue = new int[total];
        int head = 0;
        int tail = 0;
        final int start = index(sizeX, sizeZ, startX, startY, startZ);
        visible[start] = true;
        queue[tail++] = start;

        while (head < tail) {
            final int node = queue[head++];
            final int x = node % sizeX;
            final int z = (node / sizeX) % sizeZ;
            final int y = node / (sizeX * sizeZ);

            for (Direction direction : DIRECTIONS) {
                final int nextX = x + direction.getStepX();
                final int nextY = y + direction.getStepY();
                final int nextZ = z + direction.getStepZ();
                if (!inBounds(sizeX, sizeY, sizeZ, nextX, nextY, nextZ)) {
                    continue;
                }
                if (faces.isFaceOpaque(node, direction)) {
                    continue;
                }
                final int next = index(sizeX, sizeZ, nextX, nextY, nextZ);
                if (!visible[next]) {
                    visible[next] = true;
                    queue[tail++] = next;
                }
            }
        }
        return visible;
    }
}
