package io.mikaple.crythst.api.client;

import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

/**
 * Read-only, client-thread-safe view of one structure that the client can
 * currently render and pick.
 *
 * <p>Implementations are produced by {@link StructureRenderData} from the
 * integrated-client render snapshots ({@code ClientPhysicsView}) or from the
 * multiplayer network cache ({@code ClientStructureCache}). This interface
 * never exposes a native body, a world volume or a mutating operation.</p>
 *
 * <p>All accessors are safe to call from the render thread. The static
 * collection methods sample one frame time and resolve poses through
 * {@link StructurePoseProvider}; registered providers may use the client
 * partial tick, but the default provider uses the timestamped render timeline
 * and ignores it.</p>
 */
public interface StructureRenderView {
    /** Physics body id, or the stable network snapshot id. */
    long bodyId();

    /** Sampled pose plus the timestamped previous/current endpoints. */
    StructureRenderPose renderPose();

    /** Metres per voxel. */
    double blockScale();

    /** Integer voxel bounds, inclusive minimum and exclusive maximum. */
    IntBounds3 bounds();

    /** World-space axis-aligned bounds of the voxel grid at the sampled pose. */
    Aabb aabb();

    /** Where this view came from. */
    Source source();

    /** Sampled structure pose at the requested frame time. */
    default Transform3d pose() {
        return renderPose().pose();
    }

    /** Older timeline endpoint. */
    default Transform3d previousPose() {
        return renderPose().previousPose();
    }

    /** Newer timeline endpoint. */
    default Transform3d currentPose() {
        return renderPose().currentPose();
    }

    /** World-space velocity at a point, in metres per second. */
    default Vector3d velocityAt(Vector3dc worldPoint) {
        return renderPose().velocityAt(worldPoint);
    }

    /** World-space centre of {@link #aabb()}. */
    default Vector3d center() {
        return aabb().center();
    }

    /**
     * All structures currently visible to the client, with the current client
     * partial tick.
     *
     * <p>The returned list is immutable. Integrated-client structures win over
     * network snapshots with the same body id, so a structure is never
     * reported twice in singleplayer.</p>
     */
    static List<StructureRenderView> all() {
        return StructureRenderData.all();
    }

    /** Explicit-partial-tick overload for callers that drive their own frame. */
    static List<StructureRenderView> all(double partialTick) {
        return StructureRenderData.all(partialTick);
    }

    /** A single visible structure, or {@code null} when it is not visible. */
    static @Nullable StructureRenderView get(long bodyId) {
        return StructureRenderData.get(bodyId);
    }

    /** A single visible structure at an explicit partial tick. */
    static @Nullable StructureRenderView get(long bodyId, double partialTick) {
        return StructureRenderData.get(bodyId, partialTick);
    }

    /** Source of a client render view. */
    enum Source {
        /** Published by the integrated server in singleplayer. */
        INTEGRATED_CLIENT,
        /** Received from the server through the structure sync channel. */
        NETWORK
    }
}
