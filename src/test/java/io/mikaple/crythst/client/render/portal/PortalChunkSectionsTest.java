package io.mikaple.crythst.client.render.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure budget check for the dedicated portal chunk-section storage. The normal
 * frame has at most two roots plus one child each, and every layer is capped at
 * {@link PortalSectionVisibility#MAX_VISIBLE_SECTIONS}.
 */
class PortalChunkSectionsTest {
    private static final int MAX_LAYERS_PER_FRAME = 4;

    @Test
    void normalPortalBudgetFitsWithoutAStorageResize() {
        assertTrue(PortalChunkSections.CHUNK_SECTION_CAPACITY
                >= MAX_LAYERS_PER_FRAME * PortalSectionVisibility.MAX_VISIBLE_SECTIONS);
    }
}
