package io.mikaple.crythst.network.structure;

/**
 * Hard limits and soft targets for structure synchronisation payloads.
 *
 * <p>The vanilla custom-payload limit is about 1 MiB. Snapshots are therefore
 * split at structure granularity into batches that target
 * {@link #TARGET_BATCH_BYTES}. A single structure larger than
 * {@link #MAX_STRUCTURE_BYTES} is skipped by the server and reported in the
 * log; it is never sent as one oversized packet.</p>
 */
public final class StructureSyncLimits {
    /** Wire format version. Bump when any payload layout changes. */
    public static final int PROTOCOL_VERSION = 1;

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
