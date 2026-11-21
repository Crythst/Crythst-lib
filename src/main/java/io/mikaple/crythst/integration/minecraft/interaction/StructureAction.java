package io.mikaple.crythst.integration.minecraft.interaction;

/**
 * Actions a client may request from the server interaction handler.
 */
public enum StructureAction {
    /** Mine the structure voxel under the crosshair. */
    MINE,
    /** Place the main-hand block item on the structure face under the crosshair. */
    PLACE,
    /** Apply shovel recoil when the crosshair is over water. */
    SHOVEL_WATER;

    private static final StructureAction[] VALUES = values();

    static StructureAction fromId(int id) {
        if (id < 0 || id >= VALUES.length) {
            return MINE;
        }
        return VALUES[id];
    }
}
