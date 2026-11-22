package io.mikaple.crythst.integration.minecraft.gameevent;

import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Forwards game events emitted by the auxiliary void level to the host world.
 *
 * <p>The auxiliary level has no players and is not part of the host level's
 * game-event dispatcher. Sculk sensors, vibration listeners, note blocks,
 * block place/break events and any mod listener therefore never see events
 * that happen inside a structure copy. This bridge runs on every auxiliary
 * {@code gameEvent} call, maps the event position through the owning
 * structure's pose and posts it to the host level.</p>
 *
 * <p>Re-entrancy is suppressed with a thread-local depth: if a host listener
 * triggers another auxiliary event while the bridge is posting, that event is
 * not forwarded again. The filter also refuses an exact duplicate event key
 * (event id + mapped block position + source entity) within the same tick, so
 * vanilla paths that emit the same logical event twice do not double-trigger
 * host sensors.</p>
 */
public final class StructureGameEvents {
    private static final Map<Level, GameEventDedup> DEDUP =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Integer> BRIDGE_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private StructureGameEvents() {
    }

    /**
     * Called from the auxiliary {@code ServerLevel#gameEvent} override.
     *
     * @param owner    owning auxiliary level, or {@code null}
     * @param event    event holder
     * @param position event position in auxiliary coordinates
     * @param context  vanilla context
     */
    public static void forwardFromAuxiliary(
            @Nullable EmptyPhysicsLevel owner,
            @Nullable Holder<GameEvent> event,
            @Nullable Vec3 position,
            @Nullable GameEvent.Context context) {
        if (owner == null || owner.closed() || event == null || position == null) {
            return;
        }
        if (isBridgeActive()) {
            // A host listener re-entered the auxiliary level while this bridge
            // was posting. Do not echo that event back to the host.
            return;
        }
        final Vec3 mapped = owner.projectAuxiliaryPoint(
                position.x, position.y, position.z);
        if (mapped == null) {
            // Outside every copied structure region: keep the event inside the
            // void level instead of dropping it at an arbitrary host point.
            return;
        }
        final ServerLevel host = owner.hostLevel();
        if (host == null || host.isClientSide()) {
            return;
        }
        final GameEvent.Context mappedContext = mapContext(host, context);
        final int sourceId = mappedContext.sourceEntity() == null
                ? -1 : mappedContext.sourceEntity().getId();
        final GameEventDedup.Key key = new GameEventDedup.Key(
                event.getRegisteredName(),
                GameEventCoordinates.blockKey(mapped.x, mapped.y, mapped.z),
                sourceId);
        final GameEventDedup dedup;
        synchronized (DEDUP) {
            dedup = DEDUP.computeIfAbsent(host, ignored -> new GameEventDedup());
        }
        if (!dedup.shouldForward(key, host.getGameTime())) {
            return;
        }
        final int depth = BRIDGE_DEPTH.get();
        BRIDGE_DEPTH.set(depth + 1);
        try {
            host.gameEvent(event, mapped, mappedContext);
        } finally {
            if (depth == 0) {
                BRIDGE_DEPTH.remove();
            } else {
                BRIDGE_DEPTH.set(depth);
            }
        }
    }

    /**
     * Whether the current thread is already inside a host event dispatch
     * started by this bridge. Used by other bridges that need to avoid an
     * echo loop.
     */
    public static boolean isBridgeActive() {
        return BRIDGE_DEPTH.get() > 0;
    }

    /**
     * Drops a source entity that belongs to the auxiliary level. Host
     * listeners must never receive a void entity reference; the affected block
     * state remains available.
     */
    private static GameEvent.Context mapContext(ServerLevel host,
                                                 @Nullable GameEvent.Context context) {
        if (context == null) {
            return GameEvent.Context.of((Entity) null);
        }
        final Entity source = context.sourceEntity();
        final Entity mappedSource = source != null && !source.isRemoved()
                && source.level() == host ? source : null;
        return new GameEvent.Context(mappedSource, context.affectedState());
    }

    /** Clears the per-level duplicate filters. Diagnostics/tests only. */
    public static void clearDedup() {
        synchronized (DEDUP) {
            DEDUP.clear();
        }
    }
}
