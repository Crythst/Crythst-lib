package io.mikaple.crythst.integration.minecraft.gameevent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small bounded duplicate filter for bridged game events.
 *
 * <p>Several vanilla paths emit the same logical event twice (for example a
 * block update and a level event for one interaction). Forwarding both would
 * make a host sculk sensor or note-block listener fire twice. The filter keys
 * on event id, containing host block and source entity id; an identical key is
 * dropped only inside the configured tick window.</p>
 */
public final class GameEventDedup {
    /** Default window: exact duplicates in one server tick only. */
    public static final int DEFAULT_WINDOW_TICKS = 0;
    /** Default upper bound on remembered keys. */
    public static final int DEFAULT_MAX_ENTRIES = 4096;

    /**
     * Duplicate identity.
     *
     * @param eventId        registry id of the game event
     * @param blockPos       packed host block position of the mapped event
     * @param sourceEntityId source entity id, or {@code -1} for no source
     */
    public record Key(String eventId, long blockPos, int sourceEntityId) {
        public Key {
            if (eventId == null) {
                throw new NullPointerException("eventId");
            }
        }
    }

    private final long windowTicks;
    private final int maxEntries;
    private final LinkedHashMap<Key, Long> lastForwardedTick = new LinkedHashMap<>();

    public GameEventDedup() {
        this(DEFAULT_WINDOW_TICKS, DEFAULT_MAX_ENTRIES);
    }

    public GameEventDedup(long windowTicks, int maxEntries) {
        this.windowTicks = Math.max(0L, windowTicks);
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * Records a candidate and reports whether it should be forwarded.
     *
     * @return {@code false} when an identical key was forwarded within the
     *         window, {@code true} otherwise
     */
    public boolean shouldForward(Key key, long gameTime) {
        if (key == null) {
            return false;
        }
        final Long previous = lastForwardedTick.get(key);
        if (previous != null && gameTime >= previous
                && gameTime - previous <= windowTicks) {
            return false;
        }
        lastForwardedTick.put(key, gameTime);
        trim();
        return true;
    }

    public int size() {
        return lastForwardedTick.size();
    }

    public void clear() {
        lastForwardedTick.clear();
    }

    private void trim() {
        if (lastForwardedTick.size() <= maxEntries) {
            return;
        }
        final Iterator<Map.Entry<Key, Long>> iterator = lastForwardedTick.entrySet().iterator();
        while (lastForwardedTick.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
