package io.mikaple.crythst.api.client;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable per-structure client render data.
 *
 * <p>Instances are built from the integrated-client render snapshots or from
 * the multiplayer network cache. They contain no live body, volume or level
 * reference, so reading them from the render thread can never mutate physics
 * state.</p>
 */
public record StructureRenderData(
        long bodyId,
        StructureRenderPose renderPose,
        double blockScale,
        IntBounds3 bounds,
        Aabb aabb,
        StructureRenderView.Source source
) implements StructureRenderView {
    public StructureRenderData {
        if (bodyId < 0L) {
            throw new IllegalArgumentException("bodyId must be non-negative: " + bodyId);
        }
        renderPose = Objects.requireNonNull(renderPose, "renderPose");
        if (renderPose.bodyId() != bodyId) {
            throw new IllegalArgumentException("renderPose body " + renderPose.bodyId()
                    + " does not match " + bodyId);
        }
        bounds = Objects.requireNonNull(bounds, "bounds");
        aabb = Objects.requireNonNull(aabb, "aabb");
        source = Objects.requireNonNull(source, "source");
        if (!Double.isFinite(blockScale) || blockScale <= 0.0) {
            throw new IllegalArgumentException("blockScale must be positive: " + blockScale);
        }
    }

    /**
     * Builds a view and computes the world-space AABB from the voxel bounds.
     */
    public static StructureRenderData of(long bodyId, StructureRenderPose renderPose,
                                         double blockScale, IntBounds3 bounds,
                                         StructureRenderView.Source source) {
        return new StructureRenderData(bodyId, renderPose, blockScale, bounds,
                worldBounds(bounds, renderPose.pose(), blockScale), source);
    }

    /**
     * World-space AABB of a voxel grid mapped through a structure pose.
     *
     * <p>Voxel {@code (x, y, z)} occupies local metres
     * {@code [x, x + 1) * blockScale}; the pose maps those metres to world
     * space. This is the same box that selection and the batch renderer use.</p>
     */
    public static Aabb worldBounds(IntBounds3 bounds, Transform3d pose, double blockScale) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(pose, "pose");
        if (!(blockScale > 0.0)) {
            throw new IllegalArgumentException("blockScale must be positive: " + blockScale);
        }
        final double localMinX = bounds.minX() * blockScale;
        final double localMinY = bounds.minY() * blockScale;
        final double localMinZ = bounds.minZ() * blockScale;
        final double localMaxX = bounds.maxX() * blockScale;
        final double localMaxY = bounds.maxY() * blockScale;
        final double localMaxZ = bounds.maxZ() * blockScale;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
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

    /** All structures visible to the client at the current partial tick. */
    public static List<StructureRenderView> all() {
        return all(ClientPartialTick.current());
    }

    /** Explicit-partial-tick overload. */
    public static List<StructureRenderView> all(double partialTick) {
        final long now = System.nanoTime();
        final List<StructureRenderView> result = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();

        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final long bodyId = structure.renderStateAt(now).bodyId();
            if (!seen.add(bodyId)) {
                continue;
            }
            final StructureRenderPose pose = StructurePoseProvider.poseFor(bodyId, partialTick, now);
            if (pose == null) {
                continue;
            }
            result.add(StructureRenderData.of(bodyId, pose, structure.blockScale(),
                    structure.volume().bounds(), StructureRenderView.Source.INTEGRATED_CLIENT));
        }

        for (ClientStructureSnapshot snapshot : ClientStructureCache.snapshots()) {
            if (!seen.add(snapshot.bodyId())) {
                continue;
            }
            final StructureRenderPose pose = StructurePoseProvider.poseFor(
                    snapshot.bodyId(), partialTick, now);
            if (pose == null) {
                continue;
            }
            result.add(StructureRenderData.of(snapshot.bodyId(), pose, snapshot.blockScale(),
                    snapshot.bounds(), StructureRenderView.Source.NETWORK));
        }
        return List.copyOf(result);
    }

    /** A single visible structure at the current partial tick. */
    public static @Nullable StructureRenderView get(long bodyId) {
        return get(bodyId, ClientPartialTick.current());
    }

    /** A single visible structure at an explicit partial tick. */
    public static @Nullable StructureRenderView get(long bodyId, double partialTick) {
        if (bodyId < 0L) {
            return null;
        }
        final long now = System.nanoTime();
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed() || structure.renderStateAt(now).bodyId() != bodyId) {
                continue;
            }
            final StructureRenderPose pose = StructurePoseProvider.poseFor(bodyId, partialTick, now);
            return pose == null ? null : StructureRenderData.of(bodyId, pose,
                    structure.blockScale(), structure.volume().bounds(),
                    StructureRenderView.Source.INTEGRATED_CLIENT);
        }

        final ClientStructureSnapshot snapshot = ClientStructureCache.get(bodyId);
        if (snapshot == null) {
            return null;
        }
        final StructureRenderPose pose = StructurePoseProvider.poseFor(bodyId, partialTick, now);
        return pose == null ? null : StructureRenderData.of(bodyId, pose, snapshot.blockScale(),
                snapshot.bounds(), StructureRenderView.Source.NETWORK);
    }
}
