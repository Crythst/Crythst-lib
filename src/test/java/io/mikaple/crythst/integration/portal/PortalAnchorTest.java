package io.mikaple.crythst.integration.portal;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Math tests for {@link PortalAnchor}: the anchor stores a portal plane in the
 * carrier's local space, so the world opening must be reproduced exactly for
 * every carrier pose.
 */
class PortalAnchorTest {
    private static final double EPS = 1.0e-9;
    private static final long CARRIER = 7L;
    /**
     * Built without touching {@code Level}'s static initialiser: these are pure
     * math tests and must run without {@code Bootstrap.bootStrap()}.
     */
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("minecraft", "overworld"));

    private static PortalPlane worldPlane() {
        return new PortalPlane(
                new Vector3d(10.0, 64.0, 3.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    private static PortalPlane otherPlane() {
        return new PortalPlane(
                new Vector3d(40.0, 80.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
    }

    @Test
    void identityPoseRoundTripsThePlane() {
        final PortalPlane original = worldPlane();
        final PortalAnchor anchor = PortalAnchor.at(CARRIER, original, Transform3d.IDENTITY);
        final PortalPlane sampled = anchor.worldPlane(Transform3d.IDENTITY);

        assertVectorEquals(original.origin(), sampled.origin());
        assertVectorEquals(original.normal(), sampled.normal());
        assertVectorEquals(original.right(), sampled.right());
        assertEquals(original.width(), sampled.width(), EPS);
        assertEquals(original.height(), sampled.height(), EPS);
    }

    @Test
    void translationMovesTheOpeningWithoutRotatingIt() {
        final PortalPlane original = worldPlane();
        final PortalAnchor anchor = PortalAnchor.at(CARRIER, original, Transform3d.IDENTITY);
        final Transform3d moved = Transform3d.of(new Vector3d(5.0, -2.0, 1.0));

        final PortalPlane sampled = anchor.worldPlane(moved);

        assertVectorEquals(new Vector3d(15.0, 62.0, 4.0), sampled.origin());
        assertVectorEquals(original.normal(), sampled.normal());
        assertVectorEquals(original.right(), sampled.right());
    }

    @Test
    void rotationAboutYTurnsTheOpeningWithTheCarrier() {
        final PortalAnchor anchor = PortalAnchor.at(CARRIER, worldPlane(), Transform3d.IDENTITY);
        final Transform3d turned = Transform3d.of(
                new Vector3d(1.0, 2.0, 3.0), new Quaterniond().rotateY(Math.PI / 2.0));

        final PortalPlane sampled = anchor.worldPlane(turned);

        // rotateY(90) maps +z onto +x: the local offset (10, 64, 3) becomes
        // (3, 64, -10) and the normal follows the rotation too.
        assertVectorEquals(new Vector3d(4.0, 66.0, -7.0), sampled.origin());
        assertVectorEquals(new Vector3d(1.0, 0.0, 0.0), sampled.normal());
        assertVectorEquals(new Vector3d(0.0, 0.0, -1.0), sampled.right());
    }

    @Test
    void capturedAgainstAMovingCarrierFollowsLaterPoses() {
        final Transform3d before = Transform3d.of(new Vector3d(100.0, 70.0, 0.0));
        final PortalPlane captured = new PortalPlane(
                new Vector3d(102.0, 70.0, 0.0),
                new Vector3d(0.0, 0.0, 1.0),
                new Vector3d(1.0, 0.0, 0.0),
                2.0, 3.0);
        final PortalAnchor anchor = PortalAnchor.at(CARRIER, captured, before);

        // The carrier drove two blocks east; the opening must be carried along.
        final Transform3d after = Transform3d.of(new Vector3d(102.0, 70.0, 0.0));
        final PortalPlane sampled = anchor.worldPlane(after);

        assertVectorEquals(new Vector3d(104.0, 70.0, 0.0), sampled.origin());
        assertVectorEquals(captured.normal(), sampled.normal());
        assertEquals(captured.width(), sampled.width(), EPS);
        assertEquals(captured.height(), sampled.height(), EPS);
    }

    @Test
    void arbitraryPoseKeepsTheBasisOrthonormal() {
        final PortalAnchor anchor = PortalAnchor.at(CARRIER, worldPlane(), Transform3d.IDENTITY);
        final Transform3d pose = Transform3d.of(
                new Vector3d(-4.0, 12.0, 9.0),
                new Quaterniond().rotateXYZ(0.3, -0.7, 1.1));

        final PortalPlane sampled = anchor.worldPlane(pose);

        assertEquals(1.0, sampled.normal().length(), EPS);
        assertEquals(1.0, sampled.right().length(), EPS);
        assertEquals(0.0, sampled.normal().dot(sampled.right()), EPS);
        final Vector3d up = new Vector3d(sampled.normal()).cross(sampled.right()).normalize();
        assertEquals(1.0, up.length(), EPS);
    }

    @Test
    void anchorOnlyMatchesItsOwnCarrier() {
        final PortalAnchor anchor = PortalAnchor.at(CARRIER, worldPlane(), Transform3d.IDENTITY);

        assertTrue(anchor.matches(CARRIER));
        assertFalse(anchor.matches(CARRIER + 1L));
    }

    @Test
    void rejectsNonPositiveCarrierIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new PortalAnchor(0L, worldPlane()));
        assertThrows(IllegalArgumentException.class,
                () -> new PortalAnchor(-3L, worldPlane()));
    }

    @Test
    void portalSnapshotCarriesTheAnchorForBothSides() {
        final UUID entryId = UUID.randomUUID();
        final UUID exitId = UUID.randomUUID();
        final PortalAnchor entryAnchor =
                PortalAnchor.at(CARRIER, worldPlane(), Transform3d.IDENTITY);
        final PortalAnchor exitAnchor =
                PortalAnchor.at(CARRIER + 1L, worldPlane(), Transform3d.IDENTITY);

        final Portal entry = new Portal(entryId, "entry_gate", DIMENSION,
                PortalShape.rectangle(worldPlane()), exitId, entryAnchor);
        final Portal exit = new Portal(exitId, "exit_gate", DIMENSION,
                PortalShape.rectangle(worldPlane()), entryId, exitAnchor);

        final PortalSnapshot snapshot = PortalSnapshot.of(entry, exit);

        assertTrue(snapshot.moving());
        assertNotNull(snapshot.anchor());
        assertNotNull(snapshot.destinationAnchor());
        assertEquals(CARRIER, snapshot.anchor().carrierBodyId());
        assertEquals(CARRIER + 1L, snapshot.destinationAnchor().carrierBodyId());

        // withPlanes swaps the resolved world planes but keeps the bindings so a
        // later frame can re-sample them.
        final PortalSnapshot resolved = snapshot.withPlanes(
                otherPlane(), PortalShape.rectangle(otherPlane()), null, null);
        assertNotSame(snapshot.plane(), resolved.plane());
        assertVectorEquals(otherPlane().origin(), resolved.plane().origin());
        assertEquals(entryAnchor, resolved.anchor());
        assertEquals(exitAnchor, resolved.destinationAnchor());
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }
}
