package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Side table of Minecraft block states for physics-structure voxels.
 *
 * <p>{@code MutableVoxelVolume} only stores solidity, mass and material. The
 * interaction layer therefore keeps the visual/gameplay {@link BlockState} in a
 * small side map instead of changing the core voxel volume. The map is keyed by
 * structure identity and voxel coordinates and is weakly keyed by the structure,
 * so removing a structure does not retain it forever.</p>
 *
 * <p>This is a deliberate first-version compromise. A volume built by
 * {@code VoxelVolumes.filled} has no block states; breaking such a voxel simply
 * produces no state-dependent sound or drop. Volumes backed by a real
 * {@code BlockVoxelVolume} are read-only anyway and reject edits.</p>
 */
public final class StructureBlockStates {
    private record VoxelKey(int x, int y, int z) {
    }

    private static final Map<PhysicsStructure, Map<VoxelKey, BlockState>> STATES = new WeakHashMap<>();

    private StructureBlockStates() {
    }

    /**
     * @return the stored state, or {@code null} when the voxel has no state
     */
    public static synchronized BlockState get(PhysicsStructure structure, int x, int y, int z) {
        final Map<VoxelKey, BlockState> states = STATES.get(structure);
        return states == null ? null : states.get(new VoxelKey(x, y, z));
    }

    /**
     * Stores a state. Passing {@code null} removes the entry.
     *
     * @return the previous state, or {@code null}
     */
    public static synchronized BlockState set(PhysicsStructure structure, int x, int y, int z,
                                               BlockState state) {
        if (state == null) {
            return remove(structure, x, y, z);
        }
        return STATES.computeIfAbsent(structure, ignored -> new HashMap<>())
                .put(new VoxelKey(x, y, z), state);
    }

    public static synchronized BlockState remove(PhysicsStructure structure, int x, int y, int z) {
        final Map<VoxelKey, BlockState> states = STATES.get(structure);
        if (states == null) {
            return null;
        }
        final BlockState removed = states.remove(new VoxelKey(x, y, z));
        if (states.isEmpty()) {
            STATES.remove(structure);
        }
        return removed;
    }

    public static synchronized void clear(PhysicsStructure structure) {
        STATES.remove(structure);
    }

    /**
     * Read-only copy of the state map for one structure, mostly for diagnostics.
     */
    public static synchronized Map<String, BlockState> describe(PhysicsStructure structure) {
        final Map<VoxelKey, BlockState> states = STATES.get(structure);
        if (states == null || states.isEmpty()) {
            return Map.of();
        }
        final Map<String, BlockState> result = new HashMap<>();
        for (Map.Entry<VoxelKey, BlockState> entry : states.entrySet()) {
            final VoxelKey key = entry.getKey();
            result.put(key.x() + "," + key.y() + "," + key.z(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    public static synchronized int size(PhysicsStructure structure) {
        final Map<VoxelKey, BlockState> states = STATES.get(structure);
        return states == null ? 0 : states.size();
    }
}
