package io.mikaple.crythst.integration.minecraft.structure.view;

import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.integration.minecraft.selection.StructureVoxelView;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Adapts a {@link StructureLevelView} to the read-only
 * {@link StructureVoxelView} used by the existing structure raycaster and
 * outline code.
 *
 * <p>This is the deliberate bridge that lets selection/render code consume the
 * new view without changing its raycast algorithm. Coordinates stay
 * structure-local, and {@link StructureVoxelShapes} resolves exact block shapes
 * through the view's {@link StructureLevelView#blockGetter()}.</p>
 *
 * <pre>{@code
 * StructureBlockHitResult hit = StructureRaycaster.nearest(
 *         List.of(ViewStructureVoxelView.of(view)), from, to);
 * }</pre>
 */
public final class ViewStructureVoxelView implements StructureVoxelView {
    private final StructureLevelView view;

    private ViewStructureVoxelView(StructureLevelView view) {
        if (view == null) {
            throw new NullPointerException("view");
        }
        this.view = view;
    }

    public static ViewStructureVoxelView of(StructureLevelView view) {
        return new ViewStructureVoxelView(view);
    }

    /** Backing level view. */
    public StructureLevelView view() {
        return view;
    }

    @Override
    public long bodyId() {
        return view.bodyId();
    }

    @Override
    public Transform3d pose() {
        return view.pose();
    }

    @Override
    public double blockScale() {
        return view.blockScale();
    }

    @Override
    public IntBounds3 bounds() {
        return view.bounds();
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return !view.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    @Override
    public BlockState blockState(int x, int y, int z) {
        return view.getBlockState(new BlockPos(x, y, z));
    }

    @Override
    public @Nullable PhysicsStructure structure() {
        return view.structure();
    }

    @Override
    public boolean closed() {
        final PhysicsStructure structure = view.structure();
        return structure != null && structure.closed();
    }
}
