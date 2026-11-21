package io.mikaple.crythst.client.render.structure.selection;

import io.mikaple.crythst.math.Transform3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StructureSelectionMathTest {
    private static final int CELL_X = 2;
    private static final int CELL_Y = -3;
    private static final int CELL_Z = 4;

    private static final Transform3d POSE = new Transform3d(
            new Vector3d(-13.5, 7.25, -4.0),
            new Quaterniond().rotateY(0.7).rotateX(0.3).rotateZ(-0.2));
    private static final double SCALE = 1.5;

    private static final StructureSelectionMath.SolidCell ONLY_HIT_CELL =
            (x, y, z) -> x == CELL_X && y == CELL_Y && z == CELL_Z;

    @Test
    void positiveAndNegativeFacesSelectTheHitCell() {
        assertFace(1, 0, 0, -1, 0, 0, 1.0, 0.5, 0.5);
        assertFace(-1, 0, 0, 1, 0, 0, 0.0, 0.5, 0.5);
        assertFace(0, 1, 0, 0, -1, 0, 0.5, 1.0, 0.5);
        assertFace(0, -1, 0, 0, 1, 0, 0.5, 0.0, 0.5);
        assertFace(0, 0, 1, 0, 0, -1, 0.5, 0.5, 1.0);
        assertFace(0, 0, -1, 0, 0, 1, 0.5, 0.5, 0.0);
    }

    @Test
    void nonSolidSteppedCellFallsBackToNearestSolidNeighbour() {
        final Vector3d localHit = new Vector3d(
                (CELL_X + 1) * SCALE,
                (CELL_Y + 0.5) * SCALE,
                (CELL_Z + 0.5) * SCALE);
        final Vector3d worldHit = POSE.transformPoint(localHit);
        final Vector3d worldRay = POSE.transformDirection(new Vector3d(-1.0, 0.0, 0.0));

        final StructureSelectionMath.Cell cell = StructureSelectionMath.findCell(
                POSE, worldHit, worldRay, SCALE,
                (x, y, z) -> x == CELL_X + 1 && y == CELL_Y && z == CELL_Z);

        assertEquals(CELL_X + 1, cell.x());
        assertEquals(CELL_Y, cell.y());
        assertEquals(CELL_Z, cell.z());
    }

    @Test
    void missingSolidCellReturnsNull() {
        final Vector3d localHit = new Vector3d(
                (CELL_X + 1) * SCALE,
                (CELL_Y + 0.5) * SCALE,
                (CELL_Z + 0.5) * SCALE);
        final Vector3d worldHit = POSE.transformPoint(localHit);
        final Vector3d worldRay = POSE.transformDirection(new Vector3d(-1.0, 0.0, 0.0));

        assertNull(StructureSelectionMath.findCell(
                POSE, worldHit, worldRay, SCALE, (x, y, z) -> false));
    }

    private static void assertFace(int hitNormalX, int hitNormalY, int hitNormalZ,
                                   int rayX, int rayY, int rayZ,
                                   double fx, double fy, double fz) {
        final Vector3d localHit = new Vector3d(
                (CELL_X + fx) * SCALE,
                (CELL_Y + fy) * SCALE,
                (CELL_Z + fz) * SCALE);
        final Vector3d worldHit = POSE.transformPoint(localHit);
        final Vector3d worldRay = POSE.transformDirection(new Vector3d(rayX, rayY, rayZ));

        final StructureSelectionMath.Cell cell = StructureSelectionMath.findCell(
                POSE, worldHit, worldRay, SCALE, ONLY_HIT_CELL);

        assertEquals(CELL_X, cell.x(), "x for normal " + hitNormalX + "," + hitNormalY + "," + hitNormalZ);
        assertEquals(CELL_Y, cell.y(), "y for normal " + hitNormalX + "," + hitNormalY + "," + hitNormalZ);
        assertEquals(CELL_Z, cell.z(), "z for normal " + hitNormalX + "," + hitNormalY + "," + hitNormalZ);
    }
}
