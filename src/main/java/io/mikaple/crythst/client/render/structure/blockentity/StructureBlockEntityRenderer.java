package io.mikaple.crythst.client.render.structure.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.render.structure.selection.StructureOutlineTransform;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders block entities inside physics structures with their real vanilla
 * renderers.
 *
 * <p>The batched block renderer only draws baked block models. Signs, banners,
 * skulls, beds, banners, chests' lids, shulker boxes and similar states either
 * have no model or need their {@code BlockEntityRenderer}. This pass recreates
 * a client-side {@link BlockEntity} from the server snapshot NBT and submits the
 * vanilla render state at the structure voxel's camera-relative pose. Rotated
 * and scaled structures therefore keep the same BER geometry and data as a real
 * block.</p>
 *
 * <p>Singleplayer only for now: the snapshot carries block-entity NBT from the
 * integrated server. Multiplayer block-entity NBT sync is still incomplete.</p>
 */
public final class StructureBlockEntityRenderer {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<Long, Cached> CACHE = new HashMap<>();

    private StructureBlockEntityRenderer() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            LevelRenderEvents.COLLECT_SUBMITS.register(StructureBlockEntityRenderer::collect);
        }
    }

    private static void collect(LevelRenderContext context) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null || context.levelState() == null
                || context.levelState().cameraRenderState == null
                || context.levelState().cameraRenderState.pos == null
                || context.poseStack() == null
                || context.submitNodeCollector() == null) {
            return;
        }
        final Vec3 camera = context.levelState().cameraRenderState.pos;
        final Vector3d cameraPos = new Vector3d(camera.x, camera.y, camera.z);
        final BlockEntityRenderDispatcher dispatcher =
                minecraft.getBlockEntityRenderDispatcher();
        dispatcher.prepare(camera);
        final float partialTick = minecraft.getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        final long now = System.nanoTime();
        final Set<Long> live = new HashSet<>();

        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final StructureRenderState render = structure.renderStateAt(now);
            final long bodyId = render.bodyId();
            live.add(bodyId);
            final StructureBlockSnapshots.Snapshot snapshot =
                    StructureBlockSnapshots.get(level.dimension(), bodyId);
            if (snapshot == null || snapshot.blockEntities().isEmpty()) {
                CACHE.remove(bodyId);
                continue;
            }
            Cached cached = CACHE.get(bodyId);
            if (cached == null || cached.snapshot != snapshot) {
                cached = build(level, render, snapshot);
                CACHE.put(bodyId, cached);
            }
            if (cached.entries.isEmpty()) {
                continue;
            }
            final PoseStack poseStack = context.poseStack();
            for (Entry entry : cached.entries) {
                final Matrix4f matrix = StructureOutlineTransform.cameraRelativeMatrix(
                        render.pose(), entry.localX, entry.localY, entry.localZ,
                        render.blockScale(), cameraPos);
                poseStack.pushPose();
                try {
                    poseStack.mulPose(matrix);
                    final BlockEntityRenderState state = dispatcher.tryExtractRenderState(
                            entry.blockEntity, partialTick, null, false);
                    if (state == null) {
                        continue;
                    }
                    dispatcher.submit(state, poseStack,
                            context.submitNodeCollector(), context.levelState().cameraRenderState);
                } catch (RuntimeException ignored) {
                    // A modded BER can throw for an unsupported synthetic level;
                    // skip that entity rather than breaking the whole frame.
                } finally {
                    poseStack.popPose();
                }
            }
        }
        CACHE.keySet().removeIf(id -> !live.contains(id));
    }

    private static Cached build(ClientLevel level, StructureRenderState render,
                                StructureBlockSnapshots.Snapshot snapshot) {
        final List<Entry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, CompoundTag> data : snapshot.blockEntities().entrySet()) {
            final BlockPos local = data.getKey();
            final int voxelX = snapshot.minX() + local.getX();
            final int voxelY = snapshot.minY() + local.getY();
            final int voxelZ = snapshot.minZ() + local.getZ();
            final BlockState state = snapshot.stateAt(voxelX, voxelY, voxelZ);
            if (state == null || state.isAir()
                    || state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)
                    || !state.hasBlockEntity()) {
                continue;
            }
            final Vector3d world = render.pose().transformPoint(new Vector3d(
                    (voxelX + 0.5) * render.blockScale(),
                    (voxelY + 0.5) * render.blockScale(),
                    (voxelZ + 0.5) * render.blockScale()));
            final BlockPos worldPos = BlockPos.containing(world.x, world.y, world.z);
            try {
                final BlockEntity blockEntity = BlockEntity.loadStatic(
                        worldPos, state, data.getValue(), level.registryAccess());
                if (blockEntity != null) {
                    blockEntity.setLevel(level);
                    entries.add(new Entry(voxelX, voxelY, voxelZ, blockEntity));
                }
            } catch (RuntimeException ignored) {
                // Missing/invalid modded NBT; fall back to the block model.
            }
        }
        return new Cached(snapshot, List.copyOf(entries));
    }

    private record Entry(int localX, int localY, int localZ, BlockEntity blockEntity) {
    }

    private record Cached(StructureBlockSnapshots.Snapshot snapshot, List<Entry> entries) {
    }
}
