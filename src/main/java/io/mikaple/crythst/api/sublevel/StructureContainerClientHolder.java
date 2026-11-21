package io.mikaple.crythst.api.sublevel;

import io.mikaple.crythst.api.client.ClientStructureContainer;
import net.minecraft.world.level.Level;

/**
 * Lazy bridge to the client-only container implementation.
 *
 * <p>This class is only referenced from {@link StructureContainer#getContainer}
 * after an explicit client-level check. A dedicated server therefore never
 * loads {@link ClientStructureContainer} or its Minecraft client dependencies.</p>
 */
final class StructureContainerClientHolder {
    private StructureContainerClientHolder() {
    }

    static StructureContainer get(Level level) {
        return ClientStructureContainer.get(level);
    }
}
