package io.mikaple.crythst.client.render.block;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side driver for {@link SpecialBlockRenderer}.
 *
 * <p>This is the non-block-entity alternative to a
 * {@code BlockEntityRenderer}: a client-tick scanner finds registered blocks
 * in loaded chunks around the player and caches their immutable positions and
 * block states.
 * A {@code LevelRenderEvents.BEFORE_GIZMOS} listener then submits every cached
 * block with camera-relative coordinates.</p>
 *
 * <p>The scanner never loads chunks. It uses
 * {@link ClientChunkCache#getChunkNow(int, int)} and only scans the chunk
 * square covering {@link #SCAN_RADIUS} blocks around the player. Cached entries
 * are pruned on every tick when their chunk unloads, their block state changes,
 * their renderer is removed from the registry, or they leave the scan radius.
 * A full rescan runs once every {@link #SCAN_INTERVAL_TICKS} ticks.</p>
 *
 * <p>The render path only reads the concurrent cache, samples light, and
 * submits models. It does not mutate the world and does not resolve chunks.</p>
 */
public final class SpecialBlockRenderManager {
    /** How often the loaded chunks around the player are rescanned. */
    public static final int SCAN_INTERVAL_TICKS = 20;

    /** Horizontal scan radius in blocks, measured from the player. */
    public static final double SCAN_RADIUS = 64.0;

    /** Safety cap so a pathological world cannot grow the cache without bound. */
    public static final int MAX_TRACKED_BLOCKS = 8192;

    private static final double SCAN_RADIUS_SQR = SCAN_RADIUS * SCAN_RADIUS;

    private static final Map<BlockPos, BlockState> TRACKED = new ConcurrentHashMap<>();

    private static volatile ClientLevel trackedLevel;
    private static int ticksSinceScan;
    private static boolean registered;

    private SpecialBlockRenderManager() {
    }

    /**
     * Registers the client-tick scanner and the level render hook.
     *
     * <p>Call once from the client initializer, after (or before) registering
     * the individual block renderers. Repeated calls are ignored.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(SpecialBlockRenderManager::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(SpecialBlockRenderManager::render);
    }

    /**
     * @return the number of blocks currently tracked for rendering
     */
    public static int trackedBlockCount() {
        return TRACKED.size();
    }

    /**
     * Drops all cached blocks. Called automatically on dimension/world change.
     */
    public static void clear() {
        TRACKED.clear();
        trackedLevel = null;
        ticksSinceScan = 0;
    }

    private static void tick(Minecraft minecraft) {
        final ClientLevel level = minecraft.level;
        if (level != trackedLevel) {
            TRACKED.clear();
            trackedLevel = level;
            ticksSinceScan = 0;
        }
        if (level == null) {
            return;
        }

        final LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        final Vec3 center = player.position();
        prune(level, center);

        if (ticksSinceScan++ % SCAN_INTERVAL_TICKS == 0) {
            scan(level, center);
        }
    }

    /**
     * Removes entries that are no longer renderable. Runs every client tick so
     * a broken or replaced block disappears immediately, even between full
     * scans.
     */
    private static void prune(ClientLevel level, Vec3 center) {
        final double centerX = center.x;
        final double centerY = center.y;
        final double centerZ = center.z;

        TRACKED.entrySet().removeIf(entry -> {
            final BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos)) {
                return true;
            }

            final BlockState currentState = level.getBlockState(pos);
            if (currentState != entry.getValue()) {
                return true;
            }
            if (!SpecialBlockRenderers.isRegistered(currentState.getBlock())) {
                return true;
            }

            final double dx = pos.getX() + 0.5 - centerX;
            final double dy = pos.getY() + 0.5 - centerY;
            final double dz = pos.getZ() + 0.5 - centerZ;
            return dx * dx + dy * dy + dz * dz > SCAN_RADIUS_SQR;
        });
    }

    /**
     * Scans every loaded chunk intersecting the configured radius. Chunk
     * iteration is bounded to the chunk square around the player, so this never
     * walks the whole dimension.
     */
    private static void scan(ClientLevel level, Vec3 center) {
        if (TRACKED.size() >= MAX_TRACKED_BLOCKS || SpecialBlockRenderers.isEmpty()) {
            return;
        }

        final ClientChunkCache chunks = level.getChunkSource();
        final int minChunkX = SectionPos.blockToSectionCoord(center.x - SCAN_RADIUS);
        final int maxChunkX = SectionPos.blockToSectionCoord(center.x + SCAN_RADIUS);
        final int minChunkZ = SectionPos.blockToSectionCoord(center.z - SCAN_RADIUS);
        final int maxChunkZ = SectionPos.blockToSectionCoord(center.z + SCAN_RADIUS);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final LevelChunk chunk = chunks.getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                chunk.findBlocks(
                        state -> SpecialBlockRenderers.isRegistered(state.getBlock()),
                        (pos, state) -> track(pos, state, center));
            }
        }
    }

    private static void track(BlockPos pos, BlockState state, Vec3 center) {
        if (TRACKED.size() >= MAX_TRACKED_BLOCKS) {
            return;
        }

        final double dx = pos.getX() + 0.5 - center.x;
        final double dy = pos.getY() + 0.5 - center.y;
        final double dz = pos.getZ() + 0.5 - center.z;
        if (dx * dx + dy * dy + dz * dz > SCAN_RADIUS_SQR) {
            return;
        }

        if (SpecialBlockRenderers.isRegistered(state.getBlock())) {
            // BlockPos is immutable after this call; the render thread never
            // sees the scanner's temporary position instances.
            TRACKED.put(pos.immutable(), state);
        }
    }

    /**
     * Submits all cached blocks from {@code LevelRenderEvents.BEFORE_GIZMOS}.
     */
    private static void render(LevelRenderContext context) {
        if (TRACKED.isEmpty() || SpecialBlockRenderers.isEmpty()) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null || level != trackedLevel) {
            return;
        }

        final SubmitNodeCollector collector = context.submitNodeCollector();
        final PoseStack poseStack = context.poseStack();
        if (collector == null || poseStack == null) {
            return;
        }

        final Vec3 cameraPos = cameraPosition(context, minecraft);
        if (cameraPos == null) {
            return;
        }

        final float partialTick = minecraft.getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);

        for (Map.Entry<BlockPos, BlockState> entry : TRACKED.entrySet()) {
            final BlockPos pos = entry.getKey();
            final BlockState state = entry.getValue();

            final SpecialBlockRenderer renderer = SpecialBlockRenderers.get(state.getBlock());
            if (renderer == null) {
                continue;
            }

            final double offsetX = pos.getX() - cameraPos.x;
            final double offsetY = pos.getY() - cameraPos.y;
            final double offsetZ = pos.getZ() - cameraPos.z;

            // Distance is measured to the block centre, while the translation is
            // to its minimum corner (the convention expected by the renderer).
            final double centerX = offsetX + 0.5;
            final double centerY = offsetY + 0.5;
            final double centerZ = offsetZ + 0.5;

            final SpecialBlockRenderContext blockContext = new SpecialBlockRenderContext(
                    pos, state, level, partialTick, cameraPos,
                    centerX * centerX + centerY * centerY + centerZ * centerZ);

            final int lightCoords = SpecialBlockLight.getLightCoords(level, pos, state);

            poseStack.pushPose();
            poseStack.translate(offsetX, offsetY, offsetZ);
            renderer.submit(blockContext, poseStack, collector,
                    lightCoords, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
    }

    /**
     * Prefers the camera render state extracted for this frame; the live camera
     * is only a fallback (for example if another renderer reuses the event
     * outside a normal level pass).
     */
    private static Vec3 cameraPosition(LevelRenderContext context, Minecraft minecraft) {
        final LevelRenderState levelState = context.levelState();
        final CameraRenderState camera = levelState == null ? null : levelState.cameraRenderState;
        if (camera != null && camera.pos != null) {
            return camera.pos;
        }
        return minecraft.gameRenderer.mainCamera().position();
    }

}
