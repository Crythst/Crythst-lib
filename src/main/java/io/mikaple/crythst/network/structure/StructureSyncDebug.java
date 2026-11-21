package io.mikaple.crythst.network.structure;

import io.mikaple.crythst.Crythst;

/**
 * Gated diagnostics for structure synchronisation.
 *
 * <p>Enable with {@code -Dcrythst.debug.structureSync=true}. Logging stays
 * completely silent otherwise. Status strings are always available so a
 * command or debug overlay can call them without enabling packet logs.</p>
 */
public final class StructureSyncDebug {
    public static final String PROPERTY = "crythst.debug.structureSync";

    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private StructureSyncDebug() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void log(String message, Object... arguments) {
        if (ENABLED) {
            Crythst.LOGGER.info("[structure-sync] " + message, arguments);
        }
    }
}
