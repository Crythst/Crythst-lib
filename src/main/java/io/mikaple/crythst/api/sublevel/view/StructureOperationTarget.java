package io.mikaple.crythst.api.sublevel.view;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Native operation target for one structure voxel.
 *
 * <p>This is the narrow redirect hook for a future player-operation bridge.
 * {@link #level()} is the auxiliary void {@link ServerLevel} and
 * {@link #auxiliaryPos()} is the block position inside it. Vanilla code such as
 * {@code ServerPlayerGameMode#useItemOn}, {@code BlockState#useItemOn},
 * {@code destroyBlock} or {@code BlockEntity} menus can be called with these
 * values, exactly as if the structure were a normal part of that level.</p>
 *
 * <p>Consumers must treat {@link #auxiliaryPos()} as opaque. Public/logged
 * coordinates are always {@link #localPos()} in structure-local voxels. The
 * target is only produced when the region and its chunk are currently loaded,
 * so using it cannot force a synchronous chunk load.</p>
 *
 * <p>Client views never produce a target.</p>
 */
public record StructureOperationTarget(
        StructureLevelView view,
        ServerLevel level,
        BlockPos auxiliaryPos,
        BlockPos localPos,
        @Nullable BlockHitResult hitResult
) {
    public StructureOperationTarget {
        view = Objects.requireNonNull(view, "view");
        level = Objects.requireNonNull(level, "level");
        auxiliaryPos = Objects.requireNonNull(auxiliaryPos, "auxiliaryPos").immutable();
        localPos = Objects.requireNonNull(localPos, "localPos").immutable();
    }

    /** Block state through the structure-local view. */
    public BlockState blockState() {
        return view.getBlockState(localPos);
    }

    /**
     * Live block entity in the auxiliary level, or {@code null}.
     *
     * <p>The returned entity's {@code getBlockPos()} is the auxiliary position;
     * do not expose or persist it.</p>
     */
    public @Nullable BlockEntity blockEntity() {
        return level.getBlockEntity(auxiliaryPos);
    }

    /**
     * Hit result rebuilt in auxiliary coordinates, or {@code null} when the
     * target was requested without a hit. Suitable for
     * {@code BlockState#useItemOn(ItemStack, Level, Player, InteractionHand,
     * BlockHitResult)} and {@code ServerPlayerGameMode#useItemOn}.
     */
    public @Nullable BlockHitResult hitResult() {
        return hitResult;
    }
}
