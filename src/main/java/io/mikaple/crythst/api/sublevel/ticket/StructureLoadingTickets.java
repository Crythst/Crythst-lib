package io.mikaple.crythst.api.sublevel.ticket;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3dc;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Loading-ticket manager for structure regions.
 *
 * <p>Crythst already keeps the auxiliary ticking region of every live
 * structure loaded. This manager additionally pins the <em>host</em> chunks
 * covered by a structure's world AABB, which is what keeps terrain collision,
 * block lookups and entity movement usable while a structure travels through
 * otherwise unloaded terrain. It is a reference-counted façade over vanilla
 * {@link TicketType#PLAYER_LOADING} tickets, one ticket per covered chunk per
 * handle, so independent systems can share the same region.</p>
 *
 * <p>Tickets are ephemeral runtime state; they are not persisted. A persistent
 * {@link io.mikaple.crythst.api.sublevel.tracking.StructureTrackingPoint} with
 * {@code keepLoaded} set reacquires its ticket when the point manager is
 * opened, and the tracking-point data itself is stored in level saved data.</p>
 *
 * <p><strong>Threading:</strong> server thread only. <strong>Lifecycle:</strong>
 * call {@link #release()} or {@link #close()} when done. A ticket does not
 * protect a structure from explicit removal; it only keeps chunks loaded while
 * the structure is alive.</p>
 */
public final class StructureLoadingTickets {
    /** Chunk radius limit accepted by {@link #acquire(ServerLevel, PhysicsStructure, int)}. */
    public static final int MAX_RADIUS_CHUNKS = 32;

    private static final Map<ServerLevel, LevelState> LEVELS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private StructureLoadingTickets() {
    }

    /**
     * Acquires a ticket for the chunks covered by a structure's world AABB.
     *
     * @param level     host level
     * @param structure live structure
     * @return a ticket handle; release it with {@link StructureLoadingTicket#release()}
     */
    public static StructureLoadingTicket acquire(ServerLevel level, PhysicsStructure structure) {
        return acquire(level, structure, 0);
    }

    /**
     * Acquires a ticket for the structure's AABB and a chunk radius around it.
     *
     * @param level        host level
     * @param structure    live structure
     * @param radiusChunks extra chunks in each direction (0..{@value #MAX_RADIUS_CHUNKS})
     * @return a ticket handle
     */
    public static StructureLoadingTicket acquire(ServerLevel level, PhysicsStructure structure,
                                                 int radiusChunks) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (structure.closed()) {
            throw new IllegalArgumentException("structure is closed");
        }
        if (radiusChunks < 0 || radiusChunks > MAX_RADIUS_CHUNKS) {
            throw new IllegalArgumentException("radiusChunks must be between 0 and "
                    + MAX_RADIUS_CHUNKS + ": " + radiusChunks);
        }
        final io.mikaple.crythst.api.sublevel.StructureContainer container =
                io.mikaple.crythst.api.sublevel.StructureContainer.getContainer(level);
        if (container != null && container.byId(structure.body().id()) != structure) {
            throw new IllegalArgumentException("structure does not belong to " + level.dimension());
        }
        final LevelState state = LEVELS.computeIfAbsent(level, ignored -> new LevelState());
        return state.acquire(level, structure, radiusChunks);
    }

    /**
     * All live ticket handles for a level.
     */
    public static List<StructureLoadingTicket> tickets(ServerLevel level) {
        final LevelState state = level == null ? null : LEVELS.get(level);
        return state == null ? List.of() : state.tickets();
    }

    /**
     * Live ticket handles for one structure body id.
     */
    public static List<StructureLoadingTicket> tickets(ServerLevel level, long bodyId) {
        final List<StructureLoadingTicket> result = new ArrayList<>();
        for (StructureLoadingTicket ticket : tickets(level)) {
            if (ticket.bodyId() == bodyId) {
                result.add(ticket);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Recomputes every ticket in a level after structures moved.
     */
    public static void refreshAll(ServerLevel level) {
        final LevelState state = level == null ? null : LEVELS.get(level);
        if (state != null) {
            state.refreshAll();
        }
    }

    /** Number of live tickets for a level. */
    public static int ticketCount(ServerLevel level) {
        return tickets(level).size();
    }

    /** Number of chunks currently pinned by tickets in a level. */
    public static int pinnedChunkCount(ServerLevel level) {
        final LevelState state = level == null ? null : LEVELS.get(level);
        return state == null ? 0 : state.chunkCount();
    }

    /**
     * Releases every ticket for a level. Intended for level unload/tests.
     */
    public static void releaseAll(ServerLevel level) {
        final LevelState state = level == null ? null : LEVELS.remove(level);
        if (state != null) {
            state.releaseAll();
        }
    }

    /**
     * Deduplicated chunks covered by a world AABB plus a chunk radius. This is
     * a pure helper; it never loads a chunk.
     */
    public static List<ChunkPos> coveredChunks(Aabb bounds, int radiusChunks) {
        if (bounds == null) {
            throw new NullPointerException("bounds");
        }
        if (radiusChunks < 0) {
            throw new IllegalArgumentException("radiusChunks must be non-negative: " + radiusChunks);
        }
        return coveredChunks(bounds.min(), bounds.max(), radiusChunks);
    }

    /** {@link #coveredChunks(Aabb, int)} for explicit minimum/maximum corners. */
    public static List<ChunkPos> coveredChunks(Vector3dc min, Vector3dc max, int radiusChunks) {
        if (min == null || max == null) {
            throw new NullPointerException("min/max");
        }
        if (radiusChunks < 0) {
            throw new IllegalArgumentException("radiusChunks must be non-negative: " + radiusChunks);
        }
        final int minChunkX = (int) Math.floor(Math.min(min.x(), max.x()) / 16.0) - radiusChunks;
        final int maxChunkX = (int) Math.floor(Math.max(min.x(), max.x()) / 16.0) + radiusChunks;
        final int minChunkZ = (int) Math.floor(Math.min(min.z(), max.z()) / 16.0) - radiusChunks;
        final int maxChunkZ = (int) Math.floor(Math.max(min.z(), max.z()) / 16.0) + radiusChunks;
        final List<ChunkPos> result = new ArrayList<>();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                result.add(new ChunkPos(x, z));
            }
        }
        return List.copyOf(result);
    }

    private static final class LevelState {
        private final TicketReferenceLedger references = new TicketReferenceLedger();
        private final Map<Long, StructureLoadingTicket> tickets = new LinkedHashMap<>();

        private synchronized StructureLoadingTicket acquire(ServerLevel level,
                                                            PhysicsStructure structure,
                                                            int radiusChunks) {
            final StructureLoadingTicket ticket =
                    new StructureLoadingTicket(this, level, structure, radiusChunks);
            tickets.put(ticket.id(), ticket);
            ticket.applyChunks(coveredChunks(structure.aabb(), radiusChunks));
            return ticket;
        }

        private synchronized List<StructureLoadingTicket> tickets() {
            return List.copyOf(tickets.values());
        }

        private synchronized void refreshAll() {
            for (StructureLoadingTicket ticket : List.copyOf(tickets.values())) {
                ticket.refresh();
            }
        }

        private synchronized int chunkCount() {
            return references.size();
        }

        private synchronized void retain(Collection<ChunkPos> chunks, ServerLevel level) {
            for (ChunkPos chunk : chunks) {
                if (references.retain(chunk.pack())) {
                    level.getChunkSource().addTicketWithRadius(
                            TicketType.PLAYER_LOADING, chunk, 0);
                }
            }
        }

        private synchronized void release(Collection<ChunkPos> chunks, ServerLevel level) {
            for (ChunkPos chunk : chunks) {
                if (references.release(chunk.pack())) {
                    level.getChunkSource().removeTicketWithRadius(
                            TicketType.PLAYER_LOADING, chunk, 0);
                }
            }
        }

        private synchronized void forget(StructureLoadingTicket ticket) {
            tickets.remove(ticket.id());
        }

        private synchronized void releaseAll() {
            for (StructureLoadingTicket ticket : List.copyOf(tickets.values())) {
                ticket.release();
            }
            tickets.clear();
            references.clear();
        }
    }

    /**
     * Reference-counted handle over one structure's host chunk tickets.
     */
    public static final class StructureLoadingTicket implements AutoCloseable {
        private static long nextId = 1L;

        private final long id;
        private final LevelState state;
        private final PhysicsStructure structure;
        private final int radiusChunks;
        private final WeakReference<ServerLevel> level;
        private Set<Long> chunks = new LinkedHashSet<>();
        private boolean released;

        private StructureLoadingTicket(LevelState state, ServerLevel level,
                                       PhysicsStructure structure, int radiusChunks) {
            this.id = nextId++;
            this.state = state;
            this.structure = structure;
            this.radiusChunks = radiusChunks;
            this.level = new WeakReference<>(level);
        }

        /** Opaque ticket id. */
        public long id() {
            return id;
        }

        /** Structure whose AABB is pinned. */
        public PhysicsStructure structure() {
            return structure;
        }

        /** Body id of the pinned structure. */
        public long bodyId() {
            return structure.body().id();
        }

        /** Extra chunk radius requested around the structure AABB. */
        public int radiusChunks() {
            return radiusChunks;
        }

        /** Whether the ticket still holds its chunks. */
        public boolean isValid() {
            return !released && !structure.closed();
        }

        /** Whether the auxiliary ticking region of the structure exists. */
        public boolean isRegionActive() {
            if (structure.closed()) {
                return false;
            }
            for (LevelPhysics physics : MinecraftPhysics.levelPhysicsSnapshot()) {
                if (physics.world() == structure.world()) {
                    return physics.auxiliaryLevel() != null
                            && physics.auxiliaryLevel().regionView(structure) != null;
                }
            }
            return false;
        }

        /** Number of distinct host chunks currently pinned. */
        public int chunkCount() {
            return chunks.size();
        }

        /**
         * Recomputes covered chunks after the structure moved. No-op once
         * released.
         */
        public void refresh() {
            if (released) {
                return;
            }
            if (structure.closed() || level.get() == null) {
                release();
                return;
            }
            applyChunks(coveredChunks(structure.aabb(), radiusChunks));
        }

        private void applyChunks(List<ChunkPos> desired) {
            final Set<Long> next = new LinkedHashSet<>();
            final List<ChunkPos> add = new ArrayList<>();
            for (ChunkPos chunk : desired) {
                final long key = chunk.pack();
                if (next.add(key)) {
                    if (!chunks.contains(key)) {
                        add.add(chunk);
                    }
                }
            }
            final List<ChunkPos> remove = new ArrayList<>();
            for (long key : chunks) {
                if (!next.contains(key)) {
                    remove.add(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
                }
            }
            final ServerLevel host = level.get();
            if (host == null) {
                return;
            }
            if (!add.isEmpty()) {
                state.retain(add, host);
            }
            if (!remove.isEmpty()) {
                state.release(remove, host);
            }
            chunks = next;
        }

        /**
         * Releases this ticket. Idempotent.
         */
        public void release() {
            if (released) {
                return;
            }
            released = true;
            final ServerLevel host = level.get();
            if (host != null && !chunks.isEmpty()) {
                final List<ChunkPos> release = new ArrayList<>(chunks.size());
                for (long key : chunks) {
                    release.add(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
                }
                state.release(release, host);
            }
            chunks = Set.of();
            state.forget(this);
        }

        @Override
        public void close() {
            release();
        }

        @Override
        public String toString() {
            return "StructureLoadingTicket[id=" + id + ", body=" + bodyId()
                    + ", chunks=" + chunkCount() + ", released=" + released + "]";
        }
    }
}
