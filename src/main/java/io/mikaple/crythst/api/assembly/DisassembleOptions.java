package io.mikaple.crythst.api.assembly;

/**
 * Options for {@link StructureAssembly#disassemble}.
 *
 * @param removeStructure        remove and close the physics body after placing
 *                               the blocks
 * @param restoreBlockEntities   reattach captured block-entity NBT
 * @param dropBlocksThatDoNotFit drop an item when the destination is outside the
 *                               build height or occupied by a non-replaceable
 *                               block
 * @param suppressNeighborUpdates restore blocks without neighbour updates, then
 *                               let the world settle once all blocks are placed
 */
public record DisassembleOptions(
        boolean removeStructure,
        boolean restoreBlockEntities,
        boolean dropBlocksThatDoNotFit,
        boolean suppressNeighborUpdates
) {
    public static final DisassembleOptions DEFAULTS =
            new DisassembleOptions(true, true, false, true);

    public static DisassembleOptions defaults() {
        return DEFAULTS;
    }

    public DisassembleOptions withRemoveStructure(boolean remove) {
        return new DisassembleOptions(remove, restoreBlockEntities,
                dropBlocksThatDoNotFit, suppressNeighborUpdates);
    }

    public DisassembleOptions withBlockEntities(boolean restore) {
        return new DisassembleOptions(removeStructure, restore,
                dropBlocksThatDoNotFit, suppressNeighborUpdates);
    }

    public DisassembleOptions withDrops(boolean drop) {
        return new DisassembleOptions(removeStructure, restoreBlockEntities,
                drop, suppressNeighborUpdates);
    }

    public DisassembleOptions withSuppressNeighborUpdates(boolean suppress) {
        return new DisassembleOptions(removeStructure, restoreBlockEntities,
                dropBlocksThatDoNotFit, suppress);
    }
}
