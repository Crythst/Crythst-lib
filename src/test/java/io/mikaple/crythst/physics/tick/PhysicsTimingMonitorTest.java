package io.mikaple.crythst.physics.tick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsTimingMonitorTest {
    @Test
    void stableStepCountHasZeroStepJitter() {
        final PhysicsTimingMonitor monitor = new PhysicsTimingMonitor();
        for (int i = 0; i < PhysicsTimingMonitor.WINDOW * 2; i++) {
            monitor.record(0.050, 0.050, 3, 0.0, 0.01, 0.0, true, false);
        }
        final PhysicsTimingMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(3, snapshot.steps());
        assertEquals(0.0, snapshot.stepJitter(), 1.0e-12);
        assertEquals(0.0, snapshot.poseJitterMetres(), 1.0e-12);
        assertTrue(snapshot.summary().contains("mode=server-tick"));
    }

    @Test
    void alternatingStepCountsAreReportedAsJitter() {
        final PhysicsTimingMonitor monitor = new PhysicsTimingMonitor();
        for (int i = 0; i < PhysicsTimingMonitor.WINDOW * 2; i++) {
            final int steps = (i % 2 == 0) ? 2 : 4;
            final double poseDelta = steps * 0.01;
            monitor.record(0.050, 0.050, steps, 0.0, poseDelta, 0.0, true, false);
        }
        final PhysicsTimingMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(1.0, snapshot.stepJitter(), 1.0e-9);
        assertTrue(snapshot.poseJitterMetres() > 0.0);
    }

    @Test
    void handlesInvalidValuesWithoutBreakingDiagnostics() {
        final PhysicsTimingMonitor monitor = new PhysicsTimingMonitor();
        monitor.record(Double.NaN, Double.POSITIVE_INFINITY, -1, Double.NaN,
                Double.NaN, Double.NEGATIVE_INFINITY, false, true);
        final PhysicsTimingMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(0, snapshot.steps());
        assertEquals(0.0, snapshot.wallDeltaSeconds());
        assertEquals(0.0, snapshot.simulationDeltaSeconds());
        assertEquals(0.0, snapshot.fixedAlpha());
        assertTrue(snapshot.frozen());
    }
}
