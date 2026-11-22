package io.mikaple.crythst.api.schematic;

/**
 * Thrown when a schematic or Crythst volume definition cannot be decoded or
 * violates the configured limits.
 *
 * <p>This is an unchecked exception because schematic loading is normally done
 * during a command, placement or restore path where the caller reports the
 * failure to the player rather than handling an I/O exception.</p>
 */
public final class SchematicException extends IllegalArgumentException {
    public SchematicException(String message) {
        super(message);
    }

    public SchematicException(String message, Throwable cause) {
        super(message, cause);
    }
}
