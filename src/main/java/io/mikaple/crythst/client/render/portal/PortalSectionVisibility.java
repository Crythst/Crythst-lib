package io.mikaple.crythst.client.render.portal;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Bounded portal-view section selection and compile scheduling.
 *
 * <p>The selection never moves the shared {@link ViewArea}; it queries the
 * existing main-camera storage and skips sections outside it. Moving the
 * rotating storage resets section meshes in 26.3 and produced blank views plus
 * main-world stutter.</p>
 */
final class PortalSectionVisibility {
    static final int MAX_SCAN_RADIUS_SECTIONS = 24;
    static final int MAX_SCANNED_SECTIONS = 65_536;
    private static final double NEARBY_DISTANCE_IN_BLOCKS = 32.0;

    private PortalSectionVisibility() {
    }

    static int collect(ClientLevel level, ViewArea viewArea, Frustum frustum,
                       SectionPos center, List<SectionRenderDispatcher.RenderSection> visible,
                       List<SectionRenderDispatcher.RenderSection> nearby) {
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

        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    final int sectionX = centerX + dx;
                    final int sectionZ = centerZ + dz;
                    if (!level.getChunkSource().hasChunk(sectionX, sectionZ)) {
                        continue;
                    }
                    final int minY = Math.max(minSectionY, centerY - radius);
                    final int maxY = Math.min(maxSectionY, centerY + radius);
                    for (int sectionY = minY; sectionY <= maxY; sectionY++) {
                        if (budget <= 0) {
                            return added;
                        }
                        budget--;
                        final SectionRenderDispatcher.RenderSection section =
                                viewArea.getRenderSectionAt(new BlockPos(
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

    static boolean hasCompiledSection(List<SectionRenderDispatcher.RenderSection> sections) {
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
                return true;
            }
        }
        return false;
    }

    static void scheduleMissingCompiles(ClientLevel level,
                                        List<SectionRenderDispatcher.RenderSection> sections,
                                        int budget) {
        RenderRegionCache cache = null;
        int remaining = budget;
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (remaining <= 0) {
                return;
            }
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
                continue;
            }
            if (cache == null) {
                cache = new RenderRegionCache();
            }
            section.compileAsync(cache.createRegion(level, section.getSectionNode()));
            remaining--;
        }
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
