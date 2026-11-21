package io.mikaple.crythst.api.sublevel.view;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link CollisionGetter} adapter over a {@link StructureLevelView}.
 *
 * <p>Queries use structure-local voxel coordinates. Only structure blocks are
 * returned: there is deliberately no host-world or entity collision here,
 * because a structure is an independent rigid body and host world blocks are
 * already handled by the terrain collision source.</p>
 *
 * <p>The returned shapes are exact {@link net.minecraft.world.level.block.state.BlockState#getCollisionShape}
 * results, offset into local metre space. Fences, walls, slabs, stairs and
 * other neighbour-sensitive shapes therefore see the structure's own
 * neighbours rather than the real world under the structure.</p>
 */
public final class StructureCollisionGetter extends StructureBlockGetter implements CollisionGetter {
    public StructureCollisionGetter(StructureLevelView view) {
        super(view);
    }

    @Override
    public @Nullable WorldBorder getWorldBorder() {
        final Level host = view().level();
        return host == null ? null : host.getWorldBorder();
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        // Local chunk coordinates are virtual; every local chunk is this view.
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        return List.of();
    }

    @Override
    public Iterable<VoxelShape> getBlockCollisions(@Nullable Entity entity, AABB box) {
        return view().getBlockCollisions(entity, box);
    }

    @Override
    public Iterable<VoxelShape> getCollisions(@Nullable Entity entity, AABB box) {
        return view().getCollisions(entity, box);
    }
}
