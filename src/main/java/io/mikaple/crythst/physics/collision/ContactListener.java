package io.mikaple.crythst.physics.collision;

/**
 * Receives contact manifolds once per fixed step. Invoked on the physics thread,
 * after the solver has run.
 */
@FunctionalInterface
public interface ContactListener {
    void onContact(ContactEvent event);
}
