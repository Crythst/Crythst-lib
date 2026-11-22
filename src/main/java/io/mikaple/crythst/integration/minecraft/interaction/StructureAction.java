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
    SHOVEL_WATER,
    /**
     * Pick the structure block under the crosshair. The server resolves the
     * stored state and runs vanilla pick-block handling.
     */
    PICK,
    /**
     * Pick the structure block and include block data where the player has
     * infinite materials (vanilla Ctrl+middle-click).
     */
    PICK_WITH_DATA,
    /**
     * Use/activate the structure block under the crosshair. The server
     * re-raycasts and runs vanilla block use against the live auxiliary block.
     */
    USE,
    /**
     * Begin a server-authoritative held-mining session. The server raycasts,
     * maps the voxel into the auxiliary void level and lets vanilla
     * {@code ServerPlayerGameMode} own progress until TICK heartbeats stop or
     * the block completes.
     */
    START_MINE,
    /** Keep the active mining session alive and confirm the same target. */
    TICK_MINE,
    /** Release the attack key and abort the active mining session. */
    STOP_MINE;

    private static final StructureAction[] VALUES = values();

    static StructureAction fromId(int id) {
        if (id < 0 || id >= VALUES.length) {
            return MINE;
        }
        return VALUES[id];
    }
}
