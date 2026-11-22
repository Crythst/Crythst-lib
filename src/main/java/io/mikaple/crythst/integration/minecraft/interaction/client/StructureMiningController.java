package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.integration.fabric.CrythstFabric;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client half of held structure mining.
 *
 * <p>The server owns progress (see {@code StructureMiningSessions}). This
 * controller only decides which mining protocol packet the attack callback
 * should send:</p>
 *
 * <ul>
 *   <li>{@link Update#START} on a fresh target/tool. The server raycasts and
 *       creates the authoritative session.</li>
 *   <li>{@link Update#TICK} on every following held tick. This is a heartbeat,
 *       not a progress claim; it also keeps the vanilla crack overlay fed
 *       locally.</li>
 *   <li>{@link Update#STOP} when the target becomes invalid or the attack key
 *       is released.</li>
 * </ul>
 *
 * <p>The local {@link MiningProgress} is used only for the crack overlay. It
 * never decides when the block breaks; the server's vanilla
 * {@code ServerPlayerGameMode} does that. Vanilla action restrictions, item
 * cooldowns and {@code ItemStack#canDestroyBlock} are still checked before a
 * session starts so blocked targets do not send pointless heartbeats.</p>
 */
public final class StructureMiningController {
    /** What the attack callback should send for the current tick. */
    public enum Update {
        /** Nothing to send. */
        NONE,
        /** Begin a new server mining session. */
        START,
        /** Keep the active session alive. */
        TICK,
        /** Abort the active server mining session. */
        STOP
    }

    /** Pure routing decision for the attack callback. */
    public enum AttackRoute {
        PASS,
        MINE,
        SHOVEL_WATER
    }

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static final MiningProgress PROGRESS = new MiningProgress();

    private static @Nullable Target currentTarget;
    private static @Nullable ItemStack currentTool;
    private static int lastTick = Integer.MIN_VALUE;
    private static boolean completed;
    private static boolean creativeStarted;
    private static boolean stopRequested;
    private static volatile @Nullable StructureBreakProgress breakProgress;

    private StructureMiningController() {
    }

    /**
     * Registers release/reset handling. Called from
     * {@link ClientStructureInteraction#register()}.
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null
                    || !client.options.keyAttack.isDown()) {
                stop();
                return;
            }
            // Defensive reset for a cancelled callback or a crosshair that
            // moved off the structure without another pre-attack invocation.
            final StructureBlockHitResult hit = ClientStructureSelection.current(client);
            if (hit == null || currentTarget == null || !currentTarget.matches(hit)
                    || !CrythstFabric.config().structureBlockInteractionEnabled) {
                stop();
            }
        });
    }

    /**
     * Advances one attack tick for {@code hit}.
     *
     * @param clickCount Fabric's click count for this tick; {@code 0} means the
     *                   key is held rather than freshly pressed
     */
    public static Update tick(Minecraft client, LocalPlayer player,
                              StructureBlockHitResult hit, int clickCount) {
        if (client == null || player == null || hit == null) {
            return stop();
        }
        if (player.tickCount == lastTick) {
            // The callback is once per tick, but a future caller (or a mod
            // chaining the event) must not advance twice.
            return Update.NONE;
        }
        lastTick = player.tickCount;

        final BlockState state = hit.blockState();
        final ItemStack tool = player.getMainHandItem();
        final Target target = Target.of(hit);
        final boolean targetChanged = currentTarget == null || !currentTarget.equals(target)
                || currentTool == null || !ItemStack.isSameItemSameComponents(currentTool, tool);

        if (targetChanged) {
            PROGRESS.reset();
            currentTarget = target;
            currentTool = tool.copy();
            completed = false;
            creativeStarted = false;
            breakProgress = null;
        }

        if (!isMineable(player, hit, state, tool)) {
            return stop();
        }

        final boolean creative = player.gameMode().isCreative();
        if (targetChanged) {
            creativeStarted = creative;
            return Update.START;
        }
        if (creative) {
            if (!creativeStarted) {
                creativeStarted = true;
                return Update.START;
            }
            return Update.TICK;
        }
        if (creativeStarted) {
            // Creative -> survival while holding the same target: the server
            // has no survival session for it yet.
            creativeStarted = false;
            return Update.START;
        }
        if (completed) {
            // Keep the server session alive until its vanilla break removes the
            // voxel and the authoritative crosshair changes.
            return Update.TICK;
        }

        final float hardness = state.getDestroySpeed(player.level(), hit.getBlockPos());
        final float destroySpeed = player.getDestroySpeed(state);
        final boolean correctTool = player.hasCorrectToolForDrops(state);
        final float perTick = MiningMath.progressPerTick(hardness, destroySpeed, correctTool);

        final MiningProgress.Step step =
                PROGRESS.tick(target, tool.getItem(), perTick, false);
        if (step == MiningProgress.Step.BREAK) {
            completed = true;
            breakProgress = null;
            // No BREAK packet exists any more: the server completes the session
            // itself. The next TICK is just a heartbeat while the block update
            // travels back.
            return Update.TICK;
        }
        if (PROGRESS.isMining()) {
            breakProgress = StructureBreakProgress.of(hit, PROGRESS.progress());
        }
        return Update.TICK;
    }

    /** Stops the current session immediately and requests a STOP packet. */
    public static Update stop() {
        final boolean wasActive = currentTarget != null || PROGRESS.hasTarget();
        clear();
        if (wasActive) {
            stopRequested = true;
            return Update.STOP;
        }
        return Update.NONE;
    }

    /** Clears local state without requesting a STOP packet. */
    public static void reset() {
        clear();
    }

    /**
     * Whether the client asked to send one STOP packet since the last poll.
     * Used by the release listener in {@link ClientStructureInteraction}.
     */
    public static boolean consumeStopRequest() {
        final boolean requested = stopRequested;
        stopRequested = false;
        return requested;
    }

    /**
     * Snapshot for the break overlay, or {@code null} when no crack should be
     * rendered.
     */
    public static @Nullable StructureBreakProgress currentProgress() {
        return breakProgress;
    }

    /**
     * Decides which attack action the current crosshair owns. A structure
     * always wins over water so a shovel cannot steal a structure click.
     */
    public static AttackRoute route(boolean structureHit, boolean waterHit) {
        if (structureHit) {
            return AttackRoute.MINE;
        }
        return waterHit ? AttackRoute.SHOVEL_WATER : AttackRoute.PASS;
    }

    private static void clear() {
        PROGRESS.reset();
        currentTarget = null;
        currentTool = null;
        completed = false;
        creativeStarted = false;
        breakProgress = null;
    }

    private static boolean isMineable(LocalPlayer player, StructureBlockHitResult hit,
                                      @Nullable BlockState state, ItemStack tool) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (hit.distance() > player.blockInteractionRange() + 1.0e-6) {
            return false;
        }
        if (!player.level().getWorldBorder().isWithinBounds(hit.getBlockPos())) {
            return false;
        }
        final GameType mode = player.gameMode();
        if (player.blockActionRestricted(player.level(), hit.getBlockPos(), mode)) {
            return false;
        }
        if (player.getCooldowns().isOnCooldown(tool)) {
            return false;
        }
        if (!tool.canDestroyBlock(state, player.level(), hit.getBlockPos(), player)) {
            return false;
        }
        // Matches vanilla destroyBlock: command blocks and other game-master
        // blocks need the creative permission flag in every game mode.
        return !(state.getBlock() instanceof GameMasterBlock)
                || player.canUseGameMasterBlocks();
    }

    /** Immutable target identity. The state identity catches block changes. */
    private record Target(long bodyId, int x, int y, int z, BlockState state) {
        static Target of(StructureBlockHitResult hit) {
            return new Target(hit.bodyId(), hit.voxelX(), hit.voxelY(), hit.voxelZ(),
                    hit.blockState());
        }

        boolean matches(StructureBlockHitResult hit) {
            return bodyId == hit.bodyId()
                    && x == hit.voxelX() && y == hit.voxelY() && z == hit.voxelZ()
                    && state == hit.blockState();
        }
    }
}
