package io.mikaple.crythst.physics.tick;

/**
 * Rolling timing diagnostics for the Minecraft physics integration.
 *
 * <p>One sample is recorded per server tick. The monitor keeps the last
 * {@value #WINDOW} samples in fixed-size rings, so recording is allocation-free
 * and cheap enough to leave enabled. It is intended for
 * {@code /crythst timing} and for debugging render stutter; it does not feed
 * back into simulation or rendering.</p>
 *
 * <p>All methods must be called from the server thread. {@link #snapshot()} is
 * immutable and safe to pass to a command or a future overlay.</p>
 */
public final class PhysicsTimingMonitor {
    /** Number of ticks used by the jitter estimates (about two seconds at 20 TPS). */
    public static final int WINDOW = 40;

    private final double[] stepSamples = new double[WINDOW];
    private final double[] poseSamples = new double[WINDOW];

    private long tick;
    private int sampleCount;
    private int sampleIndex;

    private double wallDeltaSeconds;
    private double simulationDeltaSeconds;
    private double fixedAlpha;
    private double poseDeltaMetres;
    private double poseDeltaRadians;
    private int steps;
    private boolean serverTickTiming;
    private boolean frozen;

    /**
     * Records one server tick.
     *
     * @param wallDeltaSeconds       measured wall-clock time since the previous tick
     * @param simulationDeltaSeconds time actually handed to the fixed-step clock
     * @param steps                  number of fixed steps that ran
     * @param fixedAlpha             the fixed-step clock remainder in {@code [0, 1)}
     * @param poseDeltaMetres        largest structure render-pose translation delta
     * @param poseDeltaRadians       largest structure render-pose rotation delta
     * @param serverTickTiming       whether simulation used the server tick budget
     * @param frozen                 whether the server tick rate manager is frozen
     */
    public void record(double wallDeltaSeconds, double simulationDeltaSeconds,
                       int steps, double fixedAlpha,
                       double poseDeltaMetres, double poseDeltaRadians,
                       boolean serverTickTiming, boolean frozen) {
        this.wallDeltaSeconds = nonNegativeFinite(wallDeltaSeconds);
        this.simulationDeltaSeconds = nonNegativeFinite(simulationDeltaSeconds);
        this.steps = Math.max(0, steps);
        this.fixedAlpha = Math.clamp(finiteOrZero(fixedAlpha), 0.0, 1.0);
        this.poseDeltaMetres = nonNegativeFinite(poseDeltaMetres);
        this.poseDeltaRadians = nonNegativeFinite(poseDeltaRadians);
        this.serverTickTiming = serverTickTiming;
        this.frozen = frozen;
        tick++;

        stepSamples[sampleIndex] = this.steps;
        poseSamples[sampleIndex] = this.poseDeltaMetres;
        sampleIndex = (sampleIndex + 1) % WINDOW;
        if (sampleCount < WINDOW) {
            sampleCount++;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                tick,
                wallDeltaSeconds,
                simulationDeltaSeconds,
                steps,
                fixedAlpha,
                serverTickTiming,
                frozen,
                poseDeltaMetres,
                poseDeltaRadians,
                stepJitter(),
                poseJitterMetres()
        );
    }

    /**
     * Average distance of the step counts from their mean. A stable 3 steps per
     * tick gives {@code 0}; an alternating 2/4 pattern gives {@code 1}.
     */
    private double stepJitter() {
        if (sampleCount < 2) {
            return 0.0;
        }
        double mean = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            mean += stepSamples[i];
        }
        mean /= sampleCount;
        double deviation = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            deviation += Math.abs(stepSamples[i] - mean);
        }
        return deviation / sampleCount;
    }

    /**
     * Mean absolute frame-to-frame change of the per-tick pose delta. Constant
     * motion gives a value near zero; a tick that covers two substeps followed
     * by one that covers four produces a clearly non-zero value.
     */
    private double poseJitterMetres() {
        if (sampleCount < 2) {
            return 0.0;
        }
        final int oldest = (sampleIndex - sampleCount + WINDOW) % WINDOW;
        double previous = poseSamples[oldest];
        double total = 0.0;
        for (int i = 1; i < sampleCount; i++) {
            final int index = (oldest + i) % WINDOW;
            total += Math.abs(poseSamples[index] - previous);
            previous = poseSamples[index];
        }
        return total / (sampleCount - 1);
    }

    private static double nonNegativeFinite(double value) {
        return Math.max(0.0, finiteOrZero(value));
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    /**
     * Immutable timing sample. Units are seconds, metres and radians.
     */
    public record Snapshot(
            long tick,
            double wallDeltaSeconds,
            double simulationDeltaSeconds,
            int steps,
            double fixedAlpha,
            boolean serverTickTiming,
            boolean frozen,
            double poseDeltaMetres,
            double poseDeltaRadians,
            double stepJitter,
            double poseJitterMetres
    ) {
        /**
         * Human-readable single line for chat / logs.
         */
        public String summary() {
            return "Crythst timing"
                    + " | mode=" + (serverTickTiming ? "server-tick" : "wall-clock")
                    + (frozen ? " frozen" : "")
                    + " tick=" + tick
                    + " wallDt=" + millis(wallDeltaSeconds)
                    + " simDt=" + millis(simulationDeltaSeconds)
                    + " steps=" + steps
                    + " fixedAlpha=" + String.format("%.3f", fixedAlpha)
                    + " poseDelta=" + String.format("%.4f", poseDeltaMetres) + "m"
                    + " poseAngle=" + String.format("%.4f", poseDeltaRadians) + "rad"
                    + " stepJitter=" + String.format("%.2f", stepJitter)
                    + " poseJitter=" + String.format("%.5f", poseJitterMetres) + "m"
                    + " | pose alpha = Minecraft tick partial tick";
        }

        private static String millis(double seconds) {
            return String.format("%.2fms", seconds * 1000.0);
        }
    }
}
