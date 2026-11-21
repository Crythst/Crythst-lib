package io.mikaple.crythst.integration.minecraft.structure.view;

import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.api.sublevel.view.StructureLevelViews;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only client-side {@link StructureLevelView}.
 *
 * <p>Two data sources are supported:</p>
 * <ul>
 *   <li>the integrated client's live {@link PhysicsStructure} snapshot, which
 *       is read through the volume/state side table and a sampled render
 *       pose;</li>
 *   <li>a multiplayer {@link ClientStructureSnapshot}, whose immutable palette
 *       and cells are already render-thread safe.</li>
 * </ul>
 *
 * <p>Client views never write. {@link #setBlock} and
 * {@link #removeBlock} return {@code null} and leave the volume untouched. A
 * block entity is not available on the client because block-entity NBT is not
 * yet fully mirrored; {@link #getBlockEntity} returns {@code null}. This is
 * deliberate rather than falling back to the host world's block entity at the
 * projected world position.</p>
 */
public final class ClientStructureLevelView implements StructureLevelView {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState FALLBACK_SOLID = Blocks.STONE.defaultBlockState();

    private final @Nullable PhysicsStructure structure;
    private final @Nullable ClientStructureSnapshot snapshot;
    private final @Nullable Level level;
    private final long bodyId;
    private final Transform3d pose;
    private final double blockScale;
    private final IntBounds3 bounds;
    private final long epoch;

    private ClientStructureLevelView(@Nullable PhysicsStructure structure,
                                     @Nullable ClientStructureSnapshot snapshot,
                                     @Nullable Level level, long bodyId,
                                     Transform3d pose, double blockScale,
                                     IntBounds3 bounds, long epoch) {
        if (structure == null && snapshot == null) {
            throw new IllegalArgumentException("A client view needs a structure or a snapshot");
        }
        if (bounds == null || pose == null) {
            throw new NullPointerException("bounds/pose");
        }
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            throw new IllegalArgumentException("blockScale must be positive: " + blockScale);
        }
        this.structure = structure;
        this.snapshot = snapshot;
        this.level = level;
        this.bodyId = bodyId;
        this.pose = pose;
        this.blockScale = blockScale;
        this.bounds = bounds;
        this.epoch = epoch;
    }

    /**
     * Creates a read-only view over an integrated-client structure.
     *
     * @param pose render pose sampled by the caller on the client thread
     */
    public static ClientStructureLevelView ofLive(PhysicsStructure structure,
                                                  @Nullable Level level,
                                                  Transform3d pose) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (pose == null) {
            throw new NullPointerException("pose");
        }
        return new ClientStructureLevelView(structure, null, level,
                structure.body().id(), pose, structure.blockScale(),
                structure.volume().bounds(), StructureLevelViews.epoch(structure));
    }

    /** Creates a read-only view over a multiplayer network snapshot. */
    public static ClientStructureLevelView ofSnapshot(ClientStructureSnapshot snapshot,
                                                      @Nullable Level level) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        return new ClientStructureLevelView(null, snapshot, level, snapshot.bodyId(),
                snapshot.pose(), snapshot.blockScale(), snapshot.bounds(),
                snapshot.geometryRevision());
    }

    /** The network snapshot backing this view, or {@code null} for a live view. */
    public @Nullable ClientStructureSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public @Nullable PhysicsStructure structure() {
        return structure;
    }

    @Override
    public @Nullable Level level() {
        return level;
    }

    @Override
    public long bodyId() {
        return bodyId;
    }

    @Override
    public double blockScale() {
        return blockScale;
    }

    @Override
    public IntBounds3 bounds() {
        return bounds;
    }

    @Override
    public Transform3d pose() {
        return pose;
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public long epoch() {
        return epoch;
    }

    @Override
    public boolean isCurrent() {
        if (snapshot != null) {
            // Immutable snapshot. Callers discard/replace it when the network
            // cache publishes a newer geometry revision.
            return true;
        }
        return structure != null && !structure.closed()
                && epoch == StructureLevelViews.epoch(structure);
    }

    @Override
    public BlockState getBlockState(BlockPos localPos) {
        if (localPos == null) {
            return AIR;
        }
        final int x = localPos.getX();
        final int y = localPos.getY();
        final int z = localPos.getZ();
        if (!bounds.contains(x, y, z)) {
            return AIR;
        }
        if (snapshot != null) {
            return snapshot.blockStateAt(x, y, z);
        }
        if (structure == null || structure.closed()) {
            return AIR;
        }
        final VoxelVolume volume = structure.volume();
        if (!volume.bounds().contains(x, y, z)) {
            return AIR;
        }
        if (volume instanceof StatefulVoxelVolume stateful) {
            final BlockState stored = stateful.blockState(x, y, z);
            if (stored != null) {
                return stored;
            }
        }
        final BlockState sideTable = StructureBlockStates.get(structure, x, y, z);
        if (sideTable != null) {
            return sideTable;
        }
        return volume.isSolid(x, y, z) ? FALLBACK_SOLID : AIR;
    }

    @Override
    public RegistryAccess registryAccess() {
        return level == null ? RegistryAccess.EMPTY : level.registryAccess();
    }

    @Override
    public @Nullable BlockState setBlock(BlockPos localPos, BlockState state, int flags, int recursion) {
        // Read-only by design. Returning null is the same failure shape as a
        // server view whose volume cannot be edited.
        return null;
    }

    @Override
    public @Nullable BlockState removeBlock(BlockPos localPos, boolean moving) {
        return null;
    }
}
