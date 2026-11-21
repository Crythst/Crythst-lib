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
 * Client-side vanilla-like mining controller for physics-structure voxels.
 *
 * <p>This replaces the old "instant break on click, then one voxel every five
 * ticks" shortcut. The controller is driven once per client tick by
 * {@code ClientPreAttackCallback} while the attack key is held:</p>
 *
 * <ul>
 *   <li>Creative breaks a valid target with one {@code MINE} packet and does
 *       not repeat until the crosshair reaches another voxel.</li>
 *   <li>Survival accumulates {@code state.getDestroyProgress(...)}-equivalent
 *       progress from hardness, held-tool speed, enchantments and mob effects;
 *       {@code MINE} is sent only when progress reaches {@code 1.0}.</li>
 *   <li>Releasing attack, moving to another voxel, changing the held item,
 *       leaving reach, losing the structure hit or a state change resets the
 *       session.</li>
 *   <li>Vanilla action restrictions, item cooldowns and
 *       {@code ItemStack#canDestroyBlock} are checked before progress starts.</li>
 * </ul>
 *
 * <p>The controller publishes an immutable {@link StructureBreakProgress}
 * snapshot for {@code StructureBreakOverlay}. It never touches vanilla
 * {@code blockDestruction} state, because a rotated or scaled structure voxel
 * does not have a matching axis-aligned real block position.</p>
 */
public final class StructureMiningController {
    /** What the attack callback should do with the current tick. */
    public enum Update {
        /** Nothing to do; the callback should still suppress vanilla mining. */
        NONE,
        /** A new survival target was started; swing once but send no packet. */
        STARTED,
        /** Progress was accumulated; no packet is sent. */
        PROGRESSING,
        /** Progress reached 1.0, or creative mode broke the target. Send MINE. */
        BREAK
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
                reset();
                return;
            }
            // Defensive reset for a cancelled callback or a crosshair that
            // moved off the structure without another pre-attack invocation.
            final StructureBlockHitResult hit = ClientStructureSelection.current(client);
            if (hit == null || currentTarget == null || !currentTarget.matches(hit)
                    || !CrythstFabric.config().structureBlockInteractionEnabled) {
                reset();
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
            reset();
            return Update.NONE;
        }
        if (player.tickCount == lastTick) {
            // The callback is once per tick, but a future caller (or a mod
            // chaining the event) must not advance progress twice.
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
            breakProgress = null;
        }

        if (!isMineable(player, hit, state, tool)) {
            PROGRESS.reset();
            breakProgress = null;
            return Update.NONE;
        }

        final float hardness = state.getDestroySpeed(player.level(), hit.getBlockPos());
        final float destroySpeed = player.getDestroySpeed(state);
        final boolean correctTool = player.hasCorrectToolForDrops(state);
        final float perTick = MiningMath.progressPerTick(hardness, destroySpeed, correctTool);
        final boolean creative = player.gameMode().isCreative();

        final MiningProgress.Step step = PROGRESS.tick(target, tool.getItem(), perTick, creative);
        if (step == MiningProgress.Step.BREAK) {
            breakProgress = null;
            return Update.BREAK;
        }
        if (PROGRESS.isMining()) {
            breakProgress = StructureBreakProgress.of(hit, PROGRESS.progress());
            return targetChanged ? Update.STARTED : Update.PROGRESSING;
        }
        breakProgress = null;
        return targetChanged ? Update.STARTED : Update.NONE;
    }

    /** Stops the current session immediately. */
    public static void reset() {
        PROGRESS.reset();
        currentTarget = null;
        currentTool = null;
        breakProgress = null;
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
