package io.mikaple.crythst.integration.minecraft.boundary;

/**
 * Small bounded work counter. Reusable outside the void-level tick phases so
 * every bridge phase can report exactly how much work it dropped.
 */
public final class BoundaryWorkBudget {
    private final int limit;
    private int used;

    public BoundaryWorkBudget(int limit) {
        this.limit = Math.max(0, limit);
    }

    public boolean tryConsume() {
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }

    public void reset() {
        used = 0;
    }

    public int used() {
        return used;
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return Math.max(0, limit - used);
    }

    public boolean exhausted() {
        return used >= limit;
    }
}
