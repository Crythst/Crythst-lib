package io.mikaple.crythst.network.structure;

/**
 * Thrown when a structure cannot be represented inside the wire limits.
 *
 * <p>The server catches this per structure and logs a warning instead of
 * disconnecting a client with an oversized packet.</p>
 */
public final class StructureTooLargeException extends RuntimeException {
    public StructureTooLargeException(String message) {
        super(message);
    }
}
