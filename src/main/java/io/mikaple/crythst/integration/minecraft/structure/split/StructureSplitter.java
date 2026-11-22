package io.mikaple.crythst.integration.minecraft.structure.split;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.storage.StructureBlockEntityData;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits one physics structure into its disconnected solid components.
 *
 * <p>A component is a maximal set of solid voxels connected through any of the
 * 26 surrounding cells: face, edge and corner contact all keep two blocks in
 * the same body. The scan is bounded by {@link #MAX_CELLS} so a malformed volume
 * cannot make the server walk an unbounded grid.</p>
 *
 * <p>The split keeps the original <em>bounds</em> for every new volume. Voxel
 * coordinates therefore do not move, the original structure pose can be reused
 * verbatim and the blocks stay exactly where they were. Mass, material, block
 * state and block-entity data are copied to the component that owns the cell.
 * The new body's centre of mass is recomputed by
 * {@link PhysicsStructure}.</p>
 */
public final class StructureSplitter {
    /**
     * Maximum number of cells (including air) inspected by
     * {@link #findComponents(VoxelVolume)}. Matches the default voxel collider
     * limit.
     */
    public static final int MAX_CELLS = VoxelColliderOptions.DEFAULT.maxCells();

    private StructureSplitter() {
    }

    /** One solid voxel in structure-local coordinates. */
    public record Cell(int x, int y, int z) {
    }

    /**
     * One connected solid component.
     *
     * @param bounds tight bounds of the component
     * @param cells  solid cells in deterministic (Y, then Z, then X) order
     * @param boxes  greedy-merged boxes for the component, for diagnostics and
     *               for callers that need its local shape without rebuilding it
     */
    public record Component(IntBounds3 bounds, List<Cell> cells, List<VoxelBox> boxes) {
        public Component {
            bounds = Objects.requireNonNull(bounds, "bounds");
            cells = List.copyOf(cells);
            boxes = List.copyOf(boxes);
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("A component must contain at least one cell");
            }
        }

        public int cellCount() {
            return cells.size();
        }
    }

    /**
     * Finds all 26-neighbour connected solid components in deterministic
     * order. Face, edge and diagonal (corner) contact all connect cells.
     *
     * <p>Air cells are ignored. Components are ordered by their first cell in
     * the same Y/Z/X scan order used by the collider builder, and cells inside a
     * component are sorted in that order as well.</p>
     *
     * @param volume source grid
     * @return an immutable list of components; empty when the volume has no
     *         solid cells
     * @throws IllegalArgumentException when the bounds exceed {@link #MAX_CELLS}
     */
    public static List<Component> findComponents(VoxelVolume volume) {
        Objects.requireNonNull(volume, "volume");
        final IntBounds3 bounds = Objects.requireNonNull(volume.bounds(), "volume.bounds()");
        final long cellCount = bounds.volume();
        if (cellCount > MAX_CELLS) {
            throw new IllegalArgumentException("Voxel volume is too large to split: "
                    + cellCount + " cells (limit " + MAX_CELLS + ")");
        }

        final int cells = (int) cellCount;
        final int sizeX = bounds.sizeX();
        final int sizeY = bounds.sizeY();
        final int sizeZ = bounds.sizeZ();
        final int layer = sizeX * sizeZ;

        final boolean[] visited = new boolean[cells];
        // Reused by every flood fill; a component can never be larger than the
        // whole volume. The first `tail` slots hold the component indices.
        final int[] queue = new int[cells];
        final List<Component> components = new ArrayList<>();

        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    final int start = index(bounds, x, y, z);
                    if (visited[start] || !volume.isSolid(x, y, z)) {
                        continue;
                    }

                    int head = 0;
                    int tail = 0;
                    visited[start] = true;
                    queue[tail++] = start;

                    while (head < tail) {
                        final int current = queue[head++];
                        final int localX = current % sizeX;
                        final int rest = current / sizeX;
                        final int localZ = rest % sizeZ;
                        final int localY = rest / sizeZ;
                        final int worldX = bounds.minX() + localX;
                        final int worldY = bounds.minY() + localY;
                        final int worldZ = bounds.minZ() + localZ;

                        // 26-neighbour connectivity: a block keeps the body
                        // together through faces, edges and corners.
                        for (int dy = -1; dy <= 1; dy++) {
                            final int neighbourY = worldY + dy;
                            if (neighbourY < bounds.minY() || neighbourY >= bounds.maxY()) {
                                continue;
                            }
                            for (int dz = -1; dz <= 1; dz++) {
                                final int neighbourZ = worldZ + dz;
                                if (neighbourZ < bounds.minZ() || neighbourZ >= bounds.maxZ()) {
                                    continue;
                                }
                                for (int dx = -1; dx <= 1; dx++) {
                                    final int neighbourX = worldX + dx;
                                    if ((dx | dy | dz) == 0
                                            || neighbourX < bounds.minX()
                                            || neighbourX >= bounds.maxX()) {
                                        continue;
                                    }
                                    tail = enqueue(volume, visited, queue, tail,
                                            index(bounds, neighbourX, neighbourY, neighbourZ),
                                            neighbourX, neighbourY, neighbourZ);
                                }
                            }
                        }
                    }

                    // BFS discovery order is not coordinate order. Sorting the
                    // first `tail` queue slots makes both cells() and the
                    // component order deterministic across JVM runs.
                    Arrays.sort(queue, 0, tail);
                    final List<Cell> componentCells = new ArrayList<>(tail);
                    int minX = Integer.MAX_VALUE;
                    int minY = Integer.MAX_VALUE;
                    int minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE;
                    int maxY = Integer.MIN_VALUE;
                    int maxZ = Integer.MIN_VALUE;
                    for (int i = 0; i < tail; i++) {
                        final int current = queue[i];
                        final int localX = current % sizeX;
                        final int rest = current / sizeX;
                        final int localZ = rest % sizeZ;
                        final int localY = rest / sizeZ;
                        final int worldX = bounds.minX() + localX;
                        final int worldY = bounds.minY() + localY;
                        final int worldZ = bounds.minZ() + localZ;
                        componentCells.add(new Cell(worldX, worldY, worldZ));
                        minX = Math.min(minX, worldX);
                        minY = Math.min(minY, worldY);
                        minZ = Math.min(minZ, worldZ);
                        maxX = Math.max(maxX, worldX + 1);
                        maxY = Math.max(maxY, worldY + 1);
                        maxZ = Math.max(maxZ, worldZ + 1);
                    }

                    final IntBounds3 componentBounds =
                            new IntBounds3(minX, minY, minZ, maxX, maxY, maxZ);
                    components.add(new Component(componentBounds, componentCells,
                            mergeBoxes(componentBounds, componentCells)));
                }
            }
        }

        return List.copyOf(components);
    }

    /**
     * Whether a volume has no solid cells or a total collider mass of zero.
     *
     * <p>This is the same test used by {@link #removeIfMassless} and is public
     * so callers can decide whether a reindex is meaningful before rebuilding a
     * body. Building the collider is skipped for oversized volumes; the summed
     * cell mass is equivalent because the collider's total mass is exactly the
     * sum of its boxes' cell masses.</p>
     */
    public static boolean isMassless(VoxelVolume volume) {
        Objects.requireNonNull(volume, "volume");
        final IntBounds3 bounds = Objects.requireNonNull(volume.bounds(), "volume.bounds()");
        if (bounds.volume() > MAX_CELLS) {
            return sumMass(volume, bounds) <= 0.0;
        }
        return VoxelColliderBuilder.build(volume, VoxelColliderOptions.DEFAULT).mass() <= 0.0;
    }

    /**
     * Removes a structure when its volume has no solid cells or zero total
     * mass, and clears the per-structure side tables.
     *
     * <p>Call this from {@code LevelPhysics.refreshStructure} and from the
     * interaction path after {@code PhysicsStructure.reindex} (or instead of
     * reindexing when the volume is empty).</p>
     *
     * @return {@code true} when the structure was removed
     */
    public static boolean removeIfMassless(LevelPhysics physics, PhysicsStructure original,
                                           VoxelVolume volume) {
        Objects.requireNonNull(physics, "physics");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(volume, "volume");
        if (original.closed() || !isMassless(volume)) {
            return false;
        }
        removeStructure(physics, original);
        return true;
    }

    /**
     * Splits a structure into one body per disconnected solid component.
     *
     * <p>When there is at most one component the original structure is returned
     * unchanged. A zero-mass volume is removed entirely and an empty list is
     * returned. Otherwise the new bodies are created first, their metadata is
     * copied, and only then is the original removed. If creating a split body
     * fails, the splits created so far are closed and the original is kept.</p>
     *
     * @param physics  world that owns the structure
     * @param original structure to split
     * @param volume   current volume of the structure (usually the volume just
     *                 passed to {@code reindex})
     * @return the original singleton list when no split was needed, otherwise an
     *         immutable list of the new structures; empty when the body was
     *         removed as massless
     */
    public static List<PhysicsStructure> splitIfNeeded(LevelPhysics physics, PhysicsStructure original,
                                                       VoxelVolume volume) {
        Objects.requireNonNull(physics, "physics");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(volume, "volume");
        if (original.closed()) {
            return List.of(original);
        }
        if (isMassless(volume)) {
            removeStructure(physics, original);
            return List.of();
        }

        final List<Component> components = findComponents(volume);
        if (components.size() <= 1) {
            return List.of(original);
        }
        // The auxiliary regions are rebuilt for each split component. Capture
        // pending repeater/observer/fluid ticks before the original region is
        // removed, then hand each tick to the component that owns its voxel.
        final CompoundTag originalTicks = physics.captureScheduledTicks(original);

        final IntBounds3 bounds = volume.bounds();
        final VoxelColliderOptions colliderOptions =
                VoxelColliderOptions.DEFAULT.withBlockScale(original.blockScale());
        final int componentCount = components.size();
        final List<MutableVoxelVolume> volumes = new ArrayList<>(componentCount);
        for (Component component : components) {
            volumes.add(copyComponent(volume, bounds, component));
        }

        final List<PhysicsStructure> created = new ArrayList<>(componentCount);
        try {
            // Reuse the live pose, not a stale definition pose, so blocks do not
            // jump at the moment of the split.
            final Transform3d pose = original.pose();
            final BodyType type = original.body().type();
            final PhysicsMaterial material = original.body().material();
            final double massScale = original.massScale();
            final boolean ccd = original.body().ccd();
            for (MutableVoxelVolume componentVolume : volumes) {
                final StructureDef definition = StructureDef.builder(componentVolume)
                        .pose(pose)
                        .type(type)
                        .material(material)
                        .colliderOptions(colliderOptions)
                        .massScale(massScale)
                        .ccd(ccd)
                        .build();
                created.add(physics.createStructure(definition));
            }
        } catch (RuntimeException e) {
            Crythst.LOGGER.warn("Could not split structure body #{}; keeping the original body",
                    original.body().id(), e);
            for (PhysicsStructure split : created) {
                physics.removeStructure(split);
                StructureBlockStates.clear(split);
                StructureBlockEntityData.remove(split);
            }
            return List.of(original);
        }

        final Map<Cell, Integer> owners = new HashMap<>();
        for (int i = 0; i < componentCount; i++) {
            for (Cell cell : components.get(i).cells()) {
                owners.put(cell, i);
            }
        }

        final List<ListTag> splitBlockTicks = new ArrayList<>(componentCount);
        final List<ListTag> splitFluidTicks = new ArrayList<>(componentCount);
        for (int i = 0; i < componentCount; i++) {
            splitBlockTicks.add(new ListTag());
            splitFluidTicks.add(new ListTag());
        }
        distributeTicks(originalTicks.getListOrEmpty("Block"), owners, splitBlockTicks);
        distributeTicks(originalTicks.getListOrEmpty("Fluid"), owners, splitFluidTicks);

        final List<Map<BlockPos, CompoundTag>> splitBlockEntities = new ArrayList<>(componentCount);
        for (int i = 0; i < componentCount; i++) {
            splitBlockEntities.add(new LinkedHashMap<>());
        }
        for (Map.Entry<BlockPos, CompoundTag> entry : StructureBlockEntityData.get(original).entrySet()) {
            final BlockPos pos = entry.getKey();
            // Block-entity keys are structure-local voxels relative to the
            // volume minimum (see StructureSavedData.captureBlockEntities).
            final int absoluteX = bounds.minX() + pos.getX();
            final int absoluteY = bounds.minY() + pos.getY();
            final int absoluteZ = bounds.minZ() + pos.getZ();
            final Integer owner = owners.get(new Cell(absoluteX, absoluteY, absoluteZ));
            if (owner != null) {
                // Split components have their own bounds minimum. Convert the
                // original-relative key to the component-relative key instead
                // of carrying the old origin into applyBlockEntities.
                final IntBounds3 componentBounds = components.get(owner).bounds();
                splitBlockEntities.get(owner).put(new BlockPos(
                        absoluteX - componentBounds.minX(),
                        absoluteY - componentBounds.minY(),
                        absoluteZ - componentBounds.minZ()), entry.getValue());
            }
        }

        final Vector3d linearVelocity = original.body().linearVelocity();
        final Vector3d angularVelocity = original.body().angularVelocity();
        final double gravityScale = original.body().gravityScale();
        final Vector3d gravityOverride = original.body().gravityOverride();

        for (int i = 0; i < componentCount; i++) {
            final PhysicsStructure split = created.get(i);
            final Component component = components.get(i);

            for (Cell cell : component.cells()) {
                final BlockState state = stateAt(original, volume, cell);
                if (state != null && !state.isAir()) {
                    StructureBlockStates.set(split, cell.x(), cell.y(), cell.z(), state);
                }
            }

            split.body().setLinearVelocity(linearVelocity);
            split.body().setAngularVelocity(angularVelocity);
            split.body().setGravityScale(gravityScale);
            if (gravityOverride == null) {
                split.body().clearGravityOverride();
            } else {
                split.body().setGravityOverride(gravityOverride);
            }
            split.syncRenderPose();
            split.body().activate();

            final Map<BlockPos, CompoundTag> blockEntities = splitBlockEntities.get(i);
            if (!blockEntities.isEmpty()) {
                StructureBlockEntityData.put(split, blockEntities);
                physics.applyBlockEntities(split, blockEntities);
            }
            final CompoundTag ticks = new CompoundTag();
            if (!splitBlockTicks.get(i).isEmpty()) {
                ticks.put("Block", splitBlockTicks.get(i));
            }
            if (!splitFluidTicks.get(i).isEmpty()) {
                ticks.put("Fluid", splitFluidTicks.get(i));
            }
            if (!ticks.isEmpty()) {
                physics.applyScheduledTicks(split, ticks);
            }
            // New body id: publish its block snapshot before the next tick so
            // the split does not flicker for one frame.
            physics.captureSnapshotNow(split);
        }

        removeStructure(physics, original);
        return List.copyOf(created);
    }

    private static int enqueue(VoxelVolume volume, boolean[] visited, int[] queue, int tail,
                               int index, int x, int y, int z) {
        if (visited[index] || !volume.isSolid(x, y, z)) {
            return tail;
        }
        visited[index] = true;
        queue[tail] = index;
        return tail + 1;
    }

    private static MutableVoxelVolume copyComponent(VoxelVolume source, IntBounds3 bounds,
                                                     Component component) {
        final MutableVoxelVolume target = new MutableVoxelVolume(bounds);
        for (Cell cell : component.cells()) {
            target.setBlock(cell.x(), cell.y(), cell.z(), true,
                    source.blockMass(cell.x(), cell.y(), cell.z()),
                    source.blockMaterial(cell.x(), cell.y(), cell.z()));
        }
        return target;
    }

    private static List<VoxelBox> mergeBoxes(IntBounds3 bounds, List<Cell> cells) {
        final boolean[] solid = new boolean[(int) bounds.volume()];
        for (Cell cell : cells) {
            solid[index(bounds, cell.x(), cell.y(), cell.z())] = true;
        }
        final VoxelVolume componentVolume = VoxelVolumes.solid(bounds,
                cell -> solid[index(bounds, cell.x(), cell.y(), cell.z())]);
        return VoxelColliderBuilder.build(componentVolume, VoxelColliderOptions.DEFAULT).boxes();
    }

    private static BlockState stateAt(PhysicsStructure structure, VoxelVolume volume, Cell cell) {
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState state = stateful.blockState(cell.x(), cell.y(), cell.z());
            if (state != null) {
                return state;
            }
        }
        return StructureBlockStates.get(structure, cell.x(), cell.y(), cell.z());
    }

    private static double sumMass(VoxelVolume volume, IntBounds3 bounds) {
        double total = 0.0;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    if (volume.isSolid(x, y, z)) {
                        total += volume.blockMass(x, y, z);
                    }
                }
            }
        }
        return total;
    }

    private static void distributeTicks(ListTag source, Map<Cell, Integer> owners,
                                        List<ListTag> destinations) {
        for (int i = 0; i < source.size(); i++) {
            final CompoundTag entry = source.getCompoundOrEmpty(i);
            if (entry.isEmpty()) {
                continue;
            }
            final Cell cell = new Cell(entry.getIntOr("X", 0),
                    entry.getIntOr("Y", 0), entry.getIntOr("Z", 0));
            final Integer owner = owners.get(cell);
            if (owner != null) {
                destinations.get(owner).add(entry.copy());
            }
        }
    }

    private static void removeStructure(LevelPhysics physics, PhysicsStructure structure) {
        physics.removeStructure(structure);
        StructureBlockStates.clear(structure);
        StructureBlockEntityData.remove(structure);
    }

    private static int index(IntBounds3 bounds, int x, int y, int z) {
        final int localX = x - bounds.minX();
        final int localY = y - bounds.minY();
        final int localZ = z - bounds.minZ();
        return (localY * bounds.sizeZ() + localZ) * bounds.sizeX() + localX;
    }
}
