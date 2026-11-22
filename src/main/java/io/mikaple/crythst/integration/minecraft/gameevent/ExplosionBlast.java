package io.mikaple.crythst.integration.minecraft.gameevent;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Pure implementation of vanilla's explosion ray walk.
 *
 * <p>Minecraft's {@code ServerExplosion} emits 16x16x16 shell rays, advances
 * each ray in 0.3-block steps and subtracts {@code (resistance + 0.3) * 0.3}
 * from the ray power for every occupied cell. This class reproduces that walk
 * without depending on a {@code Level}, so the same selection can be run in a
 * structure's local metric space and unit-tested with a synthetic grid.</p>
 *
 * <p>{@link Probe} owns the coordinate→cell mapping. The host structure
 * caller divides the local metric point by the structure's block scale and
 * floors it, while tests use a simple integer grid. The probe also supplies
 * blast resistance and the vanilla {@code shouldBlockExplode} decision, so
 * block-specific rules (e.g. a block that refuses explosions) are honoured.</p>
 */
public final class ExplosionBlast {
    /** Vanilla 16x16x16 direction shell. */
    public static final int RAY_GRID = 16;
    /** Vanilla ray step in world blocks. */
    public static final double STEP = 0.3;
    /** Vanilla per-step power loss. */
    public static final float STEP_POWER_LOSS = 0.22500001F;

    private ExplosionBlast() {
    }

    /**
     * One integer cell in the probe's coordinate space.
     */
    public record Cell(int x, int y, int z) {
    }

    /**
     * One selected cell and the ray power at the moment it was selected.
     *
     * @param cell  occupied cell
     * @param power remaining ray power, always positive
     */
    public record Hit(Cell cell, float power) {
    }

    /**
     * Occupancy/resistance lookup used by {@link #trace}.
     */
    public interface Probe {
        /**
         * @return the occupied cell containing this continuous point, or
         *         {@code null} when the point is air
         */
        @Nullable Cell cellAt(double x, double y, double z);

        /** Blast resistance of a cell returned by {@link #cellAt}. */
        double blastResistance(Cell cell);

        /** Vanilla block-specific explosion decision for the current power. */
        boolean shouldDestroy(Cell cell, float power);
    }

    /**
     * Runs the vanilla ray walk and returns unique selected cells in discovery
     * order.
     *
     * @param radius explosion radius in the same units as {@code center}
     * @param center explosion centre in probe space
     * @param random vanilla-style per-ray multiplier in {@code [0,1)}
     * @param probe  occupancy/resistance rules
     * @return immutable unique hits; empty for a non-positive radius
     */
    public static List<Hit> trace(double radius, Vector3dc center,
                                  DoubleSupplier random, Probe probe) {
        if (radius <= 0.0 || center == null || random == null || probe == null) {
            return List.of();
        }
        final Map<Cell, Hit> hits = new LinkedHashMap<>();
        for (int xx = 0; xx < RAY_GRID; xx++) {
            for (int yy = 0; yy < RAY_GRID; yy++) {
                for (int zz = 0; zz < RAY_GRID; zz++) {
                    if (xx != 0 && xx != RAY_GRID - 1
                            && yy != 0 && yy != RAY_GRID - 1
                            && zz != 0 && zz != RAY_GRID - 1) {
                        continue;
                    }
                    final double xd0 = xx / (double) (RAY_GRID - 1) * 2.0 - 1.0;
                    final double yd0 = yy / (double) (RAY_GRID - 1) * 2.0 - 1.0;
                    final double zd0 = zz / (double) (RAY_GRID - 1) * 2.0 - 1.0;
                    final double length = Math.sqrt(xd0 * xd0 + yd0 * yd0 + zd0 * zd0);
                    if (length <= 1.0e-12) {
                        continue;
                    }
                    final double xd = xd0 / length;
                    final double yd = yd0 / length;
                    final double zd = zd0 / length;
                    float power = (float) (radius
                            * (0.7 + Math.max(0.0, Math.min(1.0, random.getAsDouble())) * 0.6));
                    double xp = center.x();
                    double yp = center.y();
                    double zp = center.z();
                    while (power > 0.0F) {
                        final Cell cell = probe.cellAt(xp, yp, zp);
                        if (cell != null) {
                            power -= (float) ((probe.blastResistance(cell) + 0.3) * 0.3);
                            if (power > 0.0F && probe.shouldDestroy(cell, power)) {
                                hits.putIfAbsent(cell, new Hit(cell, power));
                            }
                        }
                        xp += xd * STEP;
                        yp += yd * STEP;
                        zp += zd * STEP;
                        power -= STEP_POWER_LOSS;
                    }
                }
            }
        }
        return List.copyOf(new ArrayList<>(hits.values()));
    }
}
