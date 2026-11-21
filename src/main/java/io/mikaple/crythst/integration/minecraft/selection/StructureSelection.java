package io.mikaple.crythst.integration.minecraft.selection;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared structure selection entry point.
 *
 * <p>Vanilla block clipping is reproduced without calling back into
 * {@code BlockGetter.clip}, then the nearest structure hit is merged with the
 * nearest vanilla hit. A real block or fluid that is closer always wins.</p>
 *
 * <p>The client installs this through a method added to {@code ClientLevel}, so
 * {@code Minecraft.pick}, the vanilla outline extractor and every interaction
 * path read one {@link StructureBlockHitResult}. The server calls
 * {@link #serverViews(Level)} from the authoritative interaction raycast. A
 * dedicated server never loads the client view code.</p>
 */
public final class StructureSelection {
    /**
     * A structure must be strictly closer than a real block. The tolerance
     * only absorbs floating-point noise, it cannot pull a structure in front of
     * a block the player is aiming at.
     */
    public static final double REAL_HIT_EPSILON = 1.0e-4;

    private StructureSelection() {
    }

    /** Whether structure selection is enabled by the current config. */
    public static boolean enabled() {
        final var config = MinecraftPhysics.config();
        return config != null && config.structureBlockInteractionEnabled;
    }

    /**
     * Merges structure hits into a vanilla clip.
     *
     * @param level  level used for the vanilla pass
     * @param context vanilla clip context
     * @param views  candidate structures; may be empty
     * @return the nearer of the vanilla and structure hits
     */
    public static BlockHitResult clip(BlockGetter level, ClipContext context,
                                      Iterable<? extends StructureVoxelView> views) {
        final BlockHitResult vanilla = vanillaClip(level, context);
        if (!enabled() || views == null) {
            return vanilla;
        }
        final StructureBlockHitResult structure = StructureRaycaster.nearest(
                views, context.getFrom(), context.getTo());
        if (structure == null) {
            return vanilla;
        }
        if (vanilla.getType() == HitResult.Type.MISS) {
            return structure;
        }
        final double vanillaDistance = Math.sqrt(
                vanilla.getLocation().distanceToSqr(context.getFrom()));
        return structure.distance() + REAL_HIT_EPSILON < vanillaDistance
                ? structure
                : vanilla;
    }

    /**
     * A structure-only raycast, used by the server's authoritative pass.
     */
    public static @Nullable StructureBlockHitResult raycast(
            Iterable<? extends StructureVoxelView> views, Vec3 from, Vec3 to) {
        return StructureRaycaster.nearest(views, from, to);
    }

    /**
     * A structure-only raycast from an origin/direction/reach triple.
     */
    public static @Nullable StructureBlockHitResult raycast(
            Iterable<? extends StructureVoxelView> views, Vec3 from,
            org.joml.Vector3dc direction, double reach) {
        return StructureRaycaster.nearest(views, from, direction, reach);
    }

    /**
     * Live server structures for a level, using the authoritative body poses.
     * Returns an empty list on a client level or a level without physics.
     */
    public static List<StructureVoxelView> serverViews(Level level) {
        if (!(level instanceof ServerLevel server)) {
            return List.of();
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(server);
        if (physics == null) {
            return List.of();
        }
        final List<PhysicsStructure> structures = physics.structures();
        final List<StructureVoxelView> views = new ArrayList<>(structures.size());
        for (PhysicsStructure structure : structures) {
            if (!structure.closed()) {
                views.add(PhysicsStructureView.of(structure, structure.pose()));
            }
        }
        return views;
    }

    /**
     * The original vanilla {@code BlockGetter.clip} behaviour. Reimplemented
     * here so a level implementation can add structure hits without recursion
     * through its own overridden {@code clip}.
     */
    public static BlockHitResult vanillaClip(BlockGetter level, ClipContext context) {
        final Vec3 from = context.getFrom();
        final Vec3 to = context.getTo();
        return BlockGetter.traverseBlocks(from, to, context,
                (ctx, pos) -> {
                    final BlockState state = level.getBlockState(pos);
                    final FluidState fluid = level.getFluidState(pos);
                    final VoxelShape blockShape = ctx.getBlockShape(state, level, pos);
                    final BlockHitResult blockHit = level.clipWithInteractionOverride(
                            from, to, pos, blockShape, state);
                    final VoxelShape fluidShape = ctx.getFluidShape(fluid, level, pos);
                    final BlockHitResult fluidHit = fluidShape.clip(from, to, pos);
                    final double blockDistance = blockHit == null
                            ? Double.MAX_VALUE
                            : from.distanceToSqr(blockHit.getLocation());
                    final double fluidDistance = fluidHit == null
                            ? Double.MAX_VALUE
                            : from.distanceToSqr(fluidHit.getLocation());
                    return blockDistance <= fluidDistance ? blockHit : fluidHit;
                },
                ctx -> {
                    final Vec3 delta = from.subtract(to);
                    return BlockHitResult.miss(to,
                            net.minecraft.core.Direction.getApproximateNearest(delta.x, delta.y, delta.z),
                            BlockPos.containing(to));
                });
    }
}
