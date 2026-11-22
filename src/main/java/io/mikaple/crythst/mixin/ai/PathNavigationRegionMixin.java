package io.mikaple.crythst.mixin.ai;

import io.mikaple.crythst.integration.minecraft.ai.StructurePathDiagnostics;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathIndex;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathRegionAccess;
import io.mikaple.crythst.integration.minecraft.ai.StructurePathSources;
import io.mikaple.crythst.math.Aabb;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes vanilla navigation regions see structure blocks and structure
 * collision shapes.
 *
 * <p>Vanilla builds a {@code PathNavigationRegion} from host chunks only. Since
 * a physics structure lives outside the host chunk block data, every
 * pathfinding query saw air. This mixin attaches a
 * {@link StructurePathIndex} to the region and answers block/fluid lookups
 * when the host chunk is air. Collision traversal is redirected to the region
 * itself so the index is used for {@code noCollision()} as well.</p>
 *
 * <p>The index is lazy: a region in a world without structures pays one cheap
 * source lookup and then behaves exactly like vanilla.</p>
 */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin implements StructurePathRegionAccess {
    @Shadow
    @Final
    protected int centerX;

    @Shadow
    @Final
    protected int centerZ;

    @Shadow
    @Final
    protected ChunkAccess[][] chunks;

    @Shadow
    @Final
    protected Level level;

    @Unique
    private @Nullable StructurePathIndex crythst$pathIndex;

    @Unique
    private boolean crythst$pathIndexReady;

    @Override
    public boolean crythst$hasStructurePath() {
        return crythst$structurePathIndex().hasStructures();
    }

    @Override
    public StructurePathIndex crythst$structurePathIndex() {
        if (!crythst$pathIndexReady) {
            crythst$pathIndexReady = true;
            final int width = Math.max(1, chunks.length);
            final int depth = chunks.length == 0 || chunks[0] == null
                    ? 1 : Math.max(1, chunks[0].length);
            final double minX = centerX * 16.0;
            final double minZ = centerZ * 16.0;
            final double maxX = (centerX + width) * 16.0;
            final double maxZ = (centerZ + depth) * 16.0;
            final double minY = level.getMinY();
            final double maxY = level.getMaxY() + 1.0;
            final Aabb bounds = new Aabb(
                    new Vector3d(minX, minY, minZ),
                    new Vector3d(maxX, maxY, maxZ));
            crythst$pathIndex = StructurePathIndex.build(
                    StructurePathSources.forLevel(level), bounds);
            StructurePathDiagnostics.regionBuilt(crythst$pathIndex.hasStructures());
        }
        return crythst$pathIndex;
    }

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void crythst$structureBlockState(BlockPos pos,
                                             CallbackInfoReturnable<BlockState> cir) {
        final BlockState world = cir.getReturnValue();
        if (world == null || !world.isAir()) {
            return;
        }
        final StructurePathIndex index = crythst$structurePathIndex();
        if (!index.hasStructures()) {
            return;
        }
        final BlockState structure = index.structureState(pos);
        if (structure != null && !structure.isAir()) {
            cir.setReturnValue(structure);
        }
    }

    @Inject(method = "getFluidState", at = @At("RETURN"), cancellable = true)
    private void crythst$structureFluidState(BlockPos pos,
                                             CallbackInfoReturnable<FluidState> cir) {
        final FluidState world = cir.getReturnValue();
        if (world != null && !world.isEmpty()) {
            return;
        }
        final StructurePathIndex index = crythst$structurePathIndex();
        if (!index.hasStructures()) {
            return;
        }
        final FluidState structure = index.structureFluid(pos);
        if (structure != null && !structure.isEmpty()) {
            cir.setReturnValue(structure);
        }
    }

    /**
     * Collision traversal asks the region for a chunk, then reads block states
     * from it. Returning the region makes that read hit the structure-aware
     * {@code getBlockState} above.
     */
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void crythst$structureCollisionChunk(int chunkX, int chunkZ,
                                                 CallbackInfoReturnable<BlockGetter> cir) {
        if (crythst$structurePathIndex().hasStructures()) {
            cir.setReturnValue((BlockGetter) (Object) this);
        }
    }
}
