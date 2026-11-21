package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.world.level.block.state.BlockState;

/**
 * One block cell inside a structure section that is drawn by a
 * {@link io.mikaple.crythst.client.render.block.SpecialBlockRenderer} instead
 * of the normal batched quads.
 *
 * <p>Coordinates are section-local voxel offsets; the owning
 * {@link StructureBatchCache.Section} adds its origin, block scale and
 * centre-of-mass offset to obtain the structure-local block centre.</p>
 *
 * @param x     section-local x offset in {@code [0, 16)}
 * @param y     section-local y offset in {@code [0, 16)}
 * @param z     section-local z offset in {@code [0, 16)}
 * @param state the block state to pass to the special renderer
 */
record SpecialBlockCell(int x, int y, int z, BlockState state) {
}
