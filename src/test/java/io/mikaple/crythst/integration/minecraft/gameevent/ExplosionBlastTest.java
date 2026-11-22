package io.mikaple.crythst.integration.minecraft.gameevent;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionBlastTest {
    private static final Vector3d CENTER = new Vector3d(0.5, 0.5, 0.5);

    @Test
    void traceSelectsNearbyCellsWithoutDuplicates() {
        final GridProbe probe = new GridProbe();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    probe.block(x, y, z, 0.3);
                }
            }
        }

        final var hits = ExplosionBlast.trace(3.0, CENTER, () -> 0.5, probe);

        assertFalse(hits.isEmpty(), "a radius-3 explosion must select cells");
        final Set<ExplosionBlast.Cell> unique = new HashSet<>();
        for (ExplosionBlast.Hit hit : hits) {
            assertTrue(hit.power() > 0.0F, "selected power must be positive");
            assertTrue(unique.add(hit.cell()), "a cell must appear only once");
            final double dx = hit.cell().x() + 0.5 - CENTER.x;
            final double dy = hit.cell().y() + 0.5 - CENTER.y;
            final double dz = hit.cell().z() + 0.5 - CENTER.z;
            assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= 4.0,
                    "selected cell must be near the radius");
        }
        assertTrue(unique.contains(new ExplosionBlast.Cell(0, 0, 0)),
                "the centre block is selected");
        assertFalse(unique.contains(new ExplosionBlast.Cell(4, 4, 4)),
                "a far corner is not selected");
    }

    @Test
    void blastResistanceAbsorbsTheRay() {
        final GridProbe low = new GridProbe();
        final GridProbe high = new GridProbe();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    low.block(x, y, z, 0.3);
                    high.block(x, y, z, 0.3);
                }
            }
        }
        high.block(1, 0, 0, 1_000.0);

        final var lowHits = ExplosionBlast.trace(3.0, CENTER, () -> 0.5, low);
        final var highHits = ExplosionBlast.trace(3.0, CENTER, () -> 0.5, high);

        assertTrue(lowHits.stream().anyMatch(hit ->
                        hit.cell().equals(new ExplosionBlast.Cell(1, 0, 0))),
                "a normal block on the +X ray is destroyed");
        assertFalse(highHits.stream().anyMatch(hit ->
                        hit.cell().equals(new ExplosionBlast.Cell(1, 0, 0))),
                "a very high blast resistance cell absorbs the ray");
        assertTrue(highHits.size() < lowHits.size(),
                "absorbing one ray must reduce the destroyed set");
    }

    @Test
    void blockSpecificVetoIsHonoured() {
        final GridProbe probe = new GridProbe() {
            @Override
            public boolean shouldDestroy(ExplosionBlast.Cell cell, float power) {
                return false;
            }
        };
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    probe.block(x, y, z, 0.3);
                }
            }
        }
        assertTrue(ExplosionBlast.trace(3.0, CENTER, () -> 0.5, probe).isEmpty(),
                "a block that refuses explosions is never selected");
    }

    @Test
    void nonPositiveRadiusSelectsNothing() {
        final GridProbe probe = new GridProbe();
        probe.block(0, 0, 0, 0.3);
        assertTrue(ExplosionBlast.trace(0.0, CENTER, () -> 0.5, probe).isEmpty());
        assertTrue(ExplosionBlast.trace(-2.0, CENTER, () -> 0.5, probe).isEmpty());
    }

    private static class GridProbe implements ExplosionBlast.Probe {
        private final Map<ExplosionBlast.Cell, Double> cells = new HashMap<>();

        private void block(int x, int y, int z, double resistance) {
            cells.put(new ExplosionBlast.Cell(x, y, z), resistance);
        }

        @Override
        public ExplosionBlast.Cell cellAt(double x, double y, double z) {
            final ExplosionBlast.Cell cell = new ExplosionBlast.Cell(
                    (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            return cells.containsKey(cell) ? cell : null;
        }

        @Override
        public double blastResistance(ExplosionBlast.Cell cell) {
            return cells.getOrDefault(cell, 0.0);
        }

        @Override
        public boolean shouldDestroy(ExplosionBlast.Cell cell, float power) {
            return true;
        }
    }
}
