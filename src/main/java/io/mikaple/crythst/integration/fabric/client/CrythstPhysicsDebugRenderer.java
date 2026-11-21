package io.mikaple.crythst.integration.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.debug.DebugValueAccess;

/**
 * Physics body/collider debug overlay exposed through vanilla's debug renderer
 * list.
 *
 * <p>Individual layers are driven by separate F3 + F6 entries. The
 * {@code crythst:physics} entry acts as a master switch for all layers.</p>
 */
public final class CrythstPhysicsDebugRenderer implements DebugRenderer.SimpleDebugRenderer {
    @Override
    public void emitGizmos(double cameraX, double cameraY, double cameraZ,
                           DebugValueAccess debugValueAccess, Frustum frustum,
                           float partialTicks) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        final boolean all = enabled(CrythstDebugEntries.PHYSICS);

        if (all || enabled(CrythstDebugEntries.PHYSICS_COLLIDERS)) {
            PhysicsGizmoRenderer.emitColliders(partialTicks);
        }
        if (all || enabled(CrythstDebugEntries.PHYSICS_BOUNDS)) {
            PhysicsGizmoRenderer.emitBounds(partialTicks);
        }
        if (all || enabled(CrythstDebugEntries.PHYSICS_DYNAMICS)) {
            PhysicsGizmoRenderer.emitDynamics(partialTicks);
        }
        if (all || enabled(CrythstDebugEntries.PHYSICS_CONTACTS)) {
            PhysicsGizmoRenderer.emitContacts(ClientPhysicsView.debug());
        }
        if (all || enabled(CrythstDebugEntries.PHYSICS_BODIES)) {
            LooseBodyRenderer.emit(partialTicks);
        }
        if (all || enabled(CrythstDebugEntries.PHYSICS_TERRAIN)) {
            PhysicsGizmoRenderer.emitTerrain(ClientPhysicsView.terrain());
        }
    }

    private static boolean enabled(Identifier id) {
        return Minecraft.getInstance().debugEntries.isCurrentlyEnabled(id);
    }
}
