package io.mikaple.crythst.integration.minecraft.entity.placement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side handler for an item that creates an entity when used on a
 * physics structure.
 *
 * <p>Register handlers through {@link StructureEntityPlacement#register}. The
 * registry is keyed by {@link net.minecraft.world.item.Item}, so a mod such as
 * Create can support its seats without Crythst taking a compile dependency on
 * it:</p>
 *
 * <pre>{@code
 * StructureEntityPlacement.register(MyItems.SEAT, context -> {
 *     SeatEntity seat = new SeatEntity(context.level());
 *     seat.moveTo(context.surface().spawnPoint());
 *     context.level().addFreshEntity(seat);
 *     return StructureEntityPlacementHandler.Result.placed(seat);
 * });
 * }</pre>
 *
 * <p>Handlers run on the server thread after the authoritative raycast and
 * surface-point computation. They must not trust client coordinates and must
 * not shrink {@code context.stack()}: the caller consumes one item after a
 * successful result, respecting creative mode. Register from common mod
 * initialization so the client's cancellation predicate and the server's
 * handler table agree; the handler body itself is only invoked on the logical
 * server.</p>
 */
@FunctionalInterface
public interface StructureEntityPlacementHandler {
    /**
     * Places one or more entities. Implementations must add the entity to the
     * level themselves; returning success is the contract for consuming the
     * item.
     */
    Result place(StructureEntityPlacementContext context);

    /**
     * Outcome of one entity placement.
     *
     * @param success whether an entity was placed
     * @param entity  the primary placed entity, for listeners/diagnostics; may
     *                be {@code null} when a handler spawned several entities
     */
    record Result(boolean success, @Nullable Entity entity) {
        public static Result placed(Entity entity) {
            return new Result(true, entity);
        }

        public static Result placed() {
            return new Result(true, null);
        }

        public static Result failed() {
            return new Result(false, null);
        }
    }
}
