package io.mikaple.crythst.api.assembly;

import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gathers a connected block region from a real Minecraft world.
 *
 * <p>This is deliberately a world-space operation, not Sable plot assembly. The
 * result is a set of real world block positions; {@link StructureAssembly} can
 * then move those blocks into a Crythst world-space structure.</p>
 *
 * <p>Neighbourhood is 26-connected: diagonals and vertical diagonals connect,
 * which matches how player-built hulls usually touch. Air and blocks without a
 * collision shape (fluids, flowers, torches, ...) are not part of the region and
 * do not propagate the flood fill. The optional {@code frontierFilter} is an
 * additional veto for a candidate position, for example a claim/protection
 * check.</p>
 *
 * <p>The maximum block count is capped at {@link #MAX_BLOCKS}. Results are
 * sorted by {@code x}, then {@code y}, then {@code z}, so repeated gathers of an
 * unchanged world return identical lists.</p>
 */
public final class StructureGather {
    /** Hard safety cap for both gathering and assembly. */
    public static final int MAX_BLOCKS = 1_000_000;

    private static final int[][] NEIGHBOURS = neighbours();

    private StructureGather() {
    }

    /** Outcome of a gather attempt. */
    public enum Status {
        /** At least one assemblable block was found. */
        OK,
        /** The origin was air/unsupported/filtered or had no solid neighbours. */
        NO_BLOCKS,
        /** The connected region exceeds the requested limit. */
        TOO_MANY
    }

    /**
     * Inclusive world-space block bounds.
     */
    public record Bounds(int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ) {
        public Bounds {
            if (maxX < minX || maxY < minY || maxZ < minZ) {
                throw new IllegalArgumentException("Invalid block bounds: "
                        + minX + "," + minY + "," + minZ + " -> "
                        + maxX + "," + maxY + "," + maxZ);
            }
        }

        public static Bounds of(Iterable<BlockPos> positions) {
            Objects.requireNonNull(positions, "positions");
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            boolean any = false;
            for (BlockPos pos : positions) {
                if (pos == null) {
                    continue;
                }
                any = true;
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            if (!any) {
                throw new IllegalArgumentException("Cannot build bounds from an empty position list");
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        public BlockPos min() {
            return new BlockPos(minX, minY, minZ);
        }

        public BlockPos max() {
            return new BlockPos(maxX, maxY, maxZ);
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        public boolean contains(BlockPos pos) {
            return pos != null && contains(pos.getX(), pos.getY(), pos.getZ());
        }

        /** Inclusive block count. */
        public long volume() {
            return (long) (maxX - minX + 1)
                    * (maxY - minY + 1)
                    * (maxZ - minZ + 1);
        }

        /** Converts to the physics volume convention (exclusive maximum). */
        public IntBounds3 toIntBounds3() {
            return new IntBounds3(minX, minY, minZ,
                    Math.addExact(maxX, 1), Math.addExact(maxY, 1), Math.addExact(maxZ, 1));
        }
    }

    /**
     * Immutable gather result.
     *
     * <p>For {@link Status#TOO_MANY} the block list contains the first
     * {@code limit} discovered positions so callers can report a useful bound;
     * assembly must not proceed. For {@link Status#NO_BLOCKS} the list is empty
     * and {@link #bounds()} is {@code null}.</p>
     */
    public record GatherResult(Status status, List<BlockPos> blocks,
                               @Nullable Bounds bounds, int limit) {
        public GatherResult {
            Objects.requireNonNull(status, "status");
            blocks = List.copyOf(blocks);
            if (status == Status.NO_BLOCKS && !blocks.isEmpty()) {
                throw new IllegalArgumentException("NO_BLOCKS cannot carry block positions");
            }
            if (status != Status.NO_BLOCKS && blocks.isEmpty()) {
                throw new IllegalArgumentException(status + " requires at least one block");
            }
            if (status == Status.NO_BLOCKS && bounds != null) {
                throw new IllegalArgumentException("NO_BLOCKS cannot carry bounds");
            }
            if (status != Status.NO_BLOCKS && bounds == null) {
                throw new IllegalArgumentException(status + " requires bounds");
            }
        }

        public boolean success() {
            return status == Status.OK;
        }

        public int size() {
            return blocks.size();
        }
    }

    /**
     * Flood-fills 26-connected, assemblable blocks starting at {@code origin}.
     *
     * @param level          server level to read
     * @param origin         first block; must itself be assemblable
     * @param maxBlocks      requested limit, clamped to {@link #MAX_BLOCKS}
     * @param frontierFilter optional additional candidate filter
     */
    public static GatherResult gatherConnectedBlocks(ServerLevel level, BlockPos origin,
                                                     int maxBlocks,
                                                     @Nullable Predicate<BlockPos> frontierFilter) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        final int limit = normalizeLimit(maxBlocks);
        return gather(origin, limit, frontierFilter,
                pos -> StructureAssembly.isAssemblable(level, pos));
    }

    /**
     * Pure flood-fill core used by tests and by the level-backed entry point.
     *
     * @param solid decides whether a position is part of the region
     */
    static GatherResult gather(BlockPos origin, int maxBlocks,
                               @Nullable Predicate<BlockPos> frontierFilter,
                               Predicate<BlockPos> solid) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(solid, "solid");
        final int limit = normalizeLimit(maxBlocks);
        if (frontierFilter != null && !frontierFilter.test(origin)) {
            return new GatherResult(Status.NO_BLOCKS, List.of(), null, limit);
        }
        if (!solid.test(origin)) {
            return new GatherResult(Status.NO_BLOCKS, List.of(), null, limit);
        }

        final List<BlockPos> discovered = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        final BlockPos start = origin.immutable();
        discovered.add(start);
        seen.add(start.asLong());
        queue.add(start);

        boolean overflow = false;
        search:
        while (!queue.isEmpty()) {
            final BlockPos current = queue.poll();
            for (int[] offset : NEIGHBOURS) {
                final BlockPos candidate = current.offset(offset[0], offset[1], offset[2]);
                if (!seen.add(candidate.asLong())) {
                    continue;
                }
                if (frontierFilter != null && !frontierFilter.test(candidate)) {
                    continue;
                }
                if (!solid.test(candidate)) {
                    continue;
                }
                if (discovered.size() >= limit) {
                    overflow = true;
                    break search;
                }
                final BlockPos immutable = candidate.immutable();
                discovered.add(immutable);
                queue.add(immutable);
            }
        }

        discovered.sort(POSITION_ORDER);
        final List<BlockPos> blocks = List.copyOf(discovered);
        final Bounds bounds = Bounds.of(blocks);
        return new GatherResult(overflow ? Status.TOO_MANY : Status.OK, blocks, bounds, limit);
    }

    static int normalizeLimit(int maxBlocks) {
        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("maxBlocks must be positive: " + maxBlocks);
        }
        return Math.min(maxBlocks, MAX_BLOCKS);
    }

    static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt((BlockPos pos) -> pos.getX())
                    .thenComparingInt(pos -> pos.getY())
                    .thenComparingInt(pos -> pos.getZ());

    private static int[][] neighbours() {
        final List<int[]> offsets = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        offsets.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return offsets.toArray(new int[0][]);
    }
}
