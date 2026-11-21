package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.collision.TerrainCollisionGroups;
import io.mikaple.crythst.integration.minecraft.collision.TerrainCollisionRegion;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionGeometry;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionGeometry.Key;
import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionVolume;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelCellPart;
import io.mikaple.crythst.physics.voxel.VoxelColliderBuilder;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.TerrainBody;
import io.mikaple.crythst.physics.world.TerrainCollisionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds static Bullet bodies from nearby Minecraft terrain sections.
 *
 * <p>Each 16x16x16 section is greedily merged into boxes and cached. Sections
 * are loaded around the <em>world AABB plus motion reach</em> of every dynamic
 * body, not merely a radius around its centre. The wanted region is recomputed
 * every server tick; a refresh happens as soon as a body reaches a new section,
 * so a fast or rotating structure never outruns its collision cache.</p>
 *
 * <p>Block edits mark sections dirty instead of deleting their body
 * immediately. A refresh builds the replacement first and only then removes the
 * old native body, so there is no one-tick hole through which a structure can
 * fall. Empty sections are cached too, so a row of sky sections is not rescanned
 * every refresh.</p>
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

    /**
     * Safety cap for the number of section bodies. The configured
     * {@code worldCollisionMaxSections} is treated as a soft target: a large
     * structure is allowed to exceed it so its current AABB is fully covered,
     * plus a small anticipation budget for the velocity-expanded region.
     * Beyond this limit, the nearest sections are used and a rate-limited
     * warning is emitted.
     */
    private static final int MAX_SECTIONS_HARD_LIMIT = 512;

    /** Per-body scan limit while collecting candidate sections. */
    private static final int SECTION_SCAN_LIMIT = 4096;

    /** Maximum motion reach per axis in blocks; protects against bad velocities. */
    private static final double MAX_MOTION_EXPANSION = 64.0;

    /** Minimum interval between "terrain does not cover this body" warnings. */
    private static final int WARNING_INTERVAL_TICKS = 200;

    private static final Entry EMPTY = new Entry(null, 0, List.of(), List.of());

    private final ServerLevel level;
    private final PhysicsWorld world;
    private final CrythstConfig config;

    private final Map<Key, Entry> sections = new HashMap<>();
    private final Set<Key> dirtySections = new HashSet<>();
    private final Set<Key> deferredInvalidations = new HashSet<>();
    private final Set<Key> failedSections = new HashSet<>();
    private final Map<Key, Long> retryTicks = new HashMap<>();
    private final Map<Long, Long> warningTicks = new HashMap<>();

    private Set<Key> wantedSections = Set.of();
    private List<RegionPlan> regionPlans = List.of();
    private volatile List<TerrainCollisionSnapshot.Section> sectionSnapshots = List.of();
    private volatile TerrainCollisionSnapshot snapshot;

    private boolean clearPending;
    private boolean refreshing;
    private boolean refreshRequested;
    private boolean closed;
    private int ticks;
    private int ticksSinceRefresh;
    private long refreshCount;
    private int lastRefreshTick = -1;
    private String lastRefreshReason = "never";

    public TerrainCollision(ServerLevel level, PhysicsWorld world, CrythstConfig config) {
        this.level = level;
        this.world = world;
        this.config = config;
        this.snapshot = new TerrainCollisionSnapshot(config.worldCollisionEnabled,
                0, 0, 0, 0, 0, 0, 0L, -1, "never", List.of(), List.of());
    }

    /**
     * Called once per server tick. Loading is checked every tick: new sections
     * are built before the next physics step, while the configured
     * {@code worldCollisionRefreshTicks} remains an upper bound for a
     * verification/retry pass.
     */
    public void tick() {
        tick(TerrainCollisionRegion.DEFAULT_TICK_SECONDS);
    }

    /**
     * @param secondsAhead motion horizon until the next physics advance; the
     *                     terrain region is expanded by velocity times this
     */
    public void tick(double secondsAhead) {
        if (closed) {
            return;
        }
        ticks++;
        if (!config.worldCollisionEnabled) {
            invalidateAll();
            return;
        }
        final double horizon = Double.isFinite(secondsAhead) && secondsAhead > 0.0
                ? secondsAhead : TerrainCollisionRegion.DEFAULT_TICK_SECONDS;
        final Set<Key> wanted = collectWantedSections(horizon);
        ticksSinceRefresh++;

        final boolean buildDue = hasDueBuild(wanted);
        final boolean invalidationPending = refreshRequested || !dirtySections.isEmpty();
        final int refreshTicks = Math.max(1, config.worldCollisionRefreshTicks);
        if (!TerrainCollisionRegion.needsRefresh(wanted, sections.keySet(),
                invalidationPending, buildDue, ticksSinceRefresh, refreshTicks)) {
            checkOutsideWarnings();
            publishSnapshot();
            return;
        }

        final String reason;
        if (invalidationPending) {
            reason = "invalidation";
        } else if (!sections.keySet().equals(wanted)) {
            reason = "region-changed";
        } else if (buildDue) {
            reason = "missing-sections";
        } else {
            reason = "periodic";
        }
        refresh(wanted, reason);
    }

    public void ensureNow() {
        ensureNow(TerrainCollisionRegion.DEFAULT_TICK_SECONDS);
    }

    /**
     * @param secondsAhead motion horizon used to expand the requested region;
     *                     use the total time a manual batch will simulate
     */
    public void ensureNow(double secondsAhead) {
        if (closed) {
            return;
        }
        if (!config.worldCollisionEnabled) {
            invalidateAll();
            return;
        }
        final double horizon = Double.isFinite(secondsAhead) && secondsAhead > 0.0
                ? secondsAhead : TerrainCollisionRegion.DEFAULT_TICK_SECONDS;
        final Set<Key> wanted = collectWantedSections(horizon);
        if (hasDueBuild(wanted) || !sections.keySet().equals(wanted) || !dirtySections.isEmpty()) {
            refresh(wanted, "ensure");
        } else {
            checkOutsideWarnings();
            publishSnapshot();
        }
    }

    private void refresh(Set<Key> wanted, String reason) {
        if (closed || refreshing) {
            // Reentrant refreshes are picked up by the next tick; block edits
            // made during this pass are queued through invalidateAt.
            refreshRequested = true;
            return;
        }
        refreshRequested = false;
        refreshing = true;
        ticksSinceRefresh = 0;
        lastRefreshTick = ticks;
        lastRefreshReason = reason;
        refreshCount++;
        try {
            // Build or replace wanted sections first. The old native body is
            // removed only after the replacement exists, so a block edit or a
            // newly entered section never leaves a simulation-visible gap.
            for (Key key : wanted) {
                final Entry existing = sections.get(key);
                final boolean dirty = dirtySections.contains(key);
                if (existing != null && !dirty) {
                    continue;
                }
                final long retryAt = retryTicks.getOrDefault(key, 0L);
                if (ticks < retryAt) {
                    continue;
                }

                final Entry replacement;
                try {
                    replacement = build(key);
                } catch (RuntimeException e) {
                    // One broken block state (for example from another mod)
                    // must not stop the remaining sections from loading. Warn
                    // once per section and retry after the refresh interval.
                    if (failedSections.add(key)) {
                        Crythst.LOGGER.warn("Could not build terrain section {}", key, e);
                    }
                    retryTicks.put(key, (long) ticks + Math.max(1, config.worldCollisionRefreshTicks));
                    continue;
                }
                if (replacement == null) {
                    // Chunk not fully loaded yet. Keep any existing body and
                    // retry on the next tick; do not create a hole.
                    retryTicks.remove(key);
                    continue;
                }

                failedSections.remove(key);
                retryTicks.remove(key);
                dirtySections.remove(key);
                sections.put(key, replacement);
                if (existing != null) {
                    removeBody(existing);
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
                    retryTicks.remove(cached.getKey());
                    dirtySections.remove(cached.getKey());
                }
            }
            failedSections.retainAll(wanted);
            retryTicks.keySet().retainAll(wanted);
            dirtySections.retainAll(wanted);
            wantedSections = Set.copyOf(wanted);
            rebuildSectionSnapshots();
        } catch (RuntimeException e) {
            Crythst.LOGGER.warn("Terrain collision refresh failed", e);
        } finally {
            refreshing = false;
            if (clearPending) {
                clearPending = false;
                clearEntries();
            } else if (!deferredInvalidations.isEmpty()) {
                dirtySections.addAll(deferredInvalidations);
                deferredInvalidations.clear();
                refreshRequested = true;
            }
            checkOutsideWarnings();
            publishSnapshot();
        }
    }

    private Set<Key> collectWantedSections(double secondsAhead) {
        final double radius = Math.max(0.0, config.worldCollisionRadius);
        final int configuredCap = Math.max(1, config.worldCollisionMaxSections);
        final Vector3d gravity = world.gravity();
        final Vector3d lookahead = config.worldCollisionLookahead > 0.0
                && gravity.lengthSquared() > 1.0e-12
                ? new Vector3d(gravity).normalize().mul(config.worldCollisionLookahead)
                : null;

        final List<BodyPlan> plans = new ArrayList<>();

        for (BodyHandle body : world.bodies()) {
            if (body.userData() == TerrainBody.INSTANCE) {
                continue;
            }
            final Aabb bounds;
            try {
                bounds = body.aabb();
            } catch (RuntimeException e) {
                continue;
            }
            if (!isFinite(bounds.min()) || !isFinite(bounds.max())) {
                // An infinite/NaN bound (for example an unbounded plane)
                // cannot be mapped to sections without overflowing.
                continue;
            }
            if (!isReasonable(bounds)) {
                // Far outside the Minecraft world border: section coordinates
                // would overflow int arithmetic. Such a body has no terrain
                // that could be usefully cached anyway.
                continue;
            }

            final Vector3d linearVelocity = safeVector(body::linearVelocity);
            final Vector3d angularVelocity = safeVector(body::angularVelocity);
            final Vector3d acceleration = accelerationOf(body);
            Aabb expanded = TerrainCollisionRegion.motionBounds(bounds,
                    linearVelocity, angularVelocity, acceleration,
                    secondsAhead, radius, MAX_MOTION_EXPANSION);

            if (lookahead != null) {
                // Extend the region from the body's centre along gravity. Do
                // not treat the lookahead vector as a world-space corner.
                final Vector3d target = bounds.center().add(lookahead);
                final Aabb targetBounds = new Aabb(target, target);
                expanded = expanded.union(targetBounds);
            }

            final List<Key> required = TerrainCollisionRegion.sectionsNearest(
                    bounds, bounds.center(), SECTION_SCAN_LIMIT);
            final List<Key> ordered = new ArrayList<>(required.size() + 16);
            final Set<Key> requiredSet = new LinkedHashSet<>(required);
            ordered.addAll(required);
            for (Key key : TerrainCollisionRegion.sectionsNearest(
                    expanded, bounds.center(), SECTION_SCAN_LIMIT)) {
                if (!requiredSet.contains(key)) {
                    ordered.add(key);
                }
            }
            final boolean requiredTruncated = TerrainCollisionRegion.sectionCount(bounds) > required.size();
            plans.add(new BodyPlan(body, bounds, expanded, ordered, requiredSet, requiredTruncated));
        }

        final int anticipationBudget = Math.max(8, Math.min(configuredCap, 32));

        final List<List<Key>> requiredByBody = new ArrayList<>(plans.size());
        final List<List<Key>> anticipatoryByBody = new ArrayList<>(plans.size());
        for (BodyPlan plan : plans) {
            requiredByBody.add(new ArrayList<>(plan.ordered.subList(0, plan.required.size())));
            anticipatoryByBody.add(new ArrayList<>(
                    plan.ordered.subList(plan.required.size(), plan.ordered.size())));
        }

        // Phase one covers the current AABB of every body (raising the
        // configured cap when necessary); phase two spends the remainder on
        // the velocity-expanded region, fairly across bodies.
        final TerrainCollisionRegion.RegionSelection selection =
                TerrainCollisionRegion.selectRegion(requiredByBody, anticipatoryByBody,
                        configuredCap, anticipationBudget, MAX_SECTIONS_HARD_LIMIT);
        final Set<Key> wanted = selection.sections();

        final List<RegionPlan> regions = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            final BodyPlan plan = plans.get(i);
            final Set<Key> planned = i < selection.plannedPerBody().size()
                    ? selection.plannedPerBody().get(i) : Set.of();
            regions.add(new RegionPlan(plan.body.id(), plan.bounds, plan.expanded,
                    plan.required, planned, plan.requiredTruncated));
        }
        regionPlans = List.copyOf(regions);
        wantedSections = Set.copyOf(wanted);
        return wanted;
    }

    private static Vector3d safeVector(java.util.function.Supplier<Vector3d> supplier) {
        try {
            final Vector3d value = supplier.get();
            return value == null ? new Vector3d() : value;
        } catch (RuntimeException e) {
            return new Vector3d();
        }
    }

    private Vector3d accelerationOf(BodyHandle body) {
        if (body.type() != BodyType.DYNAMIC) {
            return new Vector3d();
        }
        final Vector3d override = body.gravityOverride();
        if (override != null) {
            return override;
        }
        return new Vector3d(world.gravity()).mul(body.gravityScale());
    }

    private boolean hasDueBuild(Set<Key> wanted) {
        for (Key key : wanted) {
            if (!sections.containsKey(key) && ticks >= retryTicks.getOrDefault(key, 0L)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean isReasonable(Aabb bounds) {
        final double limit = 30_000_000.0;
        final Vector3d min = bounds.min();
        final Vector3d max = bounds.max();
        return Math.abs(min.x) <= limit && Math.abs(min.y) <= limit && Math.abs(min.z) <= limit
                && Math.abs(max.x) <= limit && Math.abs(max.y) <= limit && Math.abs(max.z) <= limit;
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

        final TerrainSectionVolume volume = new TerrainSectionVolume(bounds);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                    pos.set(x, y, z);
                    final BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    // Full cubes take the cheap merged path. Partial shapes
                    // (slabs, stairs, fences, ...) keep their exact boxes so the
                    // collision surface matches the visible geometry.
                    if (state.isCollisionShapeFullBlock(level, pos)) {
                        volume.setFullCube(x, y, z);
                        continue;
                    }
                    final VoxelShape shape = state.getCollisionShape(level, pos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    final List<VoxelCellPart> parts =
                            TerrainSectionVolume.partsFromShape(shape, x, y, z);
                    if (parts.isEmpty()) {
                        continue;
                    }
                    if (TerrainSectionVolume.isFullCube(parts, x, y, z)) {
                        volume.setFullCube(x, y, z);
                    } else {
                        volume.setSolid(x, y, z, parts);
                    }
                }
            }
        }
        if (volume.isEmpty()) {
            return EMPTY;
        }

        final VoxelColliderData collider = VoxelColliderBuilder.build(volume, TERRAIN_OPTIONS, volume);
        if (collider.isEmpty()) {
            return EMPTY;
        }

        final Vector3d bodyPosition = TerrainSectionGeometry.staticBodyPosition(bounds, collider);
        final BodyHandle body = world.createBody(BodyDef.staticBody(collider.toCompoundShape())
                .transform(Transform3d.of(bodyPosition))
                .filter(TerrainCollisionGroups.TERRAIN)
                .userData(TerrainBody.INSTANCE)
                .build());
        return new Entry(body,
                collider.boxes().size() + collider.customParts().size(),
                collider.boxes(), collider.customParts());
    }

    private boolean isLoaded(Key key) {
        // A section spans exactly one chunk column horizontally. Only build
        // from fully loaded chunks: getBlockState on a missing or proto chunk
        // would synchronously load/generate it from the physics tick and could
        // cache air before worldgen finished.
        return level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ()) != null;
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
     * Marks the section containing {@code pos} dirty. The old body remains
     * active until the replacement has been built on the physics thread, so a
     * block edit cannot open a one-tick hole. When the block touches a section
     * boundary its neighbour may have a neighbour-dependent collision shape
     * (fences, walls, ...), so the adjacent section is invalidated too.
     */
    public void invalidateAt(BlockPos pos) {
        if (closed) {
            return;
        }
        final Set<Key> keys = TerrainCollisionRegion.affectedSections(
                pos.getX(), pos.getY(), pos.getZ());
        refreshRequested = true;
        if (refreshing) {
            deferredInvalidations.addAll(keys);
            return;
        }
        dirtySections.addAll(keys);
        // A block edit must be reflected on the next tick, not after a retry
        // backoff caused by an earlier build failure.
        for (Key key : keys) {
            retryTicks.remove(key);
        }
    }

    private void checkOutsideWarnings() {
        for (RegionPlan plan : regionPlans) {
            final boolean outside = plan.requiredTruncated
                    || !sections.keySet().containsAll(plan.required)
                    || !TerrainCollisionRegion.covers(sections.keySet(), plan.bounds);
            if (!outside) {
                warningTicks.remove(plan.bodyId);
                continue;
            }
            final long lastWarning = warningTicks.getOrDefault(plan.bodyId, Long.MIN_VALUE / 2);
            if ((long) ticks - lastWarning < WARNING_INTERVAL_TICKS) {
                continue;
            }
            warningTicks.put(plan.bodyId, (long) ticks);
            Crythst.LOGGER.warn("Crythst terrain collision does not cover body #{}: "
                            + "AABB {} -> {}, required {} section(s), cached {} section(s), wanted {}",
                    plan.bodyId,
                    format(plan.bounds.min()), format(plan.bounds.max()),
                    plan.required.size(), sections.size(), wantedSections.size());
        }
        warningTicks.keySet().removeIf(id -> regionPlans.stream().noneMatch(plan -> plan.bodyId == id));
    }

    private void publishSnapshot() {
        if (closed) {
            return;
        }
        int bodySections = 0;
        int boxCount = 0;
        for (Entry entry : sections.values()) {
            if (entry.body != null) {
                bodySections++;
                boxCount += entry.boxCount;
            }
        }

        int missing = 0;
        final List<TerrainCollisionSnapshot.Region> regions = new ArrayList<>(regionPlans.size());
        for (RegionPlan plan : regionPlans) {
            int planned = 0;
            if (plan.requiredTruncated) {
                // The AABB spans more sections than the scan limit; report at
                // least one missing section so the count is not misleading.
                missing++;
            }
            for (Key key : plan.required) {
                if (!sections.containsKey(key)) {
                    missing++;
                }
            }
            for (Key key : plan.planned) {
                if (sections.containsKey(key)) {
                    planned++;
                }
            }
            final Aabb bounds = plan.bounds;
            final Aabb expanded = plan.expanded;
            final boolean outside = plan.requiredTruncated
                    || !sections.keySet().containsAll(plan.required)
                    || !TerrainCollisionRegion.covers(sections.keySet(), bounds);
            regions.add(new TerrainCollisionSnapshot.Region(
                    plan.bodyId,
                    bounds.min().x, bounds.min().y, bounds.min().z,
                    bounds.max().x, bounds.max().y, bounds.max().z,
                    expanded.min().x, expanded.min().y, expanded.min().z,
                    expanded.max().x, expanded.max().y, expanded.max().z,
                    plan.required.size(), planned, outside));
        }

        snapshot = new TerrainCollisionSnapshot(
                config.worldCollisionEnabled,
                sections.size(), bodySections, sections.size() - bodySections,
                wantedSections.size(), missing, boxCount,
                refreshCount, lastRefreshTick, lastRefreshReason,
                sectionSnapshots, regions);
    }

    private void rebuildSectionSnapshots() {
        final List<TerrainCollisionSnapshot.Section> snapshots = new ArrayList<>(sections.size());
        for (Map.Entry<Key, Entry> cached : sections.entrySet()) {
            final Key key = cached.getKey();
            final Entry entry = cached.getValue();
            final Aabb bounds = TerrainCollisionRegion.sectionBounds(key, 1.0);
            snapshots.add(new TerrainCollisionSnapshot.Section(
                    key.x(), key.y(), key.z(),
                    bounds.min().x, bounds.min().y, bounds.min().z,
                    bounds.max().x, bounds.max().y, bounds.max().z,
                    entry.body == null ? -1L : entry.body.id(),
                    entry.boxCount, entry.boxes, entry.parts));
        }
        snapshots.sort((left, right) -> {
            int byX = Integer.compare(left.x(), right.x());
            if (byX != 0) {
                return byX;
            }
            int byY = Integer.compare(left.y(), right.y());
            return byY != 0 ? byY : Integer.compare(left.z(), right.z());
        });
        sectionSnapshots = List.copyOf(snapshots);
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
        if (sections.isEmpty() && dirtySections.isEmpty() && deferredInvalidations.isEmpty()
                && regionPlans.isEmpty() && wantedSections.isEmpty()) {
            clearPending = false;
            publishSnapshot();
            return;
        }
        // Clear the map before touching Bullet: removing a body may notify
        // gameplay code, which must never observe a half-cleared iteration.
        final List<Entry> bodies = new ArrayList<>(sections.values());
        sections.clear();
        dirtySections.clear();
        deferredInvalidations.clear();
        failedSections.clear();
        retryTicks.clear();
        warningTicks.clear();
        wantedSections = Set.of();
        regionPlans = List.of();
        sectionSnapshots = List.of();
        clearPending = false;
        for (Entry entry : bodies) {
            removeBody(entry);
        }
        publishSnapshot();
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

    /**
     * Immutable diagnostics for a debug renderer or command. Safe to call from
     * any thread; the snapshot is replaced by the server thread.
     */
    public TerrainCollisionSnapshot snapshot() {
        return snapshot;
    }

    private static String format(Vector3d vector) {
        return String.format("%.2f,%.2f,%.2f", vector.x, vector.y, vector.z);
    }

    private record Entry(BodyHandle body, int boxCount, List<VoxelBox> boxes,
                         List<VoxelCellPart> parts) {
    }

    private record BodyPlan(BodyHandle body, Aabb bounds, Aabb expanded,
                            List<Key> ordered, Set<Key> required,
                            boolean requiredTruncated) {
    }

    private record RegionPlan(long bodyId, Aabb bounds, Aabb expanded,
                              Set<Key> required, Set<Key> planned,
                              boolean requiredTruncated) {
    }
}
