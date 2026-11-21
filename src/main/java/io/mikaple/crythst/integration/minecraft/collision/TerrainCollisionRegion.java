package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.math.Aabb;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure geometry and refresh policy for terrain collision.
 *
 * <p>This class deliberately has no Minecraft imports so the region arithmetic
 * can be tested on a plain JVM. It answers the questions that drive terrain
 * caching:</p>
 *
 * <ul>
 *   <li>which section keys does a world-space AABB overlap,</li>
 *   <li>how far must that AABB be expanded for the body's velocity, angular
 *       motion, acceleration and a safety margin,</li>
 *   <li>which sections should be built first when a cap is reached, and</li>
 *   <li>whether the cached set is stale enough to require a refresh.</li>
 * </ul>
 *
 * <p>Section coordinates follow Minecraft's convention; see
 * {@link TerrainSectionGeometry}.</p>
 */
public final class TerrainCollisionRegion {
    /** Nominal server tick length used when no explicit horizon is supplied. */
    public static final double DEFAULT_TICK_SECONDS = 1.0 / 20.0;

    private TerrainCollisionRegion() {
    }

    /**
     * Expands an AABB by the distance the body can travel during
     * {@code seconds}, plus a static safety margin.
     *
     * <p>The reach is computed per axis from the absolute linear velocity and
     * acceleration. Rotational motion is accounted for by adding the angular
     * speed times the body's bounding-sphere radius on every axis. The result
     * is capped to {@code maxExpansion} blocks per axis when positive so a
     * corrupted velocity cannot create an unbounded section scan.</p>
     *
     * @param bounds          current world-space bounds
     * @param linearVelocity  world-space linear velocity in m/s, may be null
     * @param angularVelocity world-space angular velocity in rad/s, may be null
     * @param acceleration    world-space acceleration in m/s² (gravity), may be null
     * @param seconds         motion horizon in seconds
     * @param margin          extra static margin in blocks
     * @param maxExpansion    hard cap per axis in blocks, non-positive disables
     */
    public static Aabb motionBounds(Aabb bounds, Vector3dc linearVelocity,
                                    Vector3dc angularVelocity, Vector3dc acceleration,
                                    double seconds, double margin, double maxExpansion) {
        final double time = finitePositive(seconds) ? seconds : 0.0;
        final double extraMargin = finiteNonNegative(margin);
        final double bodyRadius = boundingRadius(bounds);

        final double angularSpeed = vectorLength(angularVelocity);
        final double angularReach = finite(angularSpeed) ? angularSpeed * time * bodyRadius : 0.0;

        final double[] reach = new double[3];
        reach[0] = axisReach(linearVelocity, acceleration, time, 0, angularReach, extraMargin);
        reach[1] = axisReach(linearVelocity, acceleration, time, 1, angularReach, extraMargin);
        reach[2] = axisReach(linearVelocity, acceleration, time, 2, angularReach, extraMargin);

        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(reach[axis])) {
                reach[axis] = 0.0;
            }
            if (maxExpansion > 0.0) {
                reach[axis] = Math.min(reach[axis], maxExpansion);
            }
        }

        final Vector3d min = bounds.min();
        final Vector3d max = bounds.max();
        return new Aabb(
                min.sub(reach[0], reach[1], reach[2]),
                max.add(reach[0], reach[1], reach[2]));
    }

    private static double axisReach(Vector3dc velocity, Vector3dc acceleration,
                                    double seconds, int axis, double angularReach,
                                    double margin) {
        final double linear = Math.abs(component(velocity, axis)) * seconds;
        final double accelerated = 0.5 * Math.abs(component(acceleration, axis)) * seconds * seconds;
        return linear + accelerated + angularReach + margin;
    }

    private static double component(Vector3dc vector, int axis) {
        if (vector == null) {
            return 0.0;
        }
        return switch (axis) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            default -> vector.z();
        };
    }

    private static double vectorLength(Vector3dc vector) {
        if (vector == null) {
            return 0.0;
        }
        return vector.length();
    }

    private static double boundingRadius(Aabb bounds) {
        final Vector3d size = bounds.size();
        return 0.5 * Math.sqrt(size.x * size.x + size.y * size.y + size.z * size.z);
    }

    /**
     * All sections that overlap {@code region}, nearest first.
     *
     * <p>{@code priority} is used to order the result: the section whose centre
     * is nearest to the priority point comes first. When the region spans more
     * than {@code limit} sections only the nearest shells around the priority
     * section are returned, so a corrupt or enormous AABB cannot allocate an
     * unbounded list.</p>
     *
     * @param region   world-space region to cover
     * @param priority world-space point that should be covered first
     * @param limit    maximum number of keys to return
     */
    public static List<TerrainSectionGeometry.Key> sectionsNearest(
            Aabb region, Vector3dc priority, int limit) {
        if (region == null || limit <= 0) {
            return List.of();
        }
        final Vector3d min = region.min();
        final Vector3d max = region.max();
        if (!finite(min) || !finite(max)) {
            return List.of();
        }

        final int minX = TerrainSectionGeometry.sectionCoordinate(min.x);
        final int minY = TerrainSectionGeometry.sectionCoordinate(min.y);
        final int minZ = TerrainSectionGeometry.sectionCoordinate(min.z);
        final int maxX = TerrainSectionGeometry.sectionCoordinate(max.x);
        final int maxY = TerrainSectionGeometry.sectionCoordinate(max.y);
        final int maxZ = TerrainSectionGeometry.sectionCoordinate(max.z);

        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return List.of();
        }

        final long spanX = (long) maxX - minX + 1;
        final long spanY = (long) maxY - minY + 1;
        final long spanZ = (long) maxZ - minZ + 1;
        final long total = spanX * spanY * spanZ;
        if (total <= 0L) {
            return List.of();
        }

        final Vector3d anchor = priority == null ? region.center() : new Vector3d(priority);
        if (!finite(anchor)) {
            return List.of();
        }

        if (total <= limit) {
            final List<TerrainSectionGeometry.Key> all = new ArrayList<>((int) total);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        all.add(new TerrainSectionGeometry.Key(x, y, z));
                    }
                }
            }
            all.sort((left, right) -> compareDistance(left, right, anchor));
            return all;
        }

        // Huge region: walk Chebyshev shells around the priority section so the
        // returned keys are the nearest ones instead of an arbitrary corner.
        final TerrainSectionGeometry.Key center =
                TerrainSectionGeometry.Key.containingFloor(anchor.x, anchor.y, anchor.z);
        final int maxRadius = Math.max(
                Math.max(Math.abs(center.x() - minX), Math.abs(maxX - center.x())),
                Math.max(Math.max(Math.abs(center.y() - minY), Math.abs(maxY - center.y())),
                        Math.max(Math.abs(center.z() - minZ), Math.abs(maxZ - center.z()))));

        final List<TerrainSectionGeometry.Key> result = new ArrayList<>(Math.min(limit, 4096));
        for (int radius = 0; radius <= maxRadius && result.size() < limit; radius++) {
            for (int x = center.x() - radius; x <= center.x() + radius && result.size() < limit; x++) {
                if (x < minX || x > maxX) {
                    continue;
                }
                for (int y = center.y() - radius; y <= center.y() + radius && result.size() < limit; y++) {
                    if (y < minY || y > maxY) {
                        continue;
                    }
                    for (int z = center.z() - radius; z <= center.z() + radius && result.size() < limit; z++) {
                        if (z < minZ || z > maxZ) {
                            continue;
                        }
                        final int shell = Math.max(Math.abs(x - center.x()),
                                Math.max(Math.abs(y - center.y()), Math.abs(z - center.z())));
                        if (shell == radius) {
                            result.add(new TerrainSectionGeometry.Key(x, y, z));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * All sections overlapping {@code region}, nearest first relative to the
     * region's own centre.
     */
    public static List<TerrainSectionGeometry.Key> sections(Aabb region, int limit) {
        return sectionsNearest(region, region == null ? null : region.center(), limit);
    }

    /**
     * Number of sections overlapping {@code region}, saturated at
     * {@link Long#MAX_VALUE}. Used to detect a region larger than the scan
     * limit without iterating it.
     */
    public static long sectionCount(Aabb region) {
        if (region == null) {
            return 0L;
        }
        final Vector3d min = region.min();
        final Vector3d max = region.max();
        if (!finite(min) || !finite(max)) {
            return Long.MAX_VALUE;
        }
        if (Math.abs(min.x) > 30_000_000.0 || Math.abs(min.y) > 30_000_000.0
                || Math.abs(min.z) > 30_000_000.0 || Math.abs(max.x) > 30_000_000.0
                || Math.abs(max.y) > 30_000_000.0 || Math.abs(max.z) > 30_000_000.0) {
            return Long.MAX_VALUE;
        }
        final long spanX = (long) TerrainSectionGeometry.sectionCoordinate(max.x)
                - TerrainSectionGeometry.sectionCoordinate(min.x) + 1L;
        final long spanY = (long) TerrainSectionGeometry.sectionCoordinate(max.y)
                - TerrainSectionGeometry.sectionCoordinate(min.y) + 1L;
        final long spanZ = (long) TerrainSectionGeometry.sectionCoordinate(max.z)
                - TerrainSectionGeometry.sectionCoordinate(min.z) + 1L;
        if (spanX <= 0L || spanY <= 0L || spanZ <= 0L) {
            return 0L;
        }
        final long maxValue = Long.MAX_VALUE;
        if (spanX > maxValue / spanY) {
            return maxValue;
        }
        final long xy = spanX * spanY;
        return xy > maxValue / spanZ ? maxValue : xy * spanZ;
    }

    /**
     * True when every section overlapping {@code region} is present in
     * {@code cached}.
     */
    public static boolean covers(Collection<TerrainSectionGeometry.Key> cached, Aabb region) {
        if (region == null) {
            return true;
        }
        final Vector3d min = region.min();
        final Vector3d max = region.max();
        if (!finite(min) || !finite(max)) {
            return false;
        }
        final int minX = TerrainSectionGeometry.sectionCoordinate(min.x);
        final int minY = TerrainSectionGeometry.sectionCoordinate(min.y);
        final int minZ = TerrainSectionGeometry.sectionCoordinate(min.z);
        final int maxX = TerrainSectionGeometry.sectionCoordinate(max.x);
        final int maxY = TerrainSectionGeometry.sectionCoordinate(max.y);
        final int maxZ = TerrainSectionGeometry.sectionCoordinate(max.z);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!cached.contains(new TerrainSectionGeometry.Key(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Refresh policy for the terrain cache.
     *
     * @param wanted              sections the current body regions require
     * @param cached              sections currently stored (including empty ones)
     * @param invalidationPending a block edit or explicit invalidation is queued
     * @param buildDue            a wanted section is missing and may be retried now
     * @param ticksSinceRefresh   ticks elapsed since the last refresh
     * @param intervalTicks       maximum age of the cache before a verification pass
     */
    public static boolean needsRefresh(Set<TerrainSectionGeometry.Key> wanted,
                                       Set<TerrainSectionGeometry.Key> cached,
                                       boolean invalidationPending,
                                       boolean buildDue,
                                       int ticksSinceRefresh,
                                       int intervalTicks) {
        if (invalidationPending || buildDue) {
            return true;
        }
        if (ticksSinceRefresh >= Math.max(1, intervalTicks)) {
            return true;
        }
        return !cached.equals(wanted);
    }

    /**
     * Sections affected by editing the block at {@code (blockX, blockY,
     * blockZ)}. A block on a section boundary can change the collision shape of
     * a neighbour (fences, walls, stairs), so the adjacent section is included
     * as well.
     */
    public static Set<TerrainSectionGeometry.Key> affectedSections(
            int blockX, int blockY, int blockZ) {
        final int sectionX = Math.floorDiv(blockX, TerrainSectionGeometry.SECTION_SIZE);
        final int sectionY = Math.floorDiv(blockY, TerrainSectionGeometry.SECTION_SIZE);
        final int sectionZ = Math.floorDiv(blockZ, TerrainSectionGeometry.SECTION_SIZE);

        final int[] offsetsX = boundaryOffsets(blockX);
        final int[] offsetsY = boundaryOffsets(blockY);
        final int[] offsetsZ = boundaryOffsets(blockZ);

        final Set<TerrainSectionGeometry.Key> keys =
                new HashSet<>(offsetsX.length * offsetsY.length * offsetsZ.length);
        for (int dx : offsetsX) {
            for (int dy : offsetsY) {
                for (int dz : offsetsZ) {
                    keys.add(new TerrainSectionGeometry.Key(
                            sectionX + dx, sectionY + dy, sectionZ + dz));
                }
            }
        }
        return keys;
    }

    private static int[] boundaryOffsets(int blockCoordinate) {
        final int local = TerrainSectionGeometry.localCoordinate(blockCoordinate);
        if (local == 0) {
            return new int[]{-1, 0};
        }
        if (local == TerrainSectionGeometry.SECTION_SIZE - 1) {
            return new int[]{0, 1};
        }
        return new int[]{0};
    }

    /**
     * Two-phase region selection used by the terrain cache.
     *
     * <p>Phase one covers every section intersecting the current AABB. These
     * are non-negotiable and may raise the configured cap. Phase two spends the
     * remaining budget on velocity-anticipatory sections, fairly across bodies.
     * When even the current AABBs exceed {@code hardCap}, the nearest required
     * sections are distributed fairly and the remainder is left for the
     * outside-terrain warning.</p>
     *
     * @param requiredByBody     current-AABB sections per body, nearest first
     * @param anticipatoryByBody motion-expanded sections excluding required ones
     * @param configuredCap      soft configured budget
     * @param anticipationBudget sections to add beyond the required union
     * @param hardCap            absolute maximum number of sections
     */
    public static RegionSelection selectRegion(
            List<List<TerrainSectionGeometry.Key>> requiredByBody,
            List<List<TerrainSectionGeometry.Key>> anticipatoryByBody,
            int configuredCap, int anticipationBudget, int hardCap) {
        if (requiredByBody == null || requiredByBody.isEmpty() || hardCap <= 0) {
            return new RegionSelection(Set.of(), List.of());
        }
        final List<List<TerrainSectionGeometry.Key>> anticipatory =
                anticipatoryByBody == null ? List.of() : anticipatoryByBody;

        final Set<TerrainSectionGeometry.Key> requiredUnion =
                new LinkedHashSet<>();
        for (List<TerrainSectionGeometry.Key> required : requiredByBody) {
            if (required != null) {
                requiredUnion.addAll(required);
            }
        }

        final List<Set<TerrainSectionGeometry.Key>> planned = new ArrayList<>(requiredByBody.size());
        for (int i = 0; i < requiredByBody.size(); i++) {
            planned.add(new LinkedHashSet<>());
        }

        final Set<TerrainSectionGeometry.Key> wanted = new LinkedHashSet<>();
        if (requiredUnion.size() <= hardCap) {
            wanted.addAll(requiredUnion);
            for (int i = 0; i < requiredByBody.size(); i++) {
                final List<TerrainSectionGeometry.Key> required = requiredByBody.get(i);
                if (required != null) {
                    planned.get(i).addAll(required);
                }
            }

            final int effectiveCap = Math.min(hardCap,
                    Math.max(Math.max(1, configuredCap),
                            requiredUnion.size() + Math.max(0, anticipationBudget)));
            final int remaining = effectiveCap - wanted.size();
            if (remaining > 0) {
                final FairSelection extra = selectFair(anticipatory, remaining);
                wanted.addAll(extra.sections());
                for (int i = 0; i < planned.size() && i < extra.perBody().size(); i++) {
                    planned.get(i).addAll(extra.perBody().get(i));
                }
            }
        } else {
            final FairSelection base = selectFair(requiredByBody, hardCap);
            wanted.addAll(base.sections());
            for (int i = 0; i < planned.size() && i < base.perBody().size(); i++) {
                planned.get(i).addAll(base.perBody().get(i));
            }
        }
        return new RegionSelection(wanted, planned);
    }

    /** Result of {@link #selectRegion(List, List, int, int, int)}. */
    public record RegionSelection(Set<TerrainSectionGeometry.Key> sections,
                                  List<Set<TerrainSectionGeometry.Key>> plannedPerBody) {
        public RegionSelection {
            sections = Set.copyOf(sections);
            final List<Set<TerrainSectionGeometry.Key>> copy = new ArrayList<>(plannedPerBody.size());
            for (Set<TerrainSectionGeometry.Key> body : plannedPerBody) {
                copy.add(Set.copyOf(body));
            }
            plannedPerBody = List.copyOf(copy);
        }
    }

    /**
     * Fairly selects at most {@code limit} sections from per-body ordered lists.
     *
     * <p>Each body contributes its next nearest section in a round-robin, so a
     * large structure cannot consume the whole budget before a second body is
     * considered. Duplicate keys are counted once but still recorded for every
     * body that requested them.</p>
     */
    public static FairSelection selectFair(List<List<TerrainSectionGeometry.Key>> orderedByBody,
                                           int limit) {
        if (orderedByBody == null || orderedByBody.isEmpty() || limit <= 0) {
            return new FairSelection(Set.of(), List.of());
        }
        final Set<TerrainSectionGeometry.Key> selected = new LinkedHashSet<>();
        final List<Set<TerrainSectionGeometry.Key>> perBody = new ArrayList<>(orderedByBody.size());
        for (int i = 0; i < orderedByBody.size(); i++) {
            perBody.add(new LinkedHashSet<>());
        }

        int index = 0;
        boolean progressed = true;
        while (progressed && selected.size() < limit) {
            progressed = false;
            for (int body = 0; body < orderedByBody.size(); body++) {
                final List<TerrainSectionGeometry.Key> ordered = orderedByBody.get(body);
                if (ordered == null || index >= ordered.size()) {
                    continue;
                }
                final TerrainSectionGeometry.Key key = ordered.get(index);
                if (selected.add(key)) {
                    perBody.get(body).add(key);
                }
                progressed = true;
                if (selected.size() >= limit) {
                    break;
                }
            }
            index++;
        }
        return new FairSelection(selected, perBody);
    }

    /**
     * Result of {@link #selectFair(List, int)}.
     *
     * @param sections union of selected keys
     * @param perBody  selected keys for each input list, in the same order
     */
    public record FairSelection(Set<TerrainSectionGeometry.Key> sections,
                                List<Set<TerrainSectionGeometry.Key>> perBody) {
        public FairSelection {
            sections = Set.copyOf(sections);
            final List<Set<TerrainSectionGeometry.Key>> copy = new ArrayList<>(perBody.size());
            for (Set<TerrainSectionGeometry.Key> body : perBody) {
                copy.add(Set.copyOf(body));
            }
            perBody = List.copyOf(copy);
        }
    }

    /** World-space bounds of a section's 16x16x16 block cube. */
    public static Aabb sectionBounds(TerrainSectionGeometry.Key key, double blockScale) {
        final double scale = blockScale > 0.0 && Double.isFinite(blockScale) ? blockScale : 1.0;
        final double size = TerrainSectionGeometry.SECTION_SIZE * scale;
        final Vector3d min = new Vector3d(key.originX(), key.originY(), key.originZ()).mul(scale);
        final Vector3d max = new Vector3d(min).add(size, size, size);
        return new Aabb(min, max);
    }

    private static int compareDistance(TerrainSectionGeometry.Key left,
                                       TerrainSectionGeometry.Key right,
                                       Vector3dc anchor) {
        final double leftDistance = distanceSquaredToSection(left, anchor);
        final double rightDistance = distanceSquaredToSection(right, anchor);
        final int byDistance = Double.compare(leftDistance, rightDistance);
        if (byDistance != 0) {
            return byDistance;
        }
        final int byX = Integer.compare(left.x(), right.x());
        if (byX != 0) {
            return byX;
        }
        final int byY = Integer.compare(left.y(), right.y());
        return byY != 0 ? byY : Integer.compare(left.z(), right.z());
    }

    private static double distanceSquaredToSection(TerrainSectionGeometry.Key key, Vector3dc point) {
        final double centerX = key.originX() + TerrainSectionGeometry.SECTION_SIZE * 0.5;
        final double centerY = key.originY() + TerrainSectionGeometry.SECTION_SIZE * 0.5;
        final double centerZ = key.originZ() + TerrainSectionGeometry.SECTION_SIZE * 0.5;
        final double dx = point.x() - centerX;
        final double dy = point.y() - centerY;
        final double dz = point.z() - centerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean finite(Vector3dc vector) {
        return vector != null && finite(vector.x()) && finite(vector.y()) && finite(vector.z());
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
