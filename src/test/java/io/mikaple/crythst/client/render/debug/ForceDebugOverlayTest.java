package io.mikaple.crythst.client.render.debug;

import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.ForceTotals;
import io.mikaple.crythst.physics.debug.ForceDebugSnapshot;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForceDebugOverlayTest {
    @Test
    void arrowLengthIsBoundedAndMonotonic() {
        assertEquals(0.0, ForceDebugOverlay.arrowLengthMetres(0.0, 100.0, 2.0), 0.0);
        assertEquals(0.0, ForceDebugOverlay.arrowLengthMetres(-5.0, 100.0, 2.0), 0.0);
        assertEquals(0.0, ForceDebugOverlay.arrowLengthMetres(Double.NaN, 100.0, 2.0), 0.0);
        assertEquals(0.0, ForceDebugOverlay.arrowLengthMetres(10.0, 0.0, 2.0), 0.0);
        assertEquals(0.0, ForceDebugOverlay.arrowLengthMetres(10.0, 100.0, 0.0), 0.0);

        assertEquals(1.0, ForceDebugOverlay.arrowLengthMetres(100.0, 100.0, 2.0), 1.0e-12);
        final double small = ForceDebugOverlay.arrowLengthMetres(50.0, 100.0, 2.0);
        final double large = ForceDebugOverlay.arrowLengthMetres(500.0, 100.0, 2.0);
        assertTrue(large > small, "larger forces produce longer arrows");
        assertTrue(large < 2.0, "the maximum length is an asymptote");
        assertTrue(ForceDebugOverlay.arrowLengthMetres(1.0e12, 100.0, 2.0) <= 2.0);
    }

    @Test
    void arrowVectorKeepsDirectionAndRequestedLength() {
        final Vector3d vector = ForceDebugOverlay.arrowVector(new Vector3d(0.0, 3.0, 4.0), 2.0);
        assertEquals(0.0, vector.x, 1.0e-12);
        assertEquals(1.2, vector.y, 1.0e-12);
        assertEquals(1.6, vector.z, 1.0e-12);
        assertEquals(2.0, vector.length(), 1.0e-12);

        assertEquals(0.0, ForceDebugOverlay.arrowVector(new Vector3d(), 2.0).length(), 0.0);
        assertEquals(0.0, ForceDebugOverlay.arrowVector(new Vector3d(1.0, 0.0, 0.0), -1.0)
                .length(), 0.0);
        assertEquals(0.0, ForceDebugOverlay.arrowVector(new Vector3d(1.0, 0.0, 0.0),
                Double.POSITIVE_INFINITY).length(), 0.0);
    }

    @Test
    void labelsIncludeTotalsGroupsAndMass() {
        final ForceGroup lift = new ForceGroup(Component.literal("lift"),
                null, 0x00FF00, true);
        final Map<ForceGroup, ForceTotals.GroupForce> groups = new LinkedHashMap<>();
        groups.put(lift, new ForceTotals.GroupForce(lift,
                new Vector3d(0.0, 1000.0, 0.0), new Vector3d(0.0, 0.0, -2.0)));
        final ForceTotals totals = new ForceTotals(12L, 1.0 / 60.0,
                new Vector3d(0.0, 1500.0, 0.0), new Vector3d(0.0, 0.0, 50.0),
                groups);
        final ForceDebugSnapshot snapshot = new ForceDebugSnapshot(12L, 2500.0,
                new Vector3d(1.0, 2.0, 3.0), new Vector3d(1.0, 3.0, 3.0), totals);

        assertEquals("crythst #12  F 1.50 kN  |T| 50.0 N·m  m 2.50 t",
                ForceDebugOverlay.forceLabel(snapshot));
        assertEquals("lift: F 1.00 kN  |T| 2.0 N·m",
                ForceDebugOverlay.groupLabel(groups.get(lift)));

        assertEquals("0.0 N", ForceDebugOverlay.formatForce(0.0));
        assertEquals("1.50 kN", ForceDebugOverlay.formatForce(1500.0));
        assertEquals("2.00 MN", ForceDebugOverlay.formatForce(2_000_000.0));
        assertEquals("1.50 kN·m", ForceDebugOverlay.formatTorque(1500.0));
        assertEquals("0.0 kg", ForceDebugOverlay.formatMass(0.0));
        assertEquals("250.0 kg", ForceDebugOverlay.formatMass(250.0));
    }

    @Test
    void snapshotIsDefensiveAndBoundsAreCapped() {
        final ForceDebugSnapshot snapshot = new ForceDebugSnapshot(1L, -5.0,
                new Vector3d(1.0, 2.0, 3.0), new Vector3d(4.0, 5.0, 6.0),
                ForceTotals.empty(1L));
        assertEquals(0.0, snapshot.massKg(), 0.0);
        final Vector3d origin = snapshot.origin();
        origin.zero();
        assertEquals(1.0, snapshot.origin().x, 0.0);
        final Vector3d center = snapshot.centerOfMass();
        center.zero();
        assertEquals(4.0, snapshot.centerOfMass().x, 0.0);

        assertTrue(ForceDebugOverlay.MAX_STRUCTURES > 0);
        assertTrue(ForceDebugOverlay.MAX_ARROWS >= ForceDebugOverlay.MAX_STRUCTURES);
        assertTrue(ForceDebugOverlay.MAX_GROUPS_PER_STRUCTURE > 0);
        assertFalse(ForceDebugOverlay.MAX_GROUP_LABELS > ForceDebugOverlay.MAX_GROUPS_PER_STRUCTURE);
    }
}
