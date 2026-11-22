package io.mikaple.crythst.integration.minecraft.boundary;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Pure decision table for one structure/host fluid boundary face.
 *
 * <p>The policy only decides what should happen. It does not touch a level, so
 * it can be tested without a server. The bridge supplies the live states and
 * owns budgeting, chunk-loaded checks and loop suppression.</p>
 */
public final class FluidBoundaryPolicy {
    private FluidBoundaryPolicy() {
    }

    public enum Action {
        /** Nothing crosses the face. */
        NONE,
        /** Structure fluid spills into a replaceable host block. */
        FLUID_TO_HOST,
        /** Host fluid enters a replaceable structure cell. */
        FLUID_TO_STRUCTURE,
        /** A fluid this bridge previously placed in the host should be removed. */
        REMOVE_HOST_FLUID
    }

    /**
     * @param inside            live structure cell state (never {@code null})
     * @param host              live host block state (never {@code null})
     * @param insideFluid       fluid state of {@code inside}
     * @param hostFluid         fluid state of {@code host}
     * @param hostBridgeOwned   whether the host fluid at this position was placed
     *                          by the bridge from this structure
     */
    public static Action decide(BlockState inside, BlockState host,
                                FluidState insideFluid, FluidState hostFluid,
                                boolean hostBridgeOwned) {
        if (inside == null || host == null || insideFluid == null || hostFluid == null) {
            return Action.NONE;
        }
        final boolean insideHasFluid = !insideFluid.isEmpty();
        final boolean hostHasFluid = !hostFluid.isEmpty();
        final boolean insideReplaceable = inside.isAir() || inside.canBeReplaced();
        final boolean hostReplaceable = host.isAir() || host.canBeReplaced();

        if (insideHasFluid && hostReplaceable && !hostHasFluid) {
            return Action.FLUID_TO_HOST;
        }
        if (insideReplaceable && !insideHasFluid && hostHasFluid && !hostBridgeOwned) {
            return Action.FLUID_TO_STRUCTURE;
        }
        if (hostBridgeOwned && !insideHasFluid && hostHasFluid) {
            // The structure stopped supplying the fluid (drained, broken, or
            // moved away). Remove the mirror we created, but only if it is
            // still the same fluid block and has not been replaced by the
            // player/world.
            return Action.REMOVE_HOST_FLUID;
        }
        return Action.NONE;
    }
}
