package io.mikaple.crythst.client.render.structure.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.network.ClientBlockEntity;
import io.mikaple.crythst.client.network.ClientBlockEntitySnapshot;
import io.mikaple.crythst.client.network.ClientStructureCache;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.client.render.structure.selection.StructureOutlineTransform;
import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.structure.StructureBlockSnapshots;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
 * skulls, beds, chests' lids, shulker boxes and similar states either have no
 * model or need their {@code BlockEntityRenderer}. This pass recreates a
 * client-side {@link BlockEntity} from snapshot NBT and submits the vanilla
 * render state at the structure voxel's camera-relative pose. Rotated and
 * scaled structures therefore keep the same BER geometry and data as a real
 * block.</p>
 *
 * <p>Two immutable sources are consumed:</p>
 * <ul>
 *   <li>singleplayer: {@link StructureBlockSnapshots.Snapshot} captured on the
 *       integrated server thread;</li>
 *   <li>multiplayer: {@link ClientBlockEntitySnapshot} received from the
 *       server. Geometry comes from {@link ClientStructureSnapshot} and the
 *       pose from the network {@code PoseTimeline}.</li>
 * </ul>
 *
 * <p>Chest lid openness is transient block-entity state, not NBT. Both sources
 * carry a per-voxel chest opener count; this renderer feeds it to the synthetic
 * {@link ChestBlockEntity}'s vanilla lid controller and ticks only chests that
 * are actually opening or closing. Animation-only snapshot changes reuse the
 * existing block-entity instances, so a closing lid does not snap to its
 * closed pose.</p>
 */
public final class StructureBlockEntityRenderer {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<Long, Cached> CACHE = new HashMap<>();

    private StructureBlockEntityRenderer() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            LevelRenderEvents.COLLECT_SUBMITS.register(StructureBlockEntityRenderer::collect);
            ClientTickEvents.END_CLIENT_TICK.register(StructureBlockEntityRenderer::tickAnimations);
        }
    }

    private static void collect(LevelRenderContext context) {
        final LevelRenderState state = context.levelState();
        submit(Minecraft.getInstance(), state == null ? null : state.cameraRenderState,
                context.poseStack(), context.submitNodeCollector());
    }

    public static void submit(Minecraft minecraft, CameraRenderState cameraState,
                              PoseStack poseStack, SubmitNodeCollector collector) {
        final ClientLevel level = minecraft.level;
        if (level == null || cameraState == null || cameraState.pos == null
                || poseStack == null || collector == null) {
            return;
        }
        final Vec3 camera = cameraState.pos;
        final Vector3d cameraPos = new Vector3d(camera.x, camera.y, camera.z);
        final BlockEntityRenderDispatcher dispatcher =
                minecraft.getBlockEntityRenderDispatcher();
        dispatcher.prepare(camera);
        final float partialTick = minecraft.getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        final long now = System.nanoTime();
        final Set<Long> live = new HashSet<>();

        // Singleplayer / integrated server: live render states plus the
        // server-thread block snapshot. Local body ids win over network data.
        final Set<Long> localBodyIds = new HashSet<>();
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final StructureRenderState render = structure.renderStateAt(now);
            final long bodyId = render.bodyId();
            localBodyIds.add(bodyId);
            live.add(bodyId);
            final StructureBlockSnapshots.Snapshot snapshot =
                    StructureBlockSnapshots.get(level.dimension(), bodyId);
            if (snapshot == null || snapshot.blockEntities().isEmpty()) {
                CACHE.remove(bodyId);
                continue;
            }
            final Map<Long, Integer> openCounts = new HashMap<>();
            for (Map.Entry<BlockPos, Integer> entry : snapshot.blockEntityOpenCounts().entrySet()) {
                openCounts.put(pack(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ()),
                        entry.getValue());
            }
            Cached cached = CACHE.get(bodyId);
            if (cached == null
                    || cached.geometryRevision != snapshot.revision()
                    || cached.blockEntityRevision != snapshot.blockEntityRevision()) {
                cached = buildLocal(level, render, snapshot, openCounts);
                CACHE.put(bodyId, cached);
            } else if (!cached.openCounts.equals(openCounts)) {
                cached = new Cached(cached.geometryRevision, cached.blockEntityRevision,
                        Map.copyOf(openCounts), cached.entries);
                CACHE.put(bodyId, cached);
            }
            submit(cached, render.pose(), render.blockScale(), cameraPos,
                    dispatcher, partialTick, cameraState, poseStack, collector);
        }

        // Multiplayer: immutable snapshots and block-entity updates from the
        // server. Skip bodies already rendered from the integrated server so a
        // structure is never drawn twice in singleplayer.
        for (ClientStructureSnapshot network : ClientStructureCache.snapshots()) {
            final long bodyId = network.bodyId();
            if (localBodyIds.contains(bodyId)) {
                continue;
            }
            final ClientBlockEntitySnapshot blockEntities =
                    ClientStructureCache.blockEntities(bodyId);
            if (blockEntities == null || blockEntities.isEmpty()) {
                CACHE.remove(bodyId);
                continue;
            }
            live.add(bodyId);
            final Map<Long, Integer> openCounts = new HashMap<>();
            for (ClientBlockEntity entry : blockEntities.entries()) {
                openCounts.put(entry.packedPos(), entry.openCount());
            }
            Cached cached = CACHE.get(bodyId);
            if (cached == null
                    || cached.geometryRevision != network.geometryRevision()
                    || cached.blockEntityRevision != blockEntities.dataRevision()) {
                cached = buildNetwork(level, network, blockEntities, openCounts);
                CACHE.put(bodyId, cached);
            } else if (!cached.openCounts.equals(openCounts)) {
                cached = new Cached(cached.geometryRevision, cached.blockEntityRevision,
                        Map.copyOf(openCounts), cached.entries);
                CACHE.put(bodyId, cached);
            }
            final Transform3d pose = ClientStructureCache.interpolatedPose(bodyId, now);
            submit(cached, pose, network.blockScale(), cameraPos,
                    dispatcher, partialTick, cameraState, poseStack, collector);
        }
        CACHE.keySet().removeIf(id -> !live.contains(id));
    }

    private static void submit(Cached cached, Transform3d pose, double blockScale,
                               Vector3d cameraPos, BlockEntityRenderDispatcher dispatcher,
                               float partialTick, CameraRenderState cameraState,
                               PoseStack poseStack, SubmitNodeCollector collector) {
        if (cached.entries.isEmpty()) {
            return;
        }
        for (Entry entry : cached.entries) {
            entry.syncAnimation(cached.openCounts.getOrDefault(entry.packedLocal(), -1));
            final Matrix4f matrix = StructureOutlineTransform.cameraRelativeMatrix(
                    pose, entry.localX, entry.localY, entry.localZ,
                    blockScale, cameraPos);
            poseStack.pushPose();
            try {
                poseStack.mulPose(matrix);
                final BlockEntityRenderState state = dispatcher.tryExtractRenderState(
                        entry.blockEntity, partialTick, null, false);
                if (state == null) {
                    continue;
                }
                dispatcher.submit(state, poseStack,
                        collector, cameraState);
            } catch (RuntimeException ignored) {
                // A modded BER can throw for an unsupported synthetic level;
                // skip that entity rather than breaking the whole frame.
            } finally {
                poseStack.popPose();
            }
        }
    }

    /**
     * Advances vanilla chest lid controllers on the client tick. Only chests
     * that are currently opening/closing are ticked; a settled open or closed
     * chest costs nothing.
     */
    private static void tickAnimations(Minecraft minecraft) {
        if (CACHE.isEmpty()) {
            return;
        }
        for (Cached cached : List.copyOf(CACHE.values())) {
            for (Entry entry : cached.entries) {
                if (!(entry.blockEntity instanceof ChestBlockEntity chest)
                        || entry.lastOpenCount < 0
                        || chest.getLevel() == null) {
                    continue;
                }
                final float openness = chest.getOpenNess(0.0f);
                final boolean animating = entry.lastOpenCount > 0
                        ? openness < 1.0f
                        : openness > 0.0f;
                if (animating) {
                    ChestBlockEntity.lidAnimateTick(chest.getLevel(),
                            chest.getBlockPos(), chest.getBlockState(), chest);
                }
            }
        }
    }

    private static Cached buildLocal(ClientLevel level, StructureRenderState render,
                                     StructureBlockSnapshots.Snapshot snapshot,
                                     Map<Long, Integer> openCounts) {
        final List<Entry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, CompoundTag> data : snapshot.blockEntities().entrySet()) {
            final BlockPos local = data.getKey();
            final int voxelX = snapshot.minX() + local.getX();
            final int voxelY = snapshot.minY() + local.getY();
            final int voxelZ = snapshot.minZ() + local.getZ();
            final BlockState state = snapshot.stateAt(voxelX, voxelY, voxelZ);
            if (!acceptsBlockEntity(state)) {
                continue;
            }
            final Entry entry = createEntry(level, render.pose(), render.blockScale(),
                    local.getX(), local.getY(), local.getZ(), voxelX, voxelY, voxelZ,
                    state, data.getValue());
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new Cached(snapshot.revision(), snapshot.blockEntityRevision(),
                Map.copyOf(openCounts), List.copyOf(entries));
    }

    private static Cached buildNetwork(ClientLevel level, ClientStructureSnapshot snapshot,
                                       ClientBlockEntitySnapshot blockEntities,
                                       Map<Long, Integer> openCounts) {
        final List<Entry> entries = new ArrayList<>();
        for (ClientBlockEntity data : blockEntities.entries()) {
            final int localX = data.x();
            final int localY = data.y();
            final int localZ = data.z();
            final int voxelX = snapshot.bounds().minX() + localX;
            final int voxelY = snapshot.bounds().minY() + localY;
            final int voxelZ = snapshot.bounds().minZ() + localZ;
            final BlockState state = snapshot.blockStateAt(voxelX, voxelY, voxelZ);
            if (!acceptsBlockEntity(state)) {
                continue;
            }
            final Entry entry = createEntry(level, snapshot.pose(), snapshot.blockScale(),
                    localX, localY, localZ, voxelX, voxelY, voxelZ,
                    state, data.data());
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new Cached(snapshot.geometryRevision(), blockEntities.dataRevision(),
                Map.copyOf(openCounts), List.copyOf(entries));
    }

    private static boolean acceptsBlockEntity(BlockState state) {
        return state != null && !state.isAir()
                && !state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)
                && state.hasBlockEntity();
    }

    private static Entry createEntry(ClientLevel level, Transform3d structurePose, double blockScale,
                                     int localX, int localY, int localZ,
                                     int voxelX, int voxelY, int voxelZ,
                                     BlockState state, CompoundTag data) {
        final Vector3d world = structurePose.transformPoint(new Vector3d(
                (voxelX + 0.5) * blockScale,
                (voxelY + 0.5) * blockScale,
                (voxelZ + 0.5) * blockScale));
        final BlockPos worldPos = BlockPos.containing(world.x, world.y, world.z);
        try {
            final BlockEntity blockEntity = BlockEntity.loadStatic(
                    worldPos, state, data, level.registryAccess());
            if (blockEntity != null) {
                blockEntity.setLevel(level);
                return new Entry(localX, localY, localZ, blockEntity);
            }
        } catch (RuntimeException ignored) {
            // Missing/invalid modded NBT; fall back to the block model.
        }
        return null;
    }

    private static long pack(int x, int y, int z) {
        return new io.mikaple.crythst.network.structure.StructureVoxelPos(x, y, z).packed();
    }

    private static final class Entry {
        private final int localX;
        private final int localY;
        private final int localZ;
        private final BlockEntity blockEntity;
        private int lastOpenCount = -1;

        private Entry(int localX, int localY, int localZ, BlockEntity blockEntity) {
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.blockEntity = blockEntity;
        }

        private long packedLocal() {
            return pack(localX, localY, localZ);
        }

        /**
         * Applies a new opener count to the vanilla chest lid controller. A
         * value of zero closes the lid; any positive value opens it, matching
         * vanilla's block event semantics.
         */
        private void syncAnimation(int openCount) {
            if (!(blockEntity instanceof ChestBlockEntity chest) || openCount < 0) {
                return;
            }
            if (openCount == lastOpenCount) {
                return;
            }
            lastOpenCount = openCount;
            chest.triggerEvent(1, openCount > 0 ? 1 : 0);
        }
    }

    private record Cached(long geometryRevision, long blockEntityRevision,
                          Map<Long, Integer> openCounts,
                          List<Entry> entries) {
    }
}
