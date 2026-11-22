package io.mikaple.crythst.api.sublevel.tracking;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.api.sublevel.StructureContainer;
import io.mikaple.crythst.api.sublevel.ticket.StructureLoadingTickets;
import io.mikaple.crythst.integration.minecraft.storage.StructureSavedData;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side tracking points for structures, with optional host-chunk loading.
 *
 * <p>A tracking point is a named position in a structure's local frame plus the
 * world position captured when it was saved. Points are stored in per-level
 * saved data, so they survive world reloads even though physics body ids do
 * not. {@link #resolve(StructureTrackingPoint)} re-binds a point by body id,
 * then by stable structure identity, then by the saved world position.</p>
 *
 * <p>A point with {@link StructureTrackingPoint#keepLoaded()} set acquires a
 * {@link StructureLoadingTickets.StructureLoadingTicket} for its structure. Tickets are reacquired by
 * {@link #refreshTickets()} after a reload and released when the point is
 * removed or when {@link #clear()} runs. Tickets are runtime state and are not
 * persisted themselves.</p>
 *
 * <p><strong>Threading:</strong> server thread only. <strong>Lifecycle:</strong>
 * obtain this view with {@link #of(ServerLevel)}; it is a lightweight wrapper
 * over level saved data.</p>
 */
public final class StructureTrackingPoints {
    private static final SavedDataType<Data> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Crythst.MOD_ID, "structure_tracking_points"),
            Data::new,
            CompoundTag.CODEC.xmap(Data::new, Data::toTag),
            DataFixTypes.SAVED_DATA_STRUCTURE_FEATURE_INDICES);

    private final ServerLevel level;
    private final Data data;

    private StructureTrackingPoints(ServerLevel level, Data data) {
        this.level = Objects.requireNonNull(level, "level");
        this.data = Objects.requireNonNull(data, "data");
    }

    /**
     * Returns the tracking-point view for a level, loading its saved data.
     */
    public static StructureTrackingPoints of(ServerLevel level) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        final Data data = level.getDataStorage().computeIfAbsent(TYPE);
        final StructureTrackingPoints points = new StructureTrackingPoints(level, data);
        points.refreshTickets();
        return points;
    }

    /** Level owning these points. */
    public ServerLevel level() {
        return level;
    }

    /**
     * Adds a named point at a structure-local position.
     */
    public StructureTrackingPoint add(String name, PhysicsStructure structure,
                                      Vector3dc localPosition) {
        return add(name, structure, localPosition, false, 1);
    }

    /**
     * Adds a named point with optional persistence loading.
     *
     * @param name         display name; may be empty
     * @param structure    live structure
     * @param localPosition structure-local metres
     * @param keepLoaded   whether to keep host chunks loaded
     * @param radiusChunks extra chunk radius for the loading ticket
     */
    public StructureTrackingPoint add(String name, PhysicsStructure structure,
                                      Vector3dc localPosition, boolean keepLoaded,
                                      int radiusChunks) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (structure.closed()) {
            throw new IllegalArgumentException("structure is closed");
        }
        final StructureContainer container = StructureContainer.getContainer(level);
        if (container != null && container.byId(structure.body().id()) != structure) {
            throw new IllegalArgumentException("structure does not belong to " + level.dimension());
        }
        final Vector3d local = new Vector3d(localPosition);
        final UUID identity = structureIdentity(structure);
        final StructureTrackingPoint point = new StructureTrackingPoint(
                UUID.randomUUID(), name, structure.body().id(), identity,
                local, structure.pose().transformPoint(local), keepLoaded, radiusChunks);
        return put(point);
    }

    /**
     * Adds or replaces a point, preserving its id.
     */
    public StructureTrackingPoint put(StructureTrackingPoint point) {
        if (point == null) {
            throw new NullPointerException("point");
        }
        data.points.put(point.id(), point);
        data.setDirty();
        refreshTicket(point);
        return point;
    }

    /** Removes a point and releases its loading ticket. */
    public boolean remove(UUID id) {
        if (id == null) {
            return false;
        }
        final StructureTrackingPoint removed = data.points.remove(id);
        if (removed == null) {
            return false;
        }
        final StructureLoadingTickets.StructureLoadingTicket ticket = data.tickets.remove(id);
        if (ticket != null) {
            ticket.release();
        }
        data.setDirty();
        return true;
    }

    /** Removes a point by exact id; alias for {@link #remove(UUID)}. */
    public boolean remove(StructureTrackingPoint point) {
        return point != null && remove(point.id());
    }

    /** Point with the given id, or {@code null}. */
    public @Nullable StructureTrackingPoint get(UUID id) {
        return id == null ? null : data.points.get(id);
    }

    /**
     * First point with the given name, or {@code null}. Names are not unique.
     */
    public @Nullable StructureTrackingPoint byName(String name) {
        if (name == null) {
            return null;
        }
        for (StructureTrackingPoint point : data.points.values()) {
            if (name.equals(point.name())) {
                return point;
            }
        }
        return null;
    }

    /** All points in insertion order. */
    public List<StructureTrackingPoint> all() {
        return List.copyOf(data.points.values());
    }

    /** Points bound to one body id. */
    public List<StructureTrackingPoint> forStructure(long bodyId) {
        final List<StructureTrackingPoint> result = new ArrayList<>();
        for (StructureTrackingPoint point : data.points.values()) {
            if (point.bodyId() == bodyId) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    /** Points bound to a stable structure identity. */
    public List<StructureTrackingPoint> forStructureIdentity(UUID identity) {
        final List<StructureTrackingPoint> result = new ArrayList<>();
        if (identity == null) {
            return List.of();
        }
        for (StructureTrackingPoint point : data.points.values()) {
            if (identity.equals(point.structureIdentity())) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    /** Number of stored points. */
    public int size() {
        return data.points.size();
    }

    /** Whether no points are stored. */
    public boolean isEmpty() {
        return data.points.isEmpty();
    }

    /**
     * Removes every point and releases every ticket.
     */
    public void clear() {
        for (StructureLoadingTickets.StructureLoadingTicket ticket : List.copyOf(data.tickets.values())) {
            ticket.release();
        }
        data.tickets.clear();
        if (!data.points.isEmpty()) {
            data.points.clear();
            data.setDirty();
        }
    }

    /**
     * Resolves the live structure for a point.
     *
     * <p>Order: current body id, stable identity, then the structure containing
     * the saved world position. Returns {@code null} when the structure is not
     * loaded.</p>
     */
    public @Nullable PhysicsStructure resolve(StructureTrackingPoint point) {
        if (point == null) {
            return null;
        }
        final StructureContainer container = StructureContainer.getContainer(level);
        if (container != null) {
            final PhysicsStructure byId = container.byId(point.bodyId());
            if (byId != null) {
                return byId;
            }
        }
        final StructureSavedData structures =
                level.getDataStorage().get(StructureSavedData.TYPE);
        if (structures != null && point.structureIdentity() != null) {
            final PhysicsStructure byIdentity = structures.liveStructure(point.structureIdentity());
            if (byIdentity != null) {
                return byIdentity;
            }
        }
        if (container != null) {
            final Vector3d saved = point.worldPositionAtSave();
            return container.containing(new Vec3(saved.x, saved.y, saved.z));
        }
        return null;
    }

    /** Current world position of a point, preferring a live structure pose. */
    public Vector3d worldPosition(StructureTrackingPoint point) {
        if (point == null) {
            throw new NullPointerException("point");
        }
        final PhysicsStructure structure = resolve(point);
        return structure == null ? point.worldPosition() : point.worldPosition(structure);
    }

    /**
     * Nearest point to a world position within {@code maxDistance} metres.
     *
     * @return the nearest point, or {@code null} when none is in range
     */
    public @Nullable StructureTrackingPoint nearest(Vector3dc worldPoint, double maxDistance) {
        if (worldPoint == null) {
            throw new NullPointerException("worldPoint");
        }
        if (!(maxDistance >= 0.0) || !Double.isFinite(maxDistance)) {
            throw new IllegalArgumentException(
                    "maxDistance must be finite and non-negative: " + maxDistance);
        }
        StructureTrackingPoint nearest = null;
        double bestSquared = maxDistance * maxDistance;
        for (StructureTrackingPoint point : data.points.values()) {
            final double distanceSquared = worldPosition(point).distanceSquared(worldPoint);
            if (distanceSquared <= bestSquared) {
                bestSquared = distanceSquared;
                nearest = point;
            }
        }
        return nearest;
    }

    /**
     * Points within a world-space radius, sorted by distance.
     */
    public List<StructureTrackingPoint> nearby(Vector3dc worldPoint, double radius) {
        if (worldPoint == null) {
            throw new NullPointerException("worldPoint");
        }
        if (!(radius >= 0.0) || !Double.isFinite(radius)) {
            throw new IllegalArgumentException("radius must be finite and non-negative: " + radius);
        }
        final double radiusSquared = radius * radius;
        final List<Map.Entry<StructureTrackingPoint, Double>> candidates = new ArrayList<>();
        for (StructureTrackingPoint point : data.points.values()) {
            final double distanceSquared = worldPosition(point).distanceSquared(worldPoint);
            if (distanceSquared <= radiusSquared) {
                candidates.add(Map.entry(point, distanceSquared));
            }
        }
        candidates.sort(Map.Entry.comparingByValue());
        final List<StructureTrackingPoint> result = new ArrayList<>(candidates.size());
        for (Map.Entry<StructureTrackingPoint, Double> entry : candidates) {
            result.add(entry.getKey());
        }
        return List.copyOf(result);
    }

    /**
     * Re-binds points to restored structures and reacquires loading tickets.
     * Safe to call repeatedly; unchanged tickets are left alone.
     */
    public void refreshTickets() {
        for (StructureTrackingPoint point : data.points.values()) {
            refreshTicket(point);
        }
    }

    /**
     * Updates each point's saved world position from its live structure.
     * Returns the number of updated points.
     */
    public int captureWorldPositions() {
        int updated = 0;
        for (Map.Entry<UUID, StructureTrackingPoint> entry : List.copyOf(data.points.entrySet())) {
            final PhysicsStructure structure = resolve(entry.getValue());
            if (structure == null) {
                continue;
            }
            final StructureTrackingPoint point = entry.getValue();
            final Vector3d world = point.worldPosition(structure);
            if (!world.equals(point.worldPositionAtSave())) {
                final StructureTrackingPoint rebound =
                        point.withStructure(structure.body().id(),
                                structureIdentity(structure) == null ? point.structureIdentity()
                                        : structureIdentity(structure), point.localPosition())
                                .withSavePosition(world);
                data.points.put(entry.getKey(), rebound);
                updated++;
            } else if (point.bodyId() != structure.body().id()) {
                data.points.put(entry.getKey(), point.withStructure(structure.body().id(),
                        structureIdentity(structure), point.localPosition()));
                updated++;
            }
        }
        if (updated > 0) {
            data.setDirty();
        }
        return updated;
    }

    /**
     * Encoding helper used by saved data and tests.
     */
    public static CompoundTag writePoints(Collection<StructureTrackingPoint> points) {
        final CompoundTag root = new CompoundTag();
        final ListTag list = new ListTag();
        if (points != null) {
            for (StructureTrackingPoint point : points) {
                if (point != null) {
                    list.add(point.toTag());
                }
            }
        }
        root.put("Points", list);
        return root;
    }

    /**
     * Decoding helper used by saved data and tests.
     */
    public static List<StructureTrackingPoint> readPoints(CompoundTag root) {
        if (root == null) {
            return List.of();
        }
        final List<StructureTrackingPoint> result = new ArrayList<>();
        final ListTag list = root.getListOrEmpty("Points");
        for (int i = 0; i < list.size(); i++) {
            final StructureTrackingPoint point =
                    StructureTrackingPoint.fromTag(list.getCompoundOrEmpty(i));
            if (point != null) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    private void refreshTicket(StructureTrackingPoint point) {
        final PhysicsStructure structure = point.keepLoaded() ? resolve(point) : null;
        final StructureLoadingTickets.StructureLoadingTicket existing = data.tickets.get(point.id());
        if (structure == null) {
            if (existing != null) {
                existing.release();
                data.tickets.remove(point.id());
            }
            return;
        }
        if (existing != null && existing.isValid()
                && existing.bodyId() == structure.body().id()
                && existing.radiusChunks() == point.keepLoadedRadiusChunks()) {
            return;
        }
        if (existing != null) {
            existing.release();
        }
        data.tickets.put(point.id(), StructureLoadingTickets.acquire(
                level, structure, point.keepLoadedRadiusChunks()));
    }

    private @Nullable UUID structureIdentity(PhysicsStructure structure) {
        final StructureSavedData structures =
                level.getDataStorage().get(StructureSavedData.TYPE);
        return structures == null ? null : structures.identityOf(structure);
    }

    private static final class Data extends SavedData {
        private final Map<UUID, StructureTrackingPoint> points = new LinkedHashMap<>();
        private final Map<UUID, StructureLoadingTickets.StructureLoadingTicket> tickets = new LinkedHashMap<>();

        private Data() {
        }

        private Data(CompoundTag root) {
            for (StructureTrackingPoint point : readPoints(root)) {
                points.put(point.id(), point);
            }
        }

        private CompoundTag toTag() {
            return writePoints(points.values());
        }
    }
}
