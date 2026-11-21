package io.mikaple.crythst.api.sublevel.view;

import io.mikaple.crythst.api.client.ClientStructureContainer;
import io.mikaple.crythst.api.sublevel.StructureContainer;
import io.mikaple.crythst.client.network.ClientStructureSnapshot;
import io.mikaple.crythst.integration.minecraft.structure.view.ClientStructureLevelView;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.jetbrains.annotations.Nullable;

/**
 * Lazy bridge to client-only structure view construction.
 *
 * <p>Only reached through {@link StructureLevelViews#of(StructureContainer, long)}
 * after a client-level check, so a dedicated server never loads
 * {@link ClientStructureContainer} or its client classes.</p>
 */
final class StructureLevelViewClientHolder {
    private StructureLevelViewClientHolder() {
    }

    static @Nullable StructureLevelView view(StructureContainer container,
                                             @Nullable PhysicsStructure structure,
                                             long bodyId) {
        if (!(container instanceof ClientStructureContainer client)) {
            return null;
        }
        if (structure != null && structure.body().id() == bodyId) {
            return ClientStructureLevelView.ofLive(structure, client.level(),
                    structure.renderStateAt(System.nanoTime()).pose());
        }
        final ClientStructureSnapshot snapshot = client.snapshot(bodyId);
        return snapshot == null ? null
                : ClientStructureLevelView.ofSnapshot(snapshot, client.level());
    }
}
