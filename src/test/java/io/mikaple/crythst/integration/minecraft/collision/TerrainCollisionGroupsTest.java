package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.physics.collision.CollisionFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainCollisionGroupsTest {
    /**
     * Mirrors libbulletjme: objects collide when either mask contains the
     * other object's group.
     */
    private static boolean collide(CollisionFilter a, CollisionFilter b) {
        return (a.collidesWith() & b.group()) != 0
                || (b.collidesWith() & a.group()) != 0;
    }

    @Test
    void defaultBodiesCollideWithTerrain() {
        assertTrue(collide(CollisionFilter.DEFAULT, TerrainCollisionGroups.TERRAIN));
        assertTrue(collide(TerrainCollisionGroups.TERRAIN, CollisionFilter.DEFAULT));
    }

    @Test
    void ignoringTerrainBodyPassesThroughTerrain() {
        final CollisionFilter ghost = TerrainCollisionGroups.ignoringTerrain();
        assertFalse(ghost.collidesWith(TerrainCollisionGroups.TERRAIN),
                "the two-way record helper should also report no collision");
        assertFalse(collide(ghost, TerrainCollisionGroups.TERRAIN));
    }

    @Test
    void ignoringTerrainBodyStillCollidesWithOrdinaryBodies() {
        final CollisionFilter ghost = TerrainCollisionGroups.ignoringTerrain();
        assertTrue(collide(ghost, CollisionFilter.DEFAULT));
        assertTrue(collide(CollisionFilter.DEFAULT, ghost));
    }

    @Test
    void terrainDoesNotChangeDefaultBodyToBodyPairs() {
        assertTrue(collide(CollisionFilter.DEFAULT, CollisionFilter.DEFAULT));
        final CollisionFilter layerTwo = new CollisionFilter(1 << 1, 0xFFFF);
        assertTrue(collide(layerTwo, CollisionFilter.DEFAULT));
        assertTrue(collide(layerTwo, TerrainCollisionGroups.TERRAIN));
    }
}
