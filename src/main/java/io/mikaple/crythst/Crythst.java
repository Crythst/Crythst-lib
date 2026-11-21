package io.mikaple.crythst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global constants and the shared logger for Crythst.
 *
 * <p>Crythst is a library mod: feature mods depend on it instead of
 * re-implementing structure physics. The public API lives below
 * {@code io.mikaple.crythst.api}; implementation details are kept in
 * {@code io.mikaple.crythst.physics} and its sub-packages.</p>
 */
public final class Crythst {
    public static final String MOD_ID = "crythst";
    public static final String MOD_NAME = "Crythst";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private Crythst() {
    }
}
