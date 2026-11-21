package io.mikaple.crythst.api.sublevel.view;

import io.mikaple.crythst.api.sublevel.ServerStructureContainer;
import io.mikaple.crythst.api.sublevel.StructureContainer;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.integration.minecraft.structure.view.ClientStructureLevelView;
import io.mikaple.crythst.integration.minecraft.structure.view.ServerStructureLevelView;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Factory and lifecycle registry for {@link StructureLevelView}s.
 *
 * <p>Views are inexpensive wrappers, not cached state. Acquire one when a query
 * or edit begins; do not store it across an edit, reindex, split or removal.
 * This class tracks a monotonic epoch per live structure so a caller can detect
 * that its view was superseded:</p>
 *
 * <pre>{@code
 * StructureLevelView view = container.view(bodyId);
 * ...
 * if (!view.isCurrent()) {
 *     view = container.view(bodyId); // reacquire
 * }
 * }</pre>
 *
 * <p>Server views write through the authoritative volume and advance the
 * structure epoch. The writing view updates its own epoch so it stays current;
 * any older view of the same structure becomes stale.</p>
 *
 * <p>On a client, use {@link StructureContainer#view(long)}: it routes live
 * singleplayer structures through {@code ClientPhysicsView} snapshots and
 * multiplayer bodies through the network cache. {@link #of(PhysicsStructure)}
 * is the server-authoritative lookup and must be called on the server
 * thread.</p>
 */
public final class StructureLevelViews {
    private static final Map<PhysicsStructure, Long> EPOCHS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PhysicsStructure, LevelPhysics> OWNERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StructureLevelViews() {
    }

    /**
     * Current epoch of a live structure.
     *
     * <p>Epochs are process-local and only compare equality; they are not
     * persisted or sent over the network.</p>
     */
    public static long epoch(PhysicsStructure structure) {
        if (structure == null) {
            return 0L;
        }
        final Long epoch = EPOCHS.get(structure);
        return epoch == null ? 0L : epoch;
    }

    /**
     * Advances and returns the epoch of a live structure. Called by server
     * views after a successful write.
     */
    public static long advanceEpoch(PhysicsStructure structure) {
        if (structure == null) {
            return 0L;
        }
        synchronized (EPOCHS) {
            final long next = epoch(structure) + 1L;
            EPOCHS.put(structure, next);
            return next;
        }
    }

    /** Drops the cached owner/epoch entry for a structure that is being removed. */
    public static void forget(PhysicsStructure structure) {
        if (structure == null) {
            return;
        }
        OWNERS.remove(structure);
        EPOCHS.remove(structure);
    }

    /**
     * Returns a server view for a live structure, locating the physics world
     * that owns it.
     *
     * @return the view, or {@code null} when the structure is closed or not
     *         attached to any physics world
     */
    public static @Nullable StructureLevelView of(PhysicsStructure structure) {
        if (structure == null || structure.closed()) {
            return null;
        }
        LevelPhysics owner = OWNERS.get(structure);
        if (owner == null || owner.closed() || !containsIdentity(owner.structures(), structure)) {
            owner = null;
            for (LevelPhysics candidate : MinecraftPhysics.levelPhysicsSnapshot()) {
                if (!candidate.closed() && containsIdentity(candidate.structures(), structure)) {
                    owner = candidate;
                    break;
                }
            }
            if (owner == null) {
                return null;
            }
            OWNERS.put(structure, owner);
        }
        return new ServerStructureLevelView(structure, owner.level(), owner);
    }

    /**
     * Returns the view for a body in a container.
     *
     * <p>Server containers resolve the authoritative physics world. Client
     * containers prefer the live singleplayer render structure and fall back to
     * the synced network snapshot. Never creates a physics world.</p>
     */
    public static @Nullable StructureLevelView of(@Nullable StructureContainer container, long bodyId) {
        if (container == null) {
            return null;
        }
        if (container instanceof ServerStructureContainer server) {
            return of(server.physics(), server.byId(bodyId));
        }
        final PhysicsStructure structure = container.byId(bodyId);
        if (container.level().isClientSide()) {
            return StructureLevelViewClientHolder.view(container, structure, bodyId);
        }
        return structure == null ? null : of(structure);
    }

    /**
     * Returns a server view for a structure in known physics world, or
     * {@code null} when the world does not own it.
     */
    public static @Nullable StructureLevelView of(@Nullable LevelPhysics physics,
                                                  @Nullable PhysicsStructure structure) {
        if (physics == null || physics.closed() || structure == null || structure.closed()
                || !containsIdentity(physics.structures(), structure)) {
            return null;
        }
        OWNERS.put(structure, physics);
        return new ServerStructureLevelView(structure, physics.level(), physics);
    }

    /**
     * Creates an authoritative view for a structure. Passing a {@code null}
     * physics world is supported for standalone volumes and tests: writes still
     * reindex the collider, but there is no auxiliary region to refresh and a
     * massless body cannot be removed.
     */
    public static StructureLevelView server(PhysicsStructure structure,
                                            @Nullable ServerLevel host,
                                            @Nullable LevelPhysics physics) {
        if (physics != null) {
            final StructureLevelView view = of(physics, structure);
            if (view != null) {
                return view;
            }
        }
        return new ServerStructureLevelView(structure, host, physics);
    }

    /**
     * Creates a read-only client view over a live integrated-client structure.
     * The pose should be a render pose sampled on the client thread.
     */
    public static StructureLevelView client(PhysicsStructure structure,
                                            @Nullable Level level,
                                            Transform3d pose) {
        return ClientStructureLevelView.ofLive(structure, level, pose);
    }

    /** Creates a read-only client view over a multiplayer network snapshot. */
    public static StructureLevelView snapshot(ClientStructureSnapshot snapshot,
                                              @Nullable Level level) {
        return ClientStructureLevelView.ofSnapshot(snapshot, level);
    }

    private static boolean containsIdentity(Iterable<PhysicsStructure> structures,
                                            PhysicsStructure target) {
        for (PhysicsStructure structure : structures) {
            if (structure == target) {
                return true;
            }
        }
        return false;
    }
}
