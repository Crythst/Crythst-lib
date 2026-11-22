package io.mikaple.crythst.integration.minecraft.gameevent;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionImpulseTest {
    private static final double EPS = 1.0e-9;

    @Test
    void impulsePointsRadiallyAwayFromTheExplosion() {
        final Vector3d center = new Vector3d(10.0, 64.0, -3.0);
        final Vector3d block = new Vector3d(13.0, 64.0, -3.0);

        final Vector3d direction = ExplosionImpulse.direction(center, block);
        assertEquals(1.0, direction.x, EPS);
        assertEquals(0.0, direction.y, EPS);
        assertEquals(0.0, direction.z, EPS);

        final Vector3d impulse = ExplosionImpulse.impulse(center, block, 20.0, 3.0F, 4.0);
        assertTrue(impulse.x > 0.0);
        assertEquals(0.0, impulse.y, EPS);
        assertEquals(0.0, impulse.z, EPS);
        assertEquals(ExplosionImpulse.magnitude(20.0, 3.0F, 4.0), impulse.length(), EPS);
    }

    @Test
    void centreBlockFallsBackToAnUpwardImpulse() {
        final Vector3d center = new Vector3d(1.5, 2.5, 3.5);
        final Vector3d direction = ExplosionImpulse.direction(center, new Vector3d(center));
        assertEquals(0.0, direction.x, EPS);
        assertEquals(1.0, direction.y, EPS);
        assertEquals(0.0, direction.z, EPS);
    }

    @Test
    void magnitudeScalesWithMassAndRemainingPower() {
        final double light = ExplosionImpulse.magnitude(10.0, 1.0F, 4.0);
        final double heavy = ExplosionImpulse.magnitude(20.0, 1.0F, 4.0);
        final double strong = ExplosionImpulse.magnitude(10.0, 4.0F, 4.0);

        assertTrue(heavy > light);
        assertTrue(strong > light);
        assertEquals(0.0, ExplosionImpulse.magnitude(0.0, 4.0F, 4.0), EPS);
        assertEquals(0.0, ExplosionImpulse.magnitude(10.0, 4.0F, 0.0), EPS);
    }

    @Test
    void approximatePowerFallsOffWithDistance() {
        assertEquals(4.0F, ExplosionImpulse.approximatePower(4.0, 0.0), 1.0e-6F);
        assertEquals(2.0F, ExplosionImpulse.approximatePower(4.0, 4.0), 1.0e-6F);
        assertEquals(0.0F, ExplosionImpulse.approximatePower(4.0, 8.0), 1.0e-6F);
    }
}
