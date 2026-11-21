package io.mikaple.crythst.integration.fabric.client;

import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Crythst entries in Minecraft's F3 + F6 debug-options screen.
 *
 * <p>All entries are {@code RENDERER} category options. They have no profile
 * assignment, so {@code DebugScreenEntryList} reports them as {@code NEVER}
 * until the player enables them; nothing is drawn by default. {@link #PHYSICS}
 * is a master switch that enables every layer at once.</p>
 */
public final class CrythstDebugEntries {
    /** Registry id of the all-in-one physics overlay. */
    public static final Identifier PHYSICS =
            Identifier.fromNamespaceAndPath("crythst", "physics");
    /** Per-voxel merged collision boxes and fractional collision parts. */
    public static final Identifier PHYSICS_COLLIDERS =
            Identifier.fromNamespaceAndPath("crythst", "physics_colliders");
    /** Structure OBB/AABB outer contour and body label. */
    public static final Identifier PHYSICS_BOUNDS =
            Identifier.fromNamespaceAndPath("crythst", "physics_bounds");
    /** Centre of mass, structure origin and pose axes. */
    public static final Identifier PHYSICS_DYNAMICS =
            Identifier.fromNamespaceAndPath("crythst", "physics_dynamics");
    /** Physics origin, gravity/up axes and contact points. */
    public static final Identifier PHYSICS_CONTACTS =
            Identifier.fromNamespaceAndPath("crythst", "physics_contacts");
    /** Loose dynamic/kinematic bodies. */
    public static final Identifier PHYSICS_BODIES =
            Identifier.fromNamespaceAndPath("crythst", "physics_bodies");

    /** All ids, in registration order. */
    public static final List<Identifier> ALL = List.of(
            PHYSICS, PHYSICS_COLLIDERS, PHYSICS_BOUNDS, PHYSICS_DYNAMICS,
            PHYSICS_CONTACTS, PHYSICS_BODIES);

    /** Shared entry implementation; the debug options screen labels by id path. */
    public static final DebugScreenEntry ENTRY = new PhysicsEntry();

    private CrythstDebugEntries() {
    }

    private static final class PhysicsEntry implements DebugScreenEntry {
        @Override
        public void display(DebugScreenDisplayer displayer, @Nullable Level level,
                            @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
            // Renderer-category entries have no text section.
        }

        @Override
        public DebugEntryCategory category() {
            return DebugEntryCategory.RENDERER;
        }
    }
}
