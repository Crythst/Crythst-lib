package io.mikaple.crythst.api.sublevel.ticket;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure reference-count bookkeeping for chunk loading tickets.
 *
 * <p>Not thread-safe by itself; {@link StructureLoadingTickets.LevelState}
 * guards it with its own lock. Keeping this separate makes the add/remove
 * lifecycle testable without a live server level.</p>
 */
final class TicketReferenceLedger {
    private final Map<Long, Integer> references = new LinkedHashMap<>();

    /**
     * Retains one reference.
     *
     * @return {@code true} when this is the first reference and the caller must
     *         add the real ticket
     */
    boolean retain(long key) {
        final int current = references.getOrDefault(key, 0);
        references.put(key, current + 1);
        return current == 0;
    }

    /**
     * Releases one reference.
     *
     * @return {@code true} when the last reference was removed and the caller
     *         must remove the real ticket; {@code false} for an unknown key
     */
    boolean release(long key) {
        final Integer current = references.get(key);
        if (current == null) {
            return false;
        }
        if (current <= 1) {
            references.remove(key);
            return true;
        }
        references.put(key, current - 1);
        return false;
    }

    /** Number of distinct pinned keys. */
    int size() {
        return references.size();
    }

    /** Current reference count for a key, or {@code 0}. */
    int count(long key) {
        return references.getOrDefault(key, 0);
    }

    /** Clears all references. */
    void clear() {
        references.clear();
    }
}
