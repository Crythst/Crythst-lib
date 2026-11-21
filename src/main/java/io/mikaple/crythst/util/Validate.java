package io.mikaple.crythst.util;

/**
 * Argument checks used by public constructors and builders.
 */
public final class Validate {
    private Validate() {
    }

    public static <T> T notNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return value;
    }

    public static double finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        return value;
    }

    public static double positive(double value, String name) {
        finite(value, name);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }

    public static double nonNegative(double value, String name) {
        finite(value, name);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
        return value;
    }

    public static int atLeast(int value, int minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException(name + " must be at least " + minimum + ": " + value);
        }
        return value;
    }

    public static <T> T[] notEmpty(T[] values, String name) {
        notNull(values, name);
        if (values.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return values;
    }
}
