package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.collision.TerrainCollisionGroups;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionGeometry;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionGeometry.Key;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionMask;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.TerrainBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds static Bullet bodies from nearby Minecraft terrain sections.
 *
 * <p>Each 16x16x16 section is greedily merged into boxes and cached. Sections
 * are loaded lazily around the AABBs of dynamic bodies, plus a lookahead along
 * gravity. Empty sections are cached too, so a row of sky sections is not
 * rescanned on every refresh. Block edits invalidate only the affected section
 * (and adjacent sections when the edited block touches a boundary); the next
 * refresh rebuilds it from live world data.</p>
 *
 * <p>Collision is limited to one thread (the server thread). Invalidation
 * requests that arrive while a refresh is iterating the cache are queued and
 * applied after the pass, so bodies are never removed mid-iteration.</p>
 */
public final class TerrainCollision {
    /**
     * Terrain has no meaningful mass and all boxes share the same default
     * material, so merging across materials is safe and avoids repeated
     * property lookups during the greedy sweep.
     */
    private static final VoxelColliderOptions TERRAIN_OPTIONS = new VoxelColliderOptions(
            true, true, 1.0, TerrainSectionGeometry.SECTION_CELLS);

    /** Cached marker for a section that is loaded but contains no collision. */
    private static final Entry EMPTY = new Entry(null);

    private static final int[] SAME_SECTION = {0};
    private static final int[] NEGATIVE_NEIGHBOUR = {-1, 0};
    private static final int[] POSITIVE_NEIGHBOUR = {0, 1};

    private final ServerLevel level;
    private final PhysicsWorld world;
    private final CrythstConfig config;

    private final Map<Key, Entry> sections = new HashMap<>();
    private final Set<Key> deferredInvalidations = new HashSet<>();
    private final Set<Key> failedSections = new HashSet<>();
    private boolean clearPending;
    private boolean refreshing;
    private boolean refreshRequested;
    private boolean closed;
    private int ticks;

    public TerrainCollision(ServerLevel level, PhysicsWorld world, CrythstConfig config) {
        this.level = level;
        this.world = world;
        this.config = config;
    }

    /**
     * Called once per server tick. Loading is throttled by
     * {@code worldCollisionRefreshTicks}, but the first call (or an explicit
     * {@link #ensureNow()}) loads immediately. Disabling collision at runtime
     * removes every cached terrain body instead of leaving stale geometry.
     */
    public void tick() {
        if (closed) {
            return;
        }
        if (!config.worldCollisionEnabled) {
            invalidateAll();
            return;
        }
        final int refreshTicks = Math.max(1, config.worldCollisionRefreshTicks);
        if (!refreshRequested && ticks++ % refreshTicks != 0) {
            return;
        }
        refresh();
    }

    public void ensureNow() {
        if (closed) {
            return;
        }
        if (!config.worldCollisionEnabled) {
            invalidateAll();
            return;
        }
        refresh();
    }

    private void refresh() {
        if (closed || refreshing) {
            // Reentrant refreshes are picked up by the next tick; block edits
            // made during this pass are queued through invalidateAt.
            return;
        }
        refreshRequested = false;
        refreshing = true;
        try {
            final Set<Key> wanted = collectWantedSections();
            if (wanted.isEmpty()) {
                // Keep cached sections: a body may be paused temporarily. Still
                // release sections whose chunk has unloaded.
                removeUnloadedSections();
                failedSections.clear();
                return;
            }

            for (Key key : wanted) {
                if (sections.containsKey(key)) {
                    continue;
                }
                final Entry entry;
                try {
                    entry = build(key);
                } catch (RuntimeException e) {
                    // One broken block state (for example from another mod)
                    // must not stop the remaining sections from loading. Warn
                    // once per section instead of once per refresh.
                    if (failedSections.add(key)) {
                        Crythst.LOGGER.warn("Could not build terrain section {}", key, e);
                    }
                    continue;
                }
                if (entry != null) {
                    failedSections.remove(key);
                    sections.put(key, entry);
                }
            }

            // Drop sections that are no longer near any body, or whose chunk
            // was unloaded. This also keeps the number of native static bodies
            // bounded while a structure travels.
            final Iterator<Map.Entry<Key, Entry>> iterator = sections.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<Key, Entry> cached = iterator.next();
                if (!wanted.contains(cached.getKey()) || !isLoaded(cached.getKey())) {
                    removeBody(cached.getValue());
                    iterator.remove();
                    failedSections.remove(cached.getKey());
                }
            }
            failedSections.retainAll(wanted);
        } catch (RuntimeException e) {
            Crythst.LOGGER.warn("Terrain collision refresh failed", e);
        } finally {
            refreshing = false;
            drainPendingInvalidations();
        }
    }

    private Set<Key> collectWantedSections() {
        final Set<Key> result = new LinkedHashSet<>();
        final double radius = Math.max(0.0, config.worldCollisionRadius);
        final int maxSections = Math.max(1, config.worldCollisionMaxSections);
        final Vector3d gravity = world.gravity();

        Vector3d lookahead = null;
        if (config.worldCollisionLookahead > 0.0 && gravity.lengthSquared() > 1.0e-12) {
            lookahead = new Vector3d(gravity).normalize().mul(config.worldCollisionLookahead);
        }

        for (BodyHandle body : world.bodies()) {
            if (body.userData() == TerrainBody.INSTANCE) {
                continue;
            }
            final Aabb bounds = body.aabb();
            final Vector3d min = bounds.min().sub(radius, radius, radius);
            final Vector3d max = bounds.max().add(radius, radius, radius);

            if (lookahead != null) {
                // Extend the region from the body's centre along gravity. Do
                // not treat the lookahead vector as a world-space corner.
                final Vector3d target = bounds.center().add(lookahead);
                min.min(target);
                max.max(target);
            }
            if (!isFinite(min) || !isFinite(max)) {
                // An infinite/NaN bound (for example an unbounded plane)
                // cannot be mapped to sections without overflowing.
                continue;
            }

            final int minSectionX = TerrainSectionGeometry.sectionCoordinate(min.x);
            final int minSectionY = TerrainSectionGeometry.sectionCoordinate(min.y);
            final int minSectionZ = TerrainSectionGeometry.sectionCoordinate(min.z);
            final int maxSectionX = TerrainSectionGeometry.sectionCoordinate(max.x);
            final int maxSectionY = TerrainSectionGeometry.sectionCoordinate(max.y);
            final int maxSectionZ = TerrainSectionGeometry.sectionCoordinate(max.z);

            for (int x = minSectionX; x <= maxSectionX && result.size() < maxSections; x++) {
                for (int y = minSectionY; y <= maxSectionY && result.size() < maxSections; y++) {
                    for (int z = minSectionZ; z <= maxSectionZ && result.size() < maxSections; z++) {
                        result.add(new Key(x, y, z));
                    }
                }
            }
            if (result.size() >= maxSections) {
                break;
            }
        }
        return result;
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    /**
     * @return a cached entry, {@link #EMPTY} when the section is loaded but
     *         empty, or {@code null} when it cannot be built yet (for example
     *         an unloaded chunk) and should be retried on a later refresh
     */
    private Entry build(Key key) {
        final IntBounds3 bounds = key.bounds();
        if (bounds.maxY() <= level.getMinY() || bounds.minY() >= level.getMaxY()) {
            return EMPTY;
        }
        if (!isLoaded(key)) {
            return null;
        }

        final TerrainSectionMask mask = new TerrainSectionMask(bounds);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    pos.set(x, y, z);
                    final BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    // Full blocks take the cheap boolean path; non-full shapes
                    // still count as solid so stairs, slabs and fences remain
                    // collidable (approximated by their voxel cell).
                    final boolean solid = state.isCollisionShapeFullBlock(level, pos)
                            || !state.getCollisionShape(level, pos).isEmpty();
                    if (solid) {
                        mask.setSolid(x, y, z, true);
                    }
                }
            }
        }
        if (mask.isEmpty()) {
            return EMPTY;
        }

        final VoxelColliderData collider = VoxelColliderBuilder.build(mask, TERRAIN_OPTIONS);
        if (collider.isEmpty()) {
            return EMPTY;
        }

        final Vector3d bodyPosition = TerrainSectionGeometry.staticBodyPosition(bounds, collider);
        final BodyHandle body = world.createBody(BodyDef.staticBody(collider.toCompoundShape())
                .transform(Transform3d.of(bodyPosition))
                .filter(TerrainCollisionGroups.TERRAIN)
                .userData(TerrainBody.INSTANCE)
                .build());
        return new Entry(body);
    }

    private boolean isLoaded(Key key) {
        // A section spans exactly one chunk column horizontally. Only build
        // from fully loaded chunks: getBlockState on a missing or proto chunk
        // would synchronously load/generate it from the physics tick and could
        // cache air before worldgen finished.
        return level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ()) != null;
    }

    private void removeUnloadedSections() {
        final Iterator<Map.Entry<Key, Entry>> iterator = sections.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Key, Entry> cached = iterator.next();
            if (!isLoaded(cached.getKey())) {
                removeBody(cached.getValue());
                iterator.remove();
            }
        }
    }

    /**
     * Removes every terrain body and forgets cached sections. Call after
     * changes that affect how blocks map to collision, or when clearing test
     * content. Safe to call while a refresh is iterating: the request is
     * queued and applied at the end of the pass.
     */
    public void invalidateAll() {
        refreshRequested = true;
        if (refreshing) {
            clearPending = true;
            return;
        }
        clearEntries();
    }

    /**
     * Invalidates the section containing {@code pos}. When the block touches a
     * section boundary its neighbour may have a neighbour-dependent collision
     * shape (fences, walls, ...), so the adjacent section is invalidated too.
     */
    public void invalidateAt(BlockPos pos) {
        if (closed) {
            return;
        }
        final Set<Key> keys = affectedSections(pos);
        refreshRequested = true;
        if (refreshing) {
            deferredInvalidations.addAll(keys);
            return;
        }
        for (Key key : keys) {
            removeSection(key);
        }
    }

    private static Set<Key> affectedSections(BlockPos pos) {
        final int blockX = pos.getX();
        final int blockY = pos.getY();
        final int blockZ = pos.getZ();
        final int sectionX = Math.floorDiv(blockX, TerrainSectionGeometry.SECTION_SIZE);
        final int sectionY = Math.floorDiv(blockY, TerrainSectionGeometry.SECTION_SIZE);
        final int sectionZ = Math.floorDiv(blockZ, TerrainSectionGeometry.SECTION_SIZE);

        final int[] offsetsX = boundaryOffsets(blockX);
        final int[] offsetsY = boundaryOffsets(blockY);
        final int[] offsetsZ = boundaryOffsets(blockZ);

        final Set<Key> keys = new HashSet<>(offsetsX.length * offsetsY.length * offsetsZ.length);
        for (int dx : offsetsX) {
            for (int dy : offsetsY) {
                for (int dz : offsetsZ) {
                    keys.add(new Key(sectionX + dx, sectionY + dy, sectionZ + dz));
                }
            }
        }
        return keys;
    }

    private static int[] boundaryOffsets(int blockCoordinate) {
        final int local = TerrainSectionGeometry.localCoordinate(blockCoordinate);
        if (local == 0) {
            return NEGATIVE_NEIGHBOUR;
        }
        if (local == TerrainSectionGeometry.SECTION_SIZE - 1) {
            return POSITIVE_NEIGHBOUR;
        }
        return SAME_SECTION;
    }

    private void drainPendingInvalidations() {
        if (clearPending) {
            clearEntries();
            return;
        }
        if (deferredInvalidations.isEmpty()) {
            return;
        }
        final Set<Key> pending = new HashSet<>(deferredInvalidations);
        deferredInvalidations.clear();
        for (Key key : pending) {
            removeSection(key);
        }
    }

    private void removeSection(Key key) {
        failedSections.remove(key);
        final Entry entry = sections.remove(key);
        if (entry != null) {
            removeBody(entry);
        }
    }

    private void removeBody(Entry entry) {
        if (entry.body == null || world.closed()) {
            return;
        }
        try {
            world.removeBody(entry.body);
        } catch (RuntimeException e) {
            Crythst.LOGGER.debug("Could not remove terrain body", e);
        }
    }

    private void clearEntries() {
        if (sections.isEmpty()) {
            deferredInvalidations.clear();
            failedSections.clear();
            clearPending = false;
            return;
        }
        // Clear the map before touching Bullet: removing a body may notify
        // gameplay code, which must never observe a half-cleared iteration.
        final java.util.List<Entry> bodies = new java.util.ArrayList<>(sections.values());
        sections.clear();
        deferredInvalidations.clear();
        failedSections.clear();
        clearPending = false;
        for (Entry entry : bodies) {
            removeBody(entry);
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (refreshing) {
            // LevelPhysics closes the Bullet world right after this; avoid
            // mutating the map from inside a refresh iteration.
            clearPending = true;
            return;
        }
        clearEntries();
    }

    /**
     * @return number of sections that currently own a static body. Cached
     *         empty sections are not counted, preserving the historical
     *         meaning of this diagnostic.
     */
    public int sectionCount() {
        return bodySectionCount();
    }

    /**
     * @return number of cached section keys, including cached empty sections
     */
    public int cachedSectionCount() {
        return sections.size();
    }

    /**
     * @return number of cached sections that currently own a static body
     */
    public int bodySectionCount() {
        int count = 0;
        for (Entry entry : sections.values()) {
            if (entry.body != null) {
                count++;
            }
        }
        return count;
    }

    private record Entry(BodyHandle body) {
    }
}
