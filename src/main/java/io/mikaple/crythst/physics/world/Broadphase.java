package io.mikaple.crythst.physics.world;

/**
 * Bullet broad-phase algorithms exposed through Crythst.
 */
public enum Broadphase {
    /** Dynamic AABB tree; best general-purpose choice. */
    DBVT,
    /** Sweep-and-prune along one axis. */
    AXIS_SWEEP_3,
    /** Simple brute-force broad phase; useful for debugging only. */
    SIMPLE
}
