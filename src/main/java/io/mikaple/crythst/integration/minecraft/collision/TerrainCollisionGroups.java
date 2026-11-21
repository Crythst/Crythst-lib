package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.physics.collision.CollisionFilter;

/**
 * Collision layers used by Minecraft-generated static terrain.
 *
 * <p>libbulletjme uses one-directional filtering: two objects collide if
 * <em>either</em> object's collide-with mask contains the other object's
 * group. It is therefore not enough for a gameplay body to exclude the
 * terrain bit; terrain must also exclude the body's group. Terrain keeps the
 * {@link #NO_TERRAIN_BIT} out of its mask so bodies in that group pass
 * through terrain while still colliding with ordinary bodies.</p>
 *
 * <p>The default {@link CollisionFilter#DEFAULT} body (group bit 0) still
 * collides with terrain, because terrain collides with every group except
 * {@link #NO_TERRAIN_BIT}.</p>
 */
public final class TerrainCollisionGroups {
    /**
     * Group bit reserved for terrain. Bit 15 is the least likely to clash
     * with the low bits usually used for player/entity layers.
     */
    public static final int TERRAIN_BIT = 1 << 15;

    /**
     * Group bit reserved for bodies that should pass through terrain. Terrain
     * excludes this bit from its collide-with mask.
     */
    public static final int NO_TERRAIN_BIT = 1 << 14;

    /**
     * Filter for static terrain sections: owns the terrain bit, collides with
     * every group except {@link #NO_TERRAIN_BIT}.
     */
    public static final CollisionFilter TERRAIN = new CollisionFilter(TERRAIN_BIT,
            0xFFFF & ~NO_TERRAIN_BIT);

    private TerrainCollisionGroups() {
    }

    /**
     * @return a filter that collides with terrain and with bodies in the
     *         given groups
     */
    public static CollisionFilter collidingWithTerrain(int bodyGroups) {
        return new CollisionFilter(bodyGroups, 0xFFFF);
    }

    /**
     * Filter for a body that should pass through terrain while still
     * colliding with ordinary default bodies.
     *
     * <p>Because libbulletjme allows exactly one group bit per object, the
     * returned body belongs to {@link #NO_TERRAIN_BIT} rather than to the
     * caller's normal layer.</p>
     */
    public static CollisionFilter ignoringTerrain() {
        return new CollisionFilter(NO_TERRAIN_BIT, 0xFFFF & ~TERRAIN_BIT);
    }
}
