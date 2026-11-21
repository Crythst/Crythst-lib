package io.mikaple.crythst.api.math;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class StructureMathTest {
    private static final double EPSILON = 1.0e-9;
    private static final double FLOAT_EPSILON = 1.0e-6;

    @Test
    void centerIsHalfAMetreFromTheLowerCorner() {
        final BlockPos pos = new BlockPos(-3, 64, 7);
        final Vector3d center = StructureMath.atCenterOf(pos);
        final Vector3d lower = StructureMath.atLowerCornerOf(pos);

        assertEquals(-2.5, center.x, EPSILON);
        assertEquals(64.5, center.y, EPSILON);
        assertEquals(7.5, center.z, EPSILON);
        assertEquals(-3.0, lower.x, EPSILON);
        assertEquals(64.0, lower.y, EPSILON);
        assertEquals(7.0, lower.z, EPSILON);
        assertEquals(0.5, center.x - lower.x, EPSILON);
        assertEquals(0.5, center.y - lower.y, EPSILON);
        assertEquals(0.5, center.z - lower.z, EPSILON);
        assertNotSame(StructureMath.ZERO, lower);
    }

    @Test
    void destinationOverloadsOverwriteAndReturnTheDestination() {
        final BlockPos pos = new BlockPos(1, 2, 3);
        final Vector3d centerDest = new Vector3d(99.0, 99.0, 99.0);
        assertSame(centerDest, StructureMath.atCenterOf(pos, centerDest));
        assertEquals(1.5, centerDest.x, EPSILON);
        assertEquals(2.5, centerDest.y, EPSILON);
        assertEquals(3.5, centerDest.z, EPSILON);

        final Vector3d lowerDest = new Vector3d(99.0, 99.0, 99.0);
        assertSame(lowerDest, StructureMath.atLowerCornerOf(pos, lowerDest));
        assertEquals(1.0, lowerDest.x, EPSILON);
        assertEquals(2.0, lowerDest.y, EPSILON);
        assertEquals(3.0, lowerDest.z, EPSILON);
    }

    @Test
    void vectorConversionsRoundTripWithoutChangingCoordinates() {
        final Vector3d original = new Vector3d(1.25, -2.5, 3.75);

        final Vec3 mojang = StructureMath.toMojang(original);
        assertEquals(original.x, mojang.x, EPSILON);
        assertEquals(original.y, mojang.y, EPSILON);
        assertEquals(original.z, mojang.z, EPSILON);

        final Vector3d roundTrip = StructureMath.toJoml(mojang);
        assertEquals(original.x, roundTrip.x, EPSILON);
        assertEquals(original.y, roundTrip.y, EPSILON);
        assertEquals(original.z, roundTrip.z, EPSILON);
        assertNotSame(original, roundTrip);
    }

    @Test
    void jomlDestinationOverloadAcceptsTheSourceAsDestination() {
        final Vector3d value = new Vector3d(4.0, 5.0, 6.0);
        final Vector3d dest = new Vector3d();
        assertSame(dest, StructureMath.toJoml(value, dest));
        assertEquals(4.0, dest.x, EPSILON);
        assertEquals(5.0, dest.y, EPSILON);
        assertEquals(6.0, dest.z, EPSILON);

        assertSame(value, StructureMath.toJoml(value, value));
        assertEquals(4.0, value.x, EPSILON);
        assertEquals(5.0, value.y, EPSILON);
        assertEquals(6.0, value.z, EPSILON);
    }

    @Test
    void quaternionConversionsRoundTripWithinFloatPrecision() {
        final Quaterniond original = new Quaterniond()
                .rotateY(Math.toRadians(35.0))
                .rotateX(Math.toRadians(-20.0))
                .normalize();

        final Quaternionf mojang = StructureMath.toMojang(original);
        final Quaterniond roundTrip = StructureMath.toJoml(mojang);

        assertEquals(original.x, roundTrip.x, FLOAT_EPSILON);
        assertEquals(original.y, roundTrip.y, FLOAT_EPSILON);
        assertEquals(original.z, roundTrip.z, FLOAT_EPSILON);
        assertEquals(original.w, roundTrip.w, FLOAT_EPSILON);
    }

    @Test
    void constantsAreOriginAndIdentity() {
        assertEquals(0.0, StructureMath.ZERO.x(), EPSILON);
        assertEquals(0.0, StructureMath.ZERO.y(), EPSILON);
        assertEquals(0.0, StructureMath.ZERO.z(), EPSILON);

        assertEquals(0.0, StructureMath.QUAT_IDENTITY.x(), EPSILON);
        assertEquals(0.0, StructureMath.QUAT_IDENTITY.y(), EPSILON);
        assertEquals(0.0, StructureMath.QUAT_IDENTITY.z(), EPSILON);
        assertEquals(1.0, StructureMath.QUAT_IDENTITY.w(), EPSILON);
    }
}
