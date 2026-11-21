package io.mikaple.crythst.client.render.portal;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Bounded replacement for {@code SectionOcclusionGraph.addSectionsInFrustum}
 * during portal capture.
 *
 * <p>The vanilla method walks an octree and, more importantly, calls
 * {@code Frustum.offsetToFullyIncludeCameraCube}. That loop has no iteration
 * cap and hangs the render thread for degenerate camera state. Portal cameras
 * are built from untrusted-ish runtime state (interpolated camera, link
 * transform), so capture uses this simple, explicitly budgeted scan instead.</p>
 *
 * <p>The scan visits section coordinates in expanding horizontal rings around
 * the portal camera, so if the budget is ever exhausted the skipped sections
 * are always the farthest ones.</p>
 */
final class PortalSectionSelection {
    /** Never look further than this many sections away, regardless of view distance. */
    static final int MAX_SCAN_RADIUS_SECTIONS = 24;
    /** Hard cap on section lookups per capture. */
    static final int MAX_SCANNED_SECTIONS = 65_536;
    /** Matches {@code SectionRenderDispatcher.NEARBY_SECTION_DISTANCE_IN_BLOCKS}. */
    private static final double NEARBY_DISTANCE_IN_BLOCKS = 32.0;

    private PortalSectionSelection() {
    }

    /**
     * Fills {@code visible} and {@code nearby} for the given portal camera.
     *
     * @return the number of visible sections added, or 0 when the destination
     *         neighbourhood is not loaded / has no sections
     */
    static int collect(ClientLevel level, ViewArea viewArea, Frustum frustum,
                       SectionPos center, List<RenderSection> visible,
                       List<RenderSection> nearby) {
        if (level == null || viewArea == null || frustum == null || center == null
                || visible == null || nearby == null) {
            return 0;
        }
        final int radius = Math.min(viewArea.getViewDistance(), MAX_SCAN_RADIUS_SECTIONS);
        if (radius < 0) {
            return 0;
        }
        final int minSectionY = viewArea.minSectionY();
        final int maxSectionY = viewArea.maxSectionY();
        final BlockPos cameraSectionCenter = center.center();
        final int centerX = center.x();
        final int centerY = center.y();
        final int centerZ = center.z();
        int budget = MAX_SCANNED_SECTIONS;
        int added = 0;

        // Expanding Chebyshev rings keep the nearest columns first even if the
        // budget is exhausted before the outermost ring is reached.
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    final int sectionX = centerX + dx;
                    final int sectionZ = centerZ + dz;
                    if (!level.getChunkSource().hasChunk(sectionX, sectionZ)) {
                        // Repositioning ViewArea assigns a node to every storage
                        // slot, including unloaded chunks. Filtering here avoids
                        // capturing an empty/sky-only destination.
                        continue;
                    }
                    final int minY = Math.max(minSectionY, centerY - radius);
                    final int maxY = Math.min(maxSectionY, centerY + radius);
                    for (int sectionY = minY; sectionY <= maxY; sectionY++) {
                        if (budget <= 0) {
                            return added;
                        }
                        budget--;
                        final RenderSection section = viewArea.getRenderSectionAt(new BlockPos(
                                SectionPos.sectionToBlockCoord(sectionX),
                                SectionPos.sectionToBlockCoord(sectionY),
                                SectionPos.sectionToBlockCoord(sectionZ)));
                        if (section == null) {
                            continue;
                        }
                        final AABB bounds = section.getBoundingBox();
                        if (bounds == null || !frustum.isVisible(bounds)) {
                            continue;
                        }
                        visible.add(section);
                        if (isNearby(cameraSectionCenter, bounds)) {
                            nearby.add(section);
                        }
                        added++;
                    }
                }
            }
        }
        return added;
    }

    private static boolean isNearby(BlockPos cameraSectionCenter, AABB bounds) {
        return isClose(cameraSectionCenter.getX(), bounds.minX, bounds.maxX)
                && isClose(cameraSectionCenter.getY(), bounds.minY, bounds.maxY)
                && isClose(cameraSectionCenter.getZ(), bounds.minZ, bounds.maxZ);
    }

    private static boolean isClose(int coordinate, double min, double max) {
        return coordinate > min - NEARBY_DISTANCE_IN_BLOCKS
                && coordinate < max + NEARBY_DISTANCE_IN_BLOCKS;
    }
}
