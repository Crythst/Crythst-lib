package io.mikaple.crythst.physics;

/**
 * Thrown when the native physics backend cannot be initialised.
 */
public class PhysicsException extends RuntimeException {
    public PhysicsException(String message) {
        super(message);
    }

    public PhysicsException(String message, Throwable cause) {
        super(message, cause);
    }
}
