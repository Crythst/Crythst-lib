package io.mikaple.crythst.integration.minecraft.interaction.client;

/**
 * Pure mining-progress maths shared by the structure mining controller and
 * tests.
 *
 * <p>The formula is the vanilla survival one:
 * {@code destroySpeed / hardness / (correctTool ? 30 : 100)}. A hardness of
 * {@code -1} means unbreakable, and a hardness of {@code 0} completes on the
 * next tick. Creative mode is handled by the caller, which never accumulates
 * progress.</p>
 *
 * <p>Tool speed, Efficiency, Haste/Mining Fatigue, the block-break-speed
 * attribute, submerged mining and the off-ground penalty are all already part
 * of {@code Player#getDestroySpeed(BlockState)}. This class only needs the
 * resulting speed plus the state's hardness and
 * {@code Player#hasCorrectToolForDrops(BlockState)}.</p>
 */
public final class MiningMath {
    /** A completed destroy stage. */
    public static final float COMPLETE = 1.0F;

    /** Highest crack texture stage used by vanilla ({@code 0..9}). */
    public static final int MAX_STAGE = 9;

    private MiningMath() {
    }

    /**
     * Progress added to a block in one client tick.
     *
     * @param hardness     {@code BlockState#getDestroySpeed(BlockGetter, BlockPos)}
     * @param destroySpeed {@code Player#getDestroySpeed(BlockState)}
     * @param correctTool  {@code Player#hasCorrectToolForDrops(BlockState)}
     * @return a finite non-negative value, or {@link Float#POSITIVE_INFINITY}
     *         for an instant break
     */
    public static float progressPerTick(float hardness, float destroySpeed, boolean correctTool) {
        if (!(hardness >= 0.0F) || !(destroySpeed > 0.0F)) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return Float.POSITIVE_INFINITY;
        }
        return destroySpeed / hardness / (correctTool ? 30.0F : 100.0F);
    }

    /**
     * Adds one tick of progress, keeping the value inside {@code [0, 1]}.
     * Zero, negative and {@code NaN} increments leave the progress unchanged.
     */
    public static float advance(float progress, float perTick) {
        final float current = sanitize(progress);
        if (!(perTick > 0.0F)) {
            return current;
        }
        final float next = current + perTick;
        if (!Float.isFinite(next) || next >= COMPLETE) {
            return COMPLETE;
        }
        return Math.max(current, next);
    }

    public static boolean isComplete(float progress) {
        return progress >= COMPLETE;
    }

    /**
     * Vanilla crack stage for a progress value.
     *
     * @return {@code -1} when there is no visible progress, otherwise
     *         {@code 0..9}
     */
    public static int stage(float progress) {
        if (!(progress > 0.0F)) {
            return -1;
        }
        if (progress >= COMPLETE) {
            return MAX_STAGE;
        }
        return Math.min(MAX_STAGE, (int) (progress * 10.0F));
    }

    private static float sanitize(float value) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }
        return Math.min(value, COMPLETE);
    }
}
