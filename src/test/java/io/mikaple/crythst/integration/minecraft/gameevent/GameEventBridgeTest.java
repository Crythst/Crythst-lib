package io.mikaple.crythst.integration.minecraft.gameevent;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEventBridgeTest {
    private static final double EPS = 1.0e-9;

    @Test
    void auxiliaryEventPointMapsThroughPoseScaleAndAnchor() {
        final Transform3d pose = new Transform3d(
                new Vector3d(30.0, 70.0, -5.0),
                new Quaterniond().rotateY(0.7).rotateX(0.2));
        final int originX = 64;
        final int originY = 0;
        final int originZ = 128;
        final double scale = 1.75;

        final Vector3d actual = GameEventCoordinates.map(pose, scale,
                originX, originY, originZ, 65.25, 2.5, 129.75);
        final Vector3d expected = pose.transformPoint(new Vector3d(
                (65.25 - originX) * scale,
                (2.5 - originY) * scale,
                (129.75 - originZ) * scale));

        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);

        final Vector3d direction = new Vector3d(0.2, -0.4, 0.7);
        final Vector3d mappedDirection = GameEventCoordinates.mapDirection(pose, direction);
        assertEquals(direction.length(), mappedDirection.length(), EPS,
                "a rotation must not scale an event direction");
    }

    @Test
    void identityPoseUsesLocalMetricOffsets() {
        final Vector3d actual = GameEventCoordinates.map(Transform3d.identity(), 2.0,
                10, -4, 20, 12.0, -2.0, 22.0);
        assertEquals(4.0, actual.x, EPS);
        assertEquals(4.0, actual.y, EPS);
        assertEquals(4.0, actual.z, EPS);
    }

    @Test
    void blockKeyFloorsNegativeCoordinatesAndIsStable() {
        final long key = GameEventCoordinates.blockKey(-1.0e-6, 2.999, -3.2);
        assertEquals(GameEventCoordinates.packBlockPos(-1, 2, -4), key);
        assertEquals(key, GameEventCoordinates.blockKey(-0.1, 2.0, -3.9));
        assertFalse(key == GameEventCoordinates.blockKey(0, 2, -4),
                "a different block must have a different key");
    }

    @Test
    void duplicateEventKeyIsSuppressedForTheWindowOnly() {
        final GameEventDedup dedup = new GameEventDedup();
        final GameEventDedup.Key key = new GameEventDedup.Key(
                "minecraft:block_place",
                GameEventCoordinates.packBlockPos(1, 2, 3), 7);

        assertTrue(dedup.shouldForward(key, 100L));
        assertFalse(dedup.shouldForward(key, 100L),
                "the same event in the same tick is a duplicate");
        assertTrue(dedup.shouldForward(key, 101L),
                "the default window is one tick (no cross-tick suppression)");

        final GameEventDedup wide = new GameEventDedup(2, 16);
        assertTrue(wide.shouldForward(key, 100L));
        assertFalse(wide.shouldForward(key, 101L));
        assertFalse(wide.shouldForward(key, 102L));
        assertTrue(wide.shouldForward(key, 103L));
    }

    @Test
    void differentEventPositionOrSourceIsNotADuplicate() {
        final GameEventDedup dedup = new GameEventDedup(4, 16);
        final GameEventDedup.Key base = new GameEventDedup.Key(
                "minecraft:note_block_play",
                GameEventCoordinates.packBlockPos(1, 2, 3), 7);

        assertTrue(dedup.shouldForward(base, 10L));
        assertTrue(dedup.shouldForward(new GameEventDedup.Key(
                "minecraft:block_break", base.blockPos(), base.sourceEntityId()), 11L));
        assertTrue(dedup.shouldForward(new GameEventDedup.Key(
                base.eventId(), GameEventCoordinates.packBlockPos(1, 2, 4),
                base.sourceEntityId()), 11L));
        assertTrue(dedup.shouldForward(new GameEventDedup.Key(
                base.eventId(), base.blockPos(), 8), 11L));
    }

    @Test
    void dedupStoreIsBounded() {
        final GameEventDedup dedup = new GameEventDedup(0, 2);
        for (int i = 0; i < 5; i++) {
            assertTrue(dedup.shouldForward(new GameEventDedup.Key(
                    "minecraft:block_place",
                    GameEventCoordinates.packBlockPos(i, 0, 0), -1), i));
        }
        assertEquals(2, dedup.size());
        dedup.clear();
        assertEquals(0, dedup.size());
    }
}
