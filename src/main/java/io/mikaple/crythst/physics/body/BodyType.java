package io.mikaple.crythst.physics.body;

/**
 * Simulation behaviour of a rigid body.
 */
public enum BodyType {
    /**
     * Infinite mass, never moves, unaffected by forces and gravity.
     */
    STATIC(true, false),
    /**
     * Infinite mass, moved explicitly by gameplay code; pushes dynamic bodies.
     */
    KINEMATIC(true, true),
    /**
     * Finite mass, driven by gravity, forces and contacts.
     */
    DYNAMIC(false, true);

    private final boolean infiniteMass;
    private final boolean scriptMoved;

    BodyType(boolean infiniteMass, boolean scriptMoved) {
        this.infiniteMass = infiniteMass;
        this.scriptMoved = scriptMoved;
    }

    public boolean infiniteMass() {
        return infiniteMass;
    }

    public boolean scriptMoved() {
        return scriptMoved;
    }
}
