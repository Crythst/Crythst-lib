package io.mikaple.crythst.api.physics.force;

import net.minecraft.network.chat.Component;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForceTotalsTest {
    private static final double EPSILON = 1.0e-9;

    private static ForceGroup group(String name) {
        return new ForceGroup(Component.literal(name));
    }

    @Test
    void totalsExposeImmutableForceAndTorqueCopies() {
        final ForceGroup lift = group("lift");
        final Map<ForceGroup, ForceTotals.GroupForce> groups = new LinkedHashMap<>();
        groups.put(lift, new ForceTotals.GroupForce(lift,
                new Vector3d(0.0, 100.0, 0.0), new Vector3d(0.0, 0.0, -2.0)));

        final ForceTotals totals = new ForceTotals(7L, 1.0 / 60.0,
                new Vector3d(0.0, 100.0, 0.0), new Vector3d(0.0, 0.0, -2.0), groups);

        assertEquals(7L, totals.bodyId());
        assertEquals(1.0 / 60.0, totals.timeStepSeconds(), 0.0);
        assertEquals(100.0, totals.forceMagnitude(), EPSILON);
        assertEquals(2.0, totals.torqueMagnitude(), EPSILON);
        assertEquals(1, totals.groupCount());
        assertTrue(totals.isPresent());
        assertFalse(totals.isZero());

        final Vector3d force = totals.force();
        force.zero();
        assertEquals(100.0, totals.force().y, EPSILON, "defensive copy");

        final Vector3d groupForce = totals.groupForce(lift);
        assertEquals(100.0, groupForce.y, EPSILON);
        groupForce.zero();
        assertEquals(100.0, totals.groupForce(lift).y, EPSILON, "per-group defensive copy");
        assertEquals(-2.0, totals.groupTorque(lift).z, EPSILON);

        assertNull(totals.group(group("missing")));
        assertTrue(totals.groupForce(group("missing")).lengthSquared() == 0.0);
    }

    @Test
    void emptyTotalsAreZeroAndAbsent() {
        final ForceTotals empty = ForceTotals.empty(42L);
        assertEquals(42L, empty.bodyId());
        assertTrue(empty.isZero());
        assertFalse(empty.isPresent());
        assertEquals(0, empty.groupCount());
        assertTrue(empty.groups().isEmpty());
    }
}
