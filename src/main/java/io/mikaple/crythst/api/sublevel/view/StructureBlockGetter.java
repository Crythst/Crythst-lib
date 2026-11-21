package io.mikaple.crythst.api.sublevel.view;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/**
 * Read-only vanilla {@link BlockGetter} adapter over a {@link StructureLevelView}.
 *
 * <p>Positions accepted by this getter are structure-local voxel coordinates.
 * That is intentional: vanilla shape and placement code can run against a
 * structure as if it were its own level. The adapter is safe to pass to
 * {@link BlockState#getShape}, {@link BlockState#getCollisionShape},
 * {@code BlockState#canSurvive}, raycasts and block-entity logic. It never
 * touches the auxiliary void region coordinates.</p>
 *
 * <p>Use {@link StructureCollisionGetter} when {@code CollisionGetter} is
 * required. Both adapters are cheap wrappers; they do not cache block data.</p>
 */
public class StructureBlockGetter implements BlockGetter {
    private final StructureLevelView view;

    public StructureBlockGetter(StructureLevelView view) {
        if (view == null) {
            throw new NullPointerException("view");
        }
        this.view = view;
    }

    /** The view backing this getter. */
    public StructureLevelView view() {
        return view;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return view.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return view.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return view.getFluidState(pos);
    }

    @Override
    public int getHeight() {
        return view.height();
    }

    @Override
    public int getMinY() {
        return view.minBuildHeight();
    }

    @Override
    public Stream<BlockState> getBlockStates(AABB box) {
        return view.getBlockStates(box);
    }
}
