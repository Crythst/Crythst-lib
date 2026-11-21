package io.mikaple.crythst.client.render.structure.batch;

import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Shared geometry view consumed by the batch cache.
 *
 * <p>Singleplayer and multiplayer both end up here: the server-thread
 * {@link StructureBlockSnapshots.Snapshot} and the network
 * {@link ClientStructureSnapshot} are adapted to the same immutable interface.
 * Pose and block scale stay per-frame inputs, so the cached meshes only depend
 * on block geometry. The pose is the structure pose (not the body pose), so
 * voxel coordinates map directly through it; the wire COM is retained for
 * diagnostics and physics consumers but must not be subtracted again.</p>
 */
final class StructureBatchSnapshots {
    private StructureBatchSnapshots() {
    }

    /**
     * Immutable voxel grid in the same coordinate space as
     * {@code VoxelVolume#bounds()}.
     */
    interface Snapshot {
        long bodyId();

        int minX();

        int minY();

        int minZ();

        int sizeX();

        int sizeY();

        int sizeZ();

        int nonAirBlocks();

        BlockState stateAt(int x, int y, int z);

        /**
         * Changes when block geometry changes. Pose-only updates preserve it.
         */
        long contentRevision();
    }

    static Snapshot fromServer(StructureBlockSnapshots.Snapshot snapshot) {
        return new ServerSnapshot(snapshot);
    }

    static Snapshot fromClient(ClientStructureSnapshot snapshot) {
        return new NetworkSnapshot(snapshot);
    }

    /**
     * World-space AABB for a network snapshot. The local box is the voxel
     * bounds converted to metres and mapped through the structure pose.
     * Structure pose already accounts for the centre-of-mass offset.
     */
    static Aabb worldBounds(Snapshot snapshot, Transform3d pose, double blockScale) {
        // Pose is the structure pose, not the body pose, so the local box does
        // not subtract the centre-of-mass offset.
        final double localMinX = snapshot.minX() * blockScale;
        final double localMinY = snapshot.minY() * blockScale;
        final double localMinZ = snapshot.minZ() * blockScale;
        final double localMaxX = (snapshot.minX() + snapshot.sizeX()) * blockScale;
        final double localMaxY = (snapshot.minY() + snapshot.sizeY()) * blockScale;
        final double localMaxZ = (snapshot.minZ() + snapshot.sizeZ()) * blockScale;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        // Aabb#transform in this codebase discards the transformed point, so
        // apply the pose explicitly here.
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d point = pose.transformPoint(new Vector3d(
                    (corner & 1) == 0 ? localMinX : localMaxX,
                    (corner & 2) == 0 ? localMinY : localMaxY,
                    (corner & 4) == 0 ? localMinZ : localMaxZ));
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }

    private record ServerSnapshot(StructureBlockSnapshots.Snapshot source) implements Snapshot {
        @Override
        public long bodyId() {
            return source.bodyId();
        }

        @Override
        public int minX() {
            return source.minX();
        }

        @Override
        public int minY() {
            return source.minY();
        }

        @Override
        public int minZ() {
            return source.minZ();
        }

        @Override
        public int sizeX() {
            return source.sizeX();
        }

        @Override
        public int sizeY() {
            return source.sizeY();
        }

        @Override
        public int sizeZ() {
            return source.sizeZ();
        }

        @Override
        public int nonAirBlocks() {
            return source.nonAirBlocks();
        }

        @Override
        public BlockState stateAt(int x, int y, int z) {
            return source.stateAt(x, y, z);
        }

        @Override
        public long contentRevision() {
            return source.revision();
        }
    }

    private record NetworkSnapshot(ClientStructureSnapshot source) implements Snapshot {
        @Override
        public long bodyId() {
            return source.bodyId();
        }

        @Override
        public int minX() {
            return source.bounds().minX();
        }

        @Override
        public int minY() {
            return source.bounds().minY();
        }

        @Override
        public int minZ() {
            return source.bounds().minZ();
        }

        @Override
        public int sizeX() {
            return source.bounds().sizeX();
        }

        @Override
        public int sizeY() {
            return source.bounds().sizeY();
        }

        @Override
        public int sizeZ() {
            return source.bounds().sizeZ();
        }

        @Override
        public int nonAirBlocks() {
            return source.nonAirBlocks();
        }

        @Override
        public BlockState stateAt(int x, int y, int z) {
            return source.blockStateAt(x, y, z);
        }

        @Override
        public long contentRevision() {
            return source.geometryRevision();
        }
    }
}
