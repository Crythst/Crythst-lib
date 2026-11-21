package io.mikaple.crythst.integration.minecraft.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureActionTest {
    @Test
    void existingActionIdsStayStable() {
        // The payload writes the enum ordinal as one byte. Adding new actions
        // must not shift the three original ids.
        assertEquals(StructureAction.MINE, StructureAction.fromId(0));
        assertEquals(StructureAction.PLACE, StructureAction.fromId(1));
        assertEquals(StructureAction.SHOVEL_WATER, StructureAction.fromId(2));
    }

    @Test
    void pickActionsAppendAfterExistingIds() {
        assertEquals(StructureAction.PICK, StructureAction.fromId(3));
        assertEquals(StructureAction.PICK_WITH_DATA, StructureAction.fromId(4));
    }

    @Test
    void unknownIdsStillFallBackToMine() {
        assertEquals(StructureAction.MINE, StructureAction.fromId(99));
        assertEquals(StructureAction.MINE, StructureAction.fromId(-1));
    }
}
