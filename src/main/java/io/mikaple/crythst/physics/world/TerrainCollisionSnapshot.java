package io.mikaple.crythst.physics.world;

import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;

import java.util.List;

/**
 * Immutable diagnostics for the Minecraft terrain collision cache.
 *
 * <p>Published by the server thread and safe to read from a debug renderer or a
 * command. Coordinates are world-space metres. A section with
 * {@code bodyId == -1} is cached but empty (loaded sky/air); it contributes no
 * collision body.</p>
 *
 * <p>This is a validation aid, not a stable gameplay API.</p>
 */
public record TerrainCollisionSnapshot(
        boolean enabled,
        int cachedSections,
        int bodySections,
        int emptySections,
        int wantedSections,
        int missingSections,
        int boxCount,
        long refreshCount,
        int lastRefreshTick,
        String lastRefreshReason,
        List<Section> sections,
        List<Region> regions
) {
    public TerrainCollisionSnapshot {
        sections = List.copyOf(sections);
        regions = List.copyOf(regions);
        if (lastRefreshReason == null) {
            lastRefreshReason = "never";
        }
    }

    /**
     * One cached terrain section.
     *
     * @param bodyId static Bullet body id, or {@code -1} for a cached empty section
     * @param boxes  merged full-cube boxes in absolute voxel coordinates
     * @param parts  exact partial-block boxes in absolute voxel coordinates
     */
    public record Section(
            int x, int y, int z,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            long bodyId,
            int boxCount,
            List<VoxelBox> boxes,
            List<VoxelCellPart> parts
    ) {
        public Section {
            boxes = List.copyOf(boxes);
            parts = List.copyOf(parts);
        }
    }

    /**
     * One dynamic body's requested terrain region.
     *
     * @param requiredSections sections overlapping the current AABB
     * @param plannedSections  sections selected for this body after capping
     * @param outsideTerrain   true when at least one required section is absent
     *                         from the cached set
     */
    public record Region(
            long bodyId,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double expandedMinX, double expandedMinY, double expandedMinZ,
            double expandedMaxX, double expandedMaxY, double expandedMaxZ,
            int requiredSections,
            int plannedSections,
            boolean outsideTerrain
    ) {
    }

    public static TerrainCollisionSnapshot empty() {
        return new TerrainCollisionSnapshot(false, 0, 0, 0, 0, 0, 0,
                0L, -1, "never", List.of(), List.of());
    }
}
