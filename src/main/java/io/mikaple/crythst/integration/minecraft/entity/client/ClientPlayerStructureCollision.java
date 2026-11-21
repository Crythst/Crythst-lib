package io.mikaple.crythst.integration.minecraft.entity.client;

/**
 * Legacy client post-tick collision predictor.
 *
 * <p>Structure collision now runs inside vanilla {@code Entity.move} on both
 * sides, so the end-of-client-tick correction is no longer registered or
 * applied. The class is kept as a no-op because the client entrypoint still
 * calls {@link #register()}; removing that call leaves this class unused.</p>
 */
public final class ClientPlayerStructureCollision {
    /** Maximum correction the removed predictor used to accept. */
    public static final double MAX_CORRECTION = 1.0;

    private ClientPlayerStructureCollision() {
    }

    /**
     * No-op. The movement-integrated path needs no client tick callback, which
     * also avoids a second authority fighting the vanilla movement result.
     */
    public static void register() {
    }
}
