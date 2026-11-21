package io.mikaple.crythst.util;

/**
 * Simple named timer for diagnostics. Not thread-safe; intended to be used from
 * a single simulation thread.
 */
public final class StepProfiler {
    private final String name;
    private long startedAt;
    private long lastNanos;
    private double averageNanos;
    private int samples;

    public StepProfiler(String name) {
        this.name = name;
    }

    public void begin() {
        startedAt = System.nanoTime();
    }

    public long end() {
        lastNanos = System.nanoTime() - startedAt;
        samples++;
        averageNanos += (lastNanos - averageNanos) / samples;
        return lastNanos;
    }

    public String name() {
        return name;
    }

    public long lastNanos() {
        return lastNanos;
    }

    public double averageNanos() {
        return averageNanos;
    }

    public double lastMillis() {
        return lastNanos / 1_000_000.0;
    }

    public double averageMillis() {
        return averageNanos / 1_000_000.0;
    }

    public void reset() {
        lastNanos = 0L;
        averageNanos = 0.0;
        samples = 0;
    }
}
