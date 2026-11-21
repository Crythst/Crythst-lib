package io.mikaple.crythst.integration.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server must not fight the client's prediction on ordinary contact: a
 * teleport resets the player's velocity and is the root of the "sticky"
 * feeling. This pins the authority decision without needing a live server.
 */
class EntityPhysicsCollisionAuthorityTest {
    @Test
    void ordinaryContactIsLeftToAPredictingClient() {
        assertFalse(EntityPhysicsCollisionManager.shouldTeleportPlayer(true, 0.0));
        assertFalse(EntityPhysicsCollisionManager.shouldTeleportPlayer(true, 0.1));
        assertFalse(EntityPhysicsCollisionManager.shouldTeleportPlayer(true,
                EntityPhysicsCollisionManager.PLAYER_PREDICTION_TELEPORT_THRESHOLD));
    }

    @Test
    void grossPenetrationForcesATeleportEvenWithPrediction() {
        assertTrue(EntityPhysicsCollisionManager.shouldTeleportPlayer(true,
                EntityPhysicsCollisionManager.PLAYER_PREDICTION_TELEPORT_THRESHOLD + 1.0e-6));
    }

    @Test
    void aClientWithoutPredictionIsAlwaysTeleported() {
        assertTrue(EntityPhysicsCollisionManager.shouldTeleportPlayer(false, 0.001));
        assertTrue(EntityPhysicsCollisionManager.shouldTeleportPlayer(false, 0.0));
    }
}
