package io.mikaple.crythst.client.render.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarRenderPlanTest {

    @Test
    void cutoutModelKeepsBothPassesInSolidPhase() {
        StarRenderPlan.Pass real = StarRenderPlan.real(false);
        StarRenderPlan.Pass star = StarRenderPlan.star(false);

        assertEquals(StarRenderPlan.Phase.SOLID, real.phase());
        assertEquals(StarRenderPlan.Phase.SOLID, star.phase());
        assertFalse(real.blended());
        assertFalse(star.blended());
        assertTrue(real.writesDepth(), "the real cutout pass owns depth");
        assertFalse(star.writesDepth(), "the star overlay must not occlude later geometry");
        assertEquals(StarRenderPlan.STAR_DEPTH_TEST, star.depthTest());
    }

    @Test
    void translucentModelKeepsBothPassesInTranslucentPhase() {
        StarRenderPlan.Pass real = StarRenderPlan.real(true);
        StarRenderPlan.Pass star = StarRenderPlan.star(true);

        assertEquals(StarRenderPlan.Phase.TRANSLUCENT, real.phase());
        assertEquals(StarRenderPlan.Phase.TRANSLUCENT, star.phase());
        assertTrue(real.blended());
        assertTrue(star.blended(), "the overlay must use the matching translucent pipeline");
        assertFalse(star.writesDepth());
    }

    @Test
    void passOrderAlwaysPutsTheRealModelFirst() {
        for (boolean translucent : new boolean[]{false, true}) {
            List<StarRenderPlan.Pass> passes = StarRenderPlan.passes(translucent);
            assertEquals(2, passes.size());
            assertEquals(StarRenderPlan.real(translucent), passes.get(0));
            assertEquals(StarRenderPlan.star(translucent), passes.get(1));
            assertTrue(StarRenderPlan.starFollowsReal(translucent));
        }
    }

    @Test
    void starPassNeverWritesDepth() {
        assertFalse(StarRenderPlan.STAR_WRITES_DEPTH);
        assertFalse(StarRenderPlan.star(false).writesDepth());
        assertFalse(StarRenderPlan.star(true).writesDepth());
        assertEquals(StarRenderPlan.DepthTest.GREATER_THAN_OR_EQUAL,
                StarRenderPlan.star(false).depthTest(),
                "reversed-Z: the overlay passes only when it is not behind the real model");
    }
}
