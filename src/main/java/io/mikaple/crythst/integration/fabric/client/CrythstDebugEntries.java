package io.mikaple.crythst.integration.fabric.client;

import io.mikaple.crythst.client.render.portal.PortalDebugText;
import io.mikaple.crythst.integration.minecraft.selection.StructureBlockHitResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Crythst entries in Minecraft's F3 + F6 debug-options screen.
 *
 * <p>All renderer entries are {@code RENDERER} category options and have no
 * profile assignment, so {@code DebugScreenEntryList} reports them as
 * {@code NEVER} until the player enables them; nothing is drawn by default.
 * {@link #PHYSICS} is a master switch that enables every physics layer at
 * once.</p>
 *
 * <p>{@link #STRUCTURE_TARGET} is a {@code SCREEN_TEXT} entry that defaults to
 * {@code IN_OVERLAY}, so pressing F3 while aiming at a structure voxel shows
 * the body id, structure-local voxel coordinates and block state.</p>
 */
public final class CrythstDebugEntries {
    /** Registry id of the all-in-one physics renderer overlay. */
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
    /** Cached Minecraft terrain-collision sections. */
    public static final Identifier PHYSICS_TERRAIN =
            Identifier.fromNamespaceAndPath("crythst", "physics_terrain");
    /** Per-structure force/torque totals and force-group arrows. */
    public static final Identifier PHYSICS_FORCES =
            Identifier.fromNamespaceAndPath("crythst", "physics_forces");
    /** Portal openings, plane normals, destination outlines and traversal boxes. */
    public static final Identifier PHYSICS_PORTAL =
            Identifier.fromNamespaceAndPath("crythst", "portal");
    /** F3 text entry for the portal renderer's per-frame cost. */
    public static final Identifier PORTAL_STATS =
            Identifier.fromNamespaceAndPath("crythst", "portal_stats");
    /** F3 text entry for the structure voxel under the crosshair. */
    public static final Identifier STRUCTURE_TARGET =
            Identifier.fromNamespaceAndPath("crythst", "structure_target");

    /** All renderer ids, in registration order. */
    public static final List<Identifier> ALL_RENDERERS = List.of(
            PHYSICS, PHYSICS_COLLIDERS, PHYSICS_BOUNDS, PHYSICS_DYNAMICS,
            PHYSICS_CONTACTS, PHYSICS_BODIES, PHYSICS_TERRAIN, PHYSICS_FORCES,
            PHYSICS_PORTAL);

    /** Shared entry implementation; the debug options screen labels by id path. */
    public static final DebugScreenEntry ENTRY = new PhysicsEntry();

    /** Text entry for the targeted structure voxel. */
    public static final DebugScreenEntry STRUCTURE_TARGET_ENTRY = new StructureTargetEntry();

    /** Text entry for the portal renderer cost. */
    public static final DebugScreenEntry PORTAL_STATS_ENTRY = new PortalStatsEntry();

    private CrythstDebugEntries() {
    }

    /**
     * Whether any Crythst renderer entry is currently enabled. Used to decide
     * whether the shared debug renderer needs to be added to vanilla's list;
     * individual layers work without the {@link #PHYSICS} master entry.
     */
    public static boolean anyRendererEnabled(DebugScreenEntryList entries) {
        if (entries == null) {
            return false;
        }
        for (Identifier id : ALL_RENDERERS) {
            if (entries.isCurrentlyEnabled(id)) {
                return true;
            }
        }
        return false;
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

    private static final class PortalStatsEntry implements DebugScreenEntry {
        @Override
        public void display(DebugScreenDisplayer displayer, @Nullable Level level,
                            @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
            for (String line : PortalDebugText.lines()) {
                displayer.addLine(line);
            }
        }

        @Override
        public DebugEntryCategory category() {
            return DebugEntryCategory.SCREEN_TEXT;
        }
    }

    private static final class StructureTargetEntry implements DebugScreenEntry {
        @Override
        public void display(DebugScreenDisplayer displayer, @Nullable Level level,
                            @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
            final Minecraft minecraft = Minecraft.getInstance();
            final HitResult hit = minecraft == null ? null : minecraft.hitResult;
            if (!(hit instanceof StructureBlockHitResult structureHit)) {
                return;
            }
            displayer.addLine("Crythst structure body #" + structureHit.bodyId());
            displayer.addLine("Structure voxel: " + structureHit.voxelX()
                    + ", " + structureHit.voxelY() + ", " + structureHit.voxelZ());
            displayer.addLine("Structure block: " + structureHit.blockState());
            displayer.addLine(String.format(Locale.ROOT,
                    "Structure pose: (%.2f, %.2f, %.2f) scale %.2f",
                    structureHit.structurePose().position().x,
                    structureHit.structurePose().position().y,
                    structureHit.structurePose().position().z,
                    structureHit.blockScale()));
            displayer.addLine(String.format(Locale.ROOT,
                    "World hit: (%.2f, %.2f, %.2f) face %s",
                    structureHit.getLocation().x,
                    structureHit.getLocation().y,
                    structureHit.getLocation().z,
                    structureHit.worldFace()));
        }

        @Override
        public DebugEntryCategory category() {
            return DebugEntryCategory.SCREEN_TEXT;
        }
    }
}
