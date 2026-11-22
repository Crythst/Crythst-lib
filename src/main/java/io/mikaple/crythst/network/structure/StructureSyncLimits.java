package io.mikaple.crythst.network.structure;

/**
 * Hard limits and soft targets for structure synchronisation payloads.
 *
 * <p>The vanilla custom-payload limit is about 1 MiB. Snapshots are therefore
 * split at structure granularity into batches that target
 * {@link #TARGET_BATCH_BYTES}. A single structure larger than
 * {@link #MAX_STRUCTURE_BYTES} is skipped by the server and reported in the
 * log; it is never sent as one oversized packet. Block-entity updates use their
 * own fragment/byte caps in this class and are additionally bounded per
 * structure and per individual NBT tag.</p>
 */
public final class StructureSyncLimits {
    /** Wire format version. Bump when any payload layout changes. */
    public static final int PROTOCOL_VERSION = 3;

    /** Maximum block-entity entries retained per structure. */
    public static final int MAX_BLOCK_ENTITY_ENTRIES = 4_096;

    /** Maximum block-entity entries in one update fragment. */
    public static final int MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE = 2_048;

    /** Maximum removed block-entity positions in one update fragment. */
    public static final int MAX_BLOCK_ENTITY_REMOVALS_PER_UPDATE = 4_096;

    /** Maximum block-entity fragments for one body revision. */
    public static final int MAX_BLOCK_ENTITY_PARTS = 1_024;

    /** Block-entity updates in one payload. */
    public static final int MAX_BLOCK_ENTITY_UPDATES_PER_PAYLOAD = 1_024;

    /** One NBT tag above this encoded size is not synchronised. */
    public static final int MAX_BLOCK_ENTITY_ENTRY_BYTES = 64 * 1024;

    /** Total encoded block-entity NBT budget for one structure. */
    public static final int MAX_BLOCK_ENTITY_BYTES_PER_STRUCTURE = 256 * 1024;

    /** A single block-entity update fragment above this is not sent. */
    public static final int MAX_BLOCK_ENTITY_UPDATE_BYTES = 256 * 1024;

    /** Soft target for one block-entity payload. */
    public static final int TARGET_BLOCK_ENTITY_BATCH_BYTES = 512 * 1024;

    /** Maximum number of voxels in one structure snapshot. */
    public static final int MAX_CELLS = 1_048_576;

    /** Maximum number of distinct block states in one palette. */
    public static final int MAX_PALETTE = 4_096;

    /** Maximum structures in one snapshot/remove packet. */
    public static final int MAX_STRUCTURES_PER_BATCH = 4_096;

    /** Maximum pose updates in one pose packet. */
    public static final int MAX_POSE_UPDATES = 4_096;

    /** Upper bound for the number of batches in one full sync. */
    public static final int MAX_BATCHES = 65_536;

    /** Soft target for one snapshot packet, leaving room for the packet header. */
    public static final int TARGET_BATCH_BYTES = 512 * 1024;

    /** A single structure above this size is skipped instead of sent. */
    public static final int MAX_STRUCTURE_BYTES = 768 * 1024;

    /** Vanilla custom payload ceiling (for documentation and sanity checks). */
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    private StructureSyncLimits() {
    }
}
