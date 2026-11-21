package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionOcclusionTest {
    private static final int SIZE_X = 3;
    private static final int SIZE_Y = 1;
    private static final int SIZE_Z = 1;

    @Test
    void openSectionFacesPropagateEverywhere() {
        final boolean[] visible = SectionOcclusion.computeVisible(
                SIZE_X, SIZE_Y, SIZE_Z, 0, 0, 0, true,
                (section, direction) -> false);

        for (boolean value : visible) {
            assertTrue(value);
        }
    }

    @Test
    void opaqueWallStopsPropagation() {
        // The section at x=0 has a fully opaque EAST face; x=1 and x=2 are
        // behind that wall and must stay hidden.
        final Set<Integer> opaqueFaces = new HashSet<>();
        opaqueFaces.add(faceKey(0, Direction.EAST));
        final boolean[] visible = SectionOcclusion.computeVisible(
                SIZE_X, SIZE_Y, SIZE_Z, 0, 0, 0, true,
                (section, direction) -> opaqueFaces.contains(faceKey(section, direction)));

        assertTrue(visible[index(0)]);
        assertFalse(visible[index(1)]);
        assertFalse(visible[index(2)]);
    }

    @Test
    void aSingleOpenFaceStillPropagates() {
        // Same wall setup, but the EAST face is not opaque. Visibility must
        // continue into x=1 (and then x=2 because that face is open too).
        final boolean[] visible = SectionOcclusion.computeVisible(
                SIZE_X, SIZE_Y, SIZE_Z, 0, 0, 0, true,
                (section, direction) -> false);

        assertTrue(visible[index(0)]);
        assertTrue(visible[index(1)]);
        assertTrue(visible[index(2)]);
    }

    @Test
    void cameraOutsideReturnsEverySectionVisible() {
        final boolean[] visible = SectionOcclusion.computeVisible(
                SIZE_X, SIZE_Y, SIZE_Z, 0, 0, 0, false,
                (section, direction) -> true);

        for (boolean value : visible) {
            assertTrue(value, "an external camera must never use the BFS to hide sections");
        }
    }

    @Test
    void outOfBoundsStartFallsBackToAllVisible() {
        final boolean[] visible = SectionOcclusion.computeVisible(
                SIZE_X, SIZE_Y, SIZE_Z, 99, 0, 0, true,
                (section, direction) -> true);

        for (boolean value : visible) {
            assertTrue(value);
        }
    }

    private static int index(int x) {
        return SectionOcclusion.index(SIZE_X, SIZE_Z, x, 0, 0);
    }

    private static int faceKey(int section, Direction direction) {
        return section * 6 + direction.ordinal();
    }
}
