package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.integration.minecraft.collision.TerrainSectionGeometry.Key;
import io.mikaple.crythst.math.Aabb;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainCollisionRegionTest {
    @Test
    void smallAabbMapsToSingleSectionWithFloorSemantics() {
        assertEquals(Set.of(new Key(0, 0, 0)),
                Set.copyOf(TerrainCollisionRegion.sections(box(0.2, 0.2, 0.2, 0.8, 0.8, 0.8), 64)));
        assertEquals(Set.of(new Key(-1, -1, -1)),
                Set.copyOf(TerrainCollisionRegion.sections(box(-0.8, -0.8, -0.8, -0.2, -0.2, -0.2), 64)));
    }

    @Test
    void aabbAcrossSectionBoundariesCoversAllEightSections() {
        final List<Key> keys = TerrainCollisionRegion.sections(box(-1, -1, -1, 1, 1, 1), 64);
        assertEquals(8, keys.size());
        assertTrue(keys.containsAll(Set.of(
                new Key(-1, -1, -1), new Key(0, -1, -1),
                new Key(-1, 0, -1), new Key(-1, -1, 0),
                new Key(0, 0, -1), new Key(0, -1, 0),
                new Key(-1, 0, 0), new Key(0, 0, 0))));
    }

    @Test
    void velocityExpandsRegionIntoTheNextSection() {
        final Aabb bounds = box(10, 0, 10, 11, 1, 11);
        final Aabb expanded = TerrainCollisionRegion.motionBounds(bounds,
                new Vector3d(100, 0, 0), null, null, 0.05, 0.0, 64.0);
        assertEquals(16.0, expanded.max().x, 1.0e-9);
        assertTrue(TerrainCollisionRegion.sections(expanded, 64)
                        .contains(new Key(1, 0, 0)),
                "the east neighbour section must be requested before the next step");
        assertFalse(TerrainCollisionRegion.sections(bounds, 64)
                        .contains(new Key(1, 0, 0)),
                "without velocity the body stays inside section 0");
    }

    @Test
    void angularVelocityAddsBoundingSphereReach() {
        final Aabb bounds = box(-1, -1, -1, 1, 1, 1);
        final Aabb expanded = TerrainCollisionRegion.motionBounds(bounds,
                new Vector3d(), new Vector3d(0, 10, 0), null, 0.05, 0.0, 64.0);
        // radius = sqrt(3), reach = 10 * 0.05 * sqrt(3) = 0.866...
        final double expected = 1.0 + 10.0 * 0.05 * Math.sqrt(3.0);
        assertEquals(expected, expanded.max().x, 1.0e-9);
        assertEquals(expected, expanded.max().y, 1.0e-9);
        assertEquals(expected, expanded.max().z, 1.0e-9);
    }

    @Test
    void gravityAccelerationAddsHalfATickSquared() {
        final Aabb bounds = box(0, 0, 0, 1, 1, 1);
        final Aabb expanded = TerrainCollisionRegion.motionBounds(bounds,
                new Vector3d(), null, new Vector3d(0, -30, 0), 0.05, 0.0, 64.0);
        assertEquals(-0.5 * 30.0 * 0.05 * 0.05, expanded.min().y, 1.0e-9);
        assertEquals(1.0 + 0.5 * 30.0 * 0.05 * 0.05, expanded.max().y, 1.0e-9);
    }

    @Test
    void motionExpansionIsCapped() {
        final Aabb bounds = box(0, 0, 0, 1, 1, 1);
        final Aabb expanded = TerrainCollisionRegion.motionBounds(bounds,
                new Vector3d(1.0e9, -1.0e9, 1.0e9), null, null, 1.0, 8.0, 64.0);
        assertEquals(-64.0, expanded.min().x, 1.0e-9);
        assertEquals(65.0, expanded.max().x, 1.0e-9);
    }

    @Test
    void sectionOrderingUsesPriorityAndHonoursTheLimit() {
        final Aabb region = box(-10_000, -10_000, -10_000, 10_000, 10_000, 10_000);
        final List<Key> keys = TerrainCollisionRegion.sectionsNearest(
                region, new Vector3d(0.5, 0.5, 0.5), 40);
        assertEquals(40, keys.size());
        assertEquals(new Key(0, 0, 0), keys.get(0));
        assertTrue(keys.contains(new Key(-1, 0, 0)));
        assertTrue(keys.contains(new Key(1, 0, 0)));
    }

    @Test
    void refreshPolicyDetectsRegionChangesInvalidationAndAge() {
        final Set<Key> first = Set.of(new Key(0, 0, 0), new Key(1, 0, 0));
        final Set<Key> second = Set.of(new Key(0, 0, 0), new Key(1, 0, 0), new Key(2, 0, 0));

        assertFalse(TerrainCollisionRegion.needsRefresh(first, first, false, false, 3, 10));
        assertTrue(TerrainCollisionRegion.needsRefresh(second, first, false, false, 3, 10),
                "entering a new section must refresh immediately");
        assertTrue(TerrainCollisionRegion.needsRefresh(first, first, true, false, 3, 10),
                "a queued block invalidation must refresh");
        assertTrue(TerrainCollisionRegion.needsRefresh(first, first, false, true, 3, 10),
                "a missing section must be built");
        assertTrue(TerrainCollisionRegion.needsRefresh(first, first, false, false, 10, 10),
                "the periodic verification pass must run");
    }

    @Test
    void affectedSectionsIncludesNeighbourAcrossNegativeBoundary() {
        final Set<Key> atPositiveCorner = TerrainCollisionRegion.affectedSections(-1, -1, -1);
        assertEquals(8, atPositiveCorner.size());
        assertTrue(atPositiveCorner.contains(new Key(-1, -1, -1)));
        assertTrue(atPositiveCorner.contains(new Key(0, 0, 0)));

        final Set<Key> atNegativeXFace = TerrainCollisionRegion.affectedSections(-16, 5, 5);
        assertEquals(Set.of(new Key(-2, 0, 0), new Key(-1, 0, 0)), atNegativeXFace);

        final Set<Key> interior = TerrainCollisionRegion.affectedSections(5, 5, 5);
        assertEquals(Set.of(new Key(0, 0, 0)), interior);
    }

    @Test
    void fairSelectionGivesEveryBodyItsNearestSections() {
        final List<List<Key>> perBody = List.of(
                List.of(new Key(0, 0, 0), new Key(1, 0, 0), new Key(2, 0, 0), new Key(3, 0, 0)),
                List.of(new Key(10, 0, 0), new Key(11, 0, 0), new Key(12, 0, 0), new Key(13, 0, 0)),
                List.of(new Key(20, 0, 0), new Key(21, 0, 0), new Key(22, 0, 0), new Key(23, 0, 0)));

        final TerrainCollisionRegion.FairSelection selection =
                TerrainCollisionRegion.selectFair(perBody, 6);

        assertEquals(6, selection.sections().size());
        // The nearest section of every body is selected before any second one.
        assertTrue(selection.sections().containsAll(Set.of(
                new Key(0, 0, 0), new Key(10, 0, 0), new Key(20, 0, 0))));
        for (Set<Key> body : selection.perBody()) {
            assertEquals(2, body.size());
        }
    }

    @Test
    void fairSelectionLetsALargeBodyUseItsFullAabbBudget() {
        final List<Key> large = new java.util.ArrayList<>();
        for (int x = 0; x < 100; x++) {
            large.add(new Key(x, 0, 0));
        }
        final TerrainCollisionRegion.FairSelection selection =
                TerrainCollisionRegion.selectFair(List.of(large), 128);
        assertEquals(100, selection.sections().size(),
                "a single large body must not be truncated to the old 64-section cap");
    }

    @Test
    void regionSelectionNeverStarvesRequiredSectionsOfALargeBody() {
        final List<Key> largeRequired = new java.util.ArrayList<>();
        for (int x = 0; x < 100; x++) {
            largeRequired.add(new Key(x, 0, 0));
        }
        final List<Key> smallRequired = List.of(new Key(1000, 0, 0));
        final List<Key> smallAnticipatory = new java.util.ArrayList<>();
        for (int x = 2000; x < 2100; x++) {
            smallAnticipatory.add(new Key(x, 0, 0));
        }

        final TerrainCollisionRegion.RegionSelection selection =
                TerrainCollisionRegion.selectRegion(
                        List.of(largeRequired, smallRequired),
                        List.of(List.of(), smallAnticipatory),
                        64, 32, 512);

        assertTrue(selection.sections().containsAll(largeRequired),
                "all current-AABB sections of a large body must be selected");
        assertTrue(selection.sections().containsAll(smallRequired));
        assertTrue(selection.plannedPerBody().get(0).containsAll(largeRequired));
        assertEquals(133, selection.sections().size(),
                "101 required + 32 anticipatory sections");
    }

    @Test
    void regionSelectionFallsBackToFairRequiredSectionsAboveTheHardCap() {
        final List<Key> left = new java.util.ArrayList<>();
        final List<Key> right = new java.util.ArrayList<>();
        for (int x = 0; x < 400; x++) {
            left.add(new Key(x, 0, 0));
            right.add(new Key(x, 1, 0));
        }
        final TerrainCollisionRegion.RegionSelection selection =
                TerrainCollisionRegion.selectRegion(List.of(left, right), List.of(List.of(), List.of()),
                        64, 32, 512);
        assertEquals(512, selection.sections().size());
        assertTrue(selection.plannedPerBody().get(0).size() > 0);
        assertTrue(selection.plannedPerBody().get(1).size() > 0);
    }

    @Test
    void sectionCountDetectsRegionsLargerThanTheScanLimit() {
        assertEquals(1, TerrainCollisionRegion.sectionCount(box(1, 1, 1, 2, 2, 2)));
        assertEquals(8, TerrainCollisionRegion.sectionCount(box(-1, -1, -1, 1, 1, 1)));
        assertTrue(TerrainCollisionRegion.sectionCount(
                        box(-10_000, -10_000, -10_000, 10_000, 10_000, 10_000))
                > 4096L);
        assertEquals(Long.MAX_VALUE, TerrainCollisionRegion.sectionCount(
                box(Double.NaN, 0, 0, 1, 1, 1)));
    }

    @Test
    void coverageRequiresEveryOverlappingSection() {
        final Aabb bounds = box(-1, -1, -1, 1, 1, 1);
        assertFalse(TerrainCollisionRegion.covers(Set.of(new Key(0, 0, 0)), bounds));
        final Set<Key> complete = Set.of(
                new Key(-1, -1, -1), new Key(0, -1, -1),
                new Key(-1, 0, -1), new Key(-1, -1, 0),
                new Key(0, 0, -1), new Key(0, -1, 0),
                new Key(-1, 0, 0), new Key(0, 0, 0));
        assertTrue(TerrainCollisionRegion.covers(complete, bounds));
    }

    @Test
    void sectionBoundsHandleNegativeKeysAndScale() {
        final Aabb bounds = TerrainCollisionRegion.sectionBounds(new Key(-1, 2, -3), 1.0);
        assertEquals(-16.0, bounds.min().x, 1.0e-9);
        assertEquals(0.0, bounds.max().x, 1.0e-9);
        assertEquals(32.0, bounds.min().y, 1.0e-9);
        assertEquals(48.0, bounds.max().y, 1.0e-9);
        assertEquals(-48.0, bounds.min().z, 1.0e-9);
        assertEquals(-32.0, bounds.max().z, 1.0e-9);
    }

    @Test
    void nonFiniteBoundsProduceNoSections() {
        assertTrue(TerrainCollisionRegion.sections(
                box(Double.NaN, 0, 0, 1, 1, 1), 64).isEmpty());
        assertTrue(TerrainCollisionRegion.sections(
                box(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1), 64).isEmpty());
    }

    private static Aabb box(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ) {
        return new Aabb(new Vector3d(minX, minY, minZ), new Vector3d(maxX, maxY, maxZ));
    }
}
