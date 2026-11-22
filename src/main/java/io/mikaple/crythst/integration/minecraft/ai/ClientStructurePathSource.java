package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.api.sublevel.view.StructureLevelViews;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Client-side {@link StructurePathSource}.
 *
 * <p>Singleplayer reads the live physics structures published for rendering;
 * multiplayer reads network snapshots. It is client-only and is class-loaded
 * exclusively through the guarded holder in {@link StructurePathSources}, so a
 * dedicated server never resolves client classes.</p>
 */
@Environment(EnvType.CLIENT)
final class ClientStructurePathSource implements StructurePathSource {
    private static final Map<Level, Snapshot> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Level level;

    ClientStructurePathSource(Level level) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        this.level = level;
    }

    @Override
    public List<StructurePathVolume> snapshot(Aabb query) {
        if (query == null) {
            throw new NullPointerException("query");
        }
        final Snapshot snapshot = current();
        if (snapshot.volumes().isEmpty()) {
            return List.of();
        }
        final List<StructurePathVolume> found = new ArrayList<>(4);
        for (StructurePathVolume volume : snapshot.volumes()) {
            if (volume.worldBounds().intersects(query)) {
                found.add(volume);
            }
        }
        return found;
    }

    @Override
    public long stamp() {
        return stampOf(level);
    }

    @Override
    public String side() {
        return "client";
    }

    private Snapshot current() {
        final long stamp = stampOf(level);
        final Snapshot cached = CACHE.get(level);
        if (cached != null && cached.stamp() == stamp) {
            return cached;
        }
        final List<StructurePathVolume> volumes = new ArrayList<>();
        final Set<Long> liveBodyIds = new HashSet<>();

        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure == null || structure.closed()) {
                continue;
            }
            liveBodyIds.add(structure.body().id());
            try {
                // Use the newest authoritative pose, not the one-tick-delayed
                // render interpolation.
                final Transform3d pose = structure.pose();
                final StructureLevelView view = StructureLevelViews.client(
                        structure, level, pose);
                volumes.add(StructurePathVolume.of(view));
            } catch (RuntimeException ignored) {
                // Structure closed while building the snapshot.
            }
        }

        final Identifier active = ClientStructureCache.activeDimension();
        if (active != null && active.equals(level.dimension().identifier())) {
            for (ClientStructureSnapshot structure : ClientStructureCache.snapshots()) {
                if (structure == null || structure.nonAirBlocks() <= 0
                        || liveBodyIds.contains(structure.bodyId())) {
                    continue;
                }
                try {
                    volumes.add(StructurePathVolume.of(
                            StructureLevelViews.snapshot(structure, level)));
                } catch (RuntimeException ignored) {
                    // Snapshot replaced while building.
                }
            }
        }

        final Snapshot rebuilt = new Snapshot(stamp, List.copyOf(volumes));
        CACHE.put(level, rebuilt);
        return rebuilt;
    }

    private static long stampOf(Level level) {
        long stamp = 17L;
        stamp = stamp * 31L + level.getGameTime();
        stamp = stamp * 31L + ClientStructureCache.revision();
        stamp = stamp * 31L + ClientStructureCache.activeSyncId();
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure == null || structure.closed()) {
                continue;
            }
            stamp = stamp * 31L + structure.body().id();
            stamp = stamp * 31L + structure.contentRevision();
            final Transform3d pose = structure.pose();
            stamp = hash(stamp, pose.position());
            stamp = hash(stamp, pose.rotation());
        }
        for (ClientStructureSnapshot structure : ClientStructureCache.snapshots()) {
            if (structure == null) {
                continue;
            }
            stamp = stamp * 31L + structure.bodyId();
            stamp = stamp * 31L + structure.geometryRevision();
            final Transform3d pose = structure.pose();
            stamp = hash(stamp, pose.position());
            stamp = hash(stamp, pose.rotation());
        }
        return stamp;
    }

    private static long hash(long seed, Vector3dc vector) {
        long hash = seed;
        hash = hash * 31L + Double.doubleToLongBits(vector.x());
        hash = hash * 31L + Double.doubleToLongBits(vector.y());
        hash = hash * 31L + Double.doubleToLongBits(vector.z());
        return hash;
    }

    private static long hash(long seed, Quaterniondc rotation) {
        long hash = seed;
        hash = hash * 31L + Double.doubleToLongBits(rotation.x());
        hash = hash * 31L + Double.doubleToLongBits(rotation.y());
        hash = hash * 31L + Double.doubleToLongBits(rotation.z());
        hash = hash * 31L + Double.doubleToLongBits(rotation.w());
        return hash;
    }

    private record Snapshot(long stamp, List<StructurePathVolume> volumes) {
    }
}
