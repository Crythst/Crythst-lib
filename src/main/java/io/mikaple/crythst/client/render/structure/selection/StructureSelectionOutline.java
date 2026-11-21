package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.StatefulVoxelVolume;
import io.mikaple.crythst.integration.minecraft.interaction.StructureBlockStates;
import io.mikaple.crythst.integration.minecraft.interaction.StructurePlacementMath;
import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureRay;
import io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureTargeting;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes the structure voxel selected by {@link ClientStructureTargeting}
 * through Minecraft's vanilla block-outline render state.
 *
 * <p>Vanilla's {@code LevelExtractor} derives the outline from
 * {@code Minecraft.hitResult} and the real block at that position. Structure
 * voxels are not real blocks, so the extractor produces no outline for them.
 * This handler runs after extraction and replaces the (usually null) outline
 * with one for the structure voxel, using the same
 * {@link BlockOutlineRenderState} record that
 * {@code LevelRenderer#submitBlockOutline} renders with the vanilla line
 * render types, colours and line width.</p>
 *
 * <p>For an axis-aligned structure at {@code blockScale == 1} the exact block
 * state shape is used at a real {@link BlockPos}. For scaled or rotated
 * structures each box of the block-state shape is transformed into world space
 * and its enclosing AABB is used. A {@link VoxelShape} is axis-aligned, so for
 * a 90-degree rotation this is still exact, while an arbitrary rotation's
 * outline is an enclosing approximation. This keeps the outline on the vanilla
 * renderer with its line types, colours and width.</p>
 */
public final class StructureSelectionOutline {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StructureSelectionOutline() {
    }

    /**
     * Registers the vanilla outline override. Safe to call more than once.
     */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register(
                    StructureSelectionOutline::afterBlockOutlineExtraction);
        }
    }

    private static void afterBlockOutlineExtraction(LevelExtractionContext context,
                                                     net.minecraft.world.phys.HitResult result) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !CrythstFabric.config().structureBlockInteractionEnabled) {
            return;
        }
        final ClientStructureRay.Hit target = ClientStructureTargeting.activeHit(minecraft);
        if (target == null) {
            // A real block, entity or miss owns the outline.
            return;
        }
        context.levelState().blockOutlineRenderState = outline(context, target, minecraft);
    }

    private static BlockOutlineRenderState outline(LevelExtractionContext context,
                                                   ClientStructureRay.Hit target,
                                                   Minecraft minecraft) {
        final boolean highContrast = minecraft.options.highContrastBlockOutline().get();
        final BlockState state = stateAt(target, minecraft);

        if (StructurePlacementMath.isAxisAlignedGrid(target.pose(), target.blockScale())) {
            final BlockPos pos = StructurePlacementMath.gridBlockPos(
                    target.pose(), target.voxelX(), target.voxelY(), target.voxelZ(),
                    target.blockScale());
            VoxelShape shape = state == null || state.isAir()
                    ? Shapes.block()
                    : state.getShape(context.level(), pos, CollisionContext.of(minecraft.player));
            if (shape.isEmpty()) {
                shape = Shapes.block();
            }
            return new BlockOutlineRenderState(pos, isTranslucent(minecraft, state),
                    highContrast, shape);
        }

        // Scaled/rotated structure: transform every box of the block-state
        // shape into world space and take its enclosing AABB. For 90-degree
        // rotations (still axis-aligned) this is exact; for arbitrary rotations
        // each box is over-approximated by its world AABB because VoxelShape is
        // axis-aligned. The shape carries absolute world coordinates, so
        // BlockPos.ZERO is used as the local origin.
        final VoxelShape shape = worldShape(context, target, state, minecraft);
        return new BlockOutlineRenderState(BlockPos.ZERO,
                isTranslucent(minecraft, state), highContrast, shape);
    }

    /**
     * Builds an absolute-world-coordinate shape from the block state's local
     * shape and the structure pose.
     */
    private static VoxelShape worldShape(LevelExtractionContext context,
                                         ClientStructureRay.Hit target,
                                         BlockState state, Minecraft minecraft) {
        final BlockPos localPos = referencePos(target);
        VoxelShape local = state == null || state.isAir()
                ? Shapes.block()
                : state.getShape(context.level(), localPos,
                        CollisionContext.of(minecraft.player));
        if (local.isEmpty()) {
            local = Shapes.block();
        }

        final double scale = target.blockScale();
        final double originX = target.voxelX() * scale;
        final double originY = target.voxelY() * scale;
        final double originZ = target.voxelZ() * scale;

        VoxelShape result = Shapes.empty();
        for (AABB box : local.toAabbs()) {
            final double x0 = originX + box.minX * scale;
            final double y0 = originY + box.minY * scale;
            final double z0 = originZ + box.minZ * scale;
            final double x1 = originX + box.maxX * scale;
            final double y1 = originY + box.maxY * scale;
            final double z1 = originZ + box.maxZ * scale;
            result = Shapes.or(result, Shapes.create(worldAabb(target, x0, y0, z0, x1, y1, z1)));
        }
        return result.isEmpty() ? Shapes.create(worldAabb(target, originX, originY, originZ,
                originX + scale, originY + scale, originZ + scale)) : result;
    }

    /**
     * A nearby real position used only for block-shape neighbour/offset
     * queries. The returned shape is transformed into world coordinates
     * afterwards, so the exact position is not used as the outline origin.
     */
    private static BlockPos referencePos(ClientStructureRay.Hit target) {
        final double scale = target.blockScale();
        final Vector3d world = target.pose().transformPoint(new Vector3d(
                (target.voxelX() + 0.5) * scale,
                (target.voxelY() + 0.5) * scale,
                (target.voxelZ() + 0.5) * scale));
        return BlockPos.containing(world.x, world.y, world.z);
    }

    /**
     * World-space AABB enclosing the eight transformed corners of a
     * structure-local box.
     */
    private static AABB worldAabb(ClientStructureRay.Hit target,
                                  double x0, double y0, double z0,
                                  double x1, double y1, double z1) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final Vector3d corner = target.pose().transformPoint(new Vector3d(
                    (i & 1) == 0 ? x0 : x1,
                    (i & 2) == 0 ? y0 : y1,
                    (i & 4) == 0 ? z0 : z1
            ));
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Looks up the block state for the selected voxel.
     *
     * <p>In singleplayer the rendered structure objects are the integrated
     * server's structures, so the side table and stateful volumes are visible.
     * In multiplayer the synced snapshot contains the palette-resolved state.</p>
     */
    private static BlockState stateAt(ClientStructureRay.Hit target, Minecraft minecraft) {
        final float alpha = minecraft.getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            final StructureRenderState state = structure.renderState(alpha);
            if (state.bodyId() != target.bodyId()) {
                continue;
            }
            if (structure.volume() instanceof StatefulVoxelVolume stateful) {
                final BlockState stored = stateful.blockState(
                        target.voxelX(), target.voxelY(), target.voxelZ());
                if (stored != null && !stored.isAir()) {
                    return stored;
                }
            }
            final BlockState sideTable = StructureBlockStates.get(structure,
                    target.voxelX(), target.voxelY(), target.voxelZ());
            if (sideTable != null && !sideTable.isAir()) {
                return sideTable;
            }
            break;
        }

        final ClientStructureSnapshot snapshot = ClientStructureCache.get(target.bodyId());
        if (snapshot != null) {
            try {
                return snapshot.blockStateAt(target.voxelX(), target.voxelY(), target.voxelZ());
            } catch (RuntimeException ignored) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return null;
    }

    private static boolean isTranslucent(Minecraft minecraft, BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        try {
            return minecraft.getModelManager().getBlockStateModelSet()
                    .get(state).hasMaterialFlag(1);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
