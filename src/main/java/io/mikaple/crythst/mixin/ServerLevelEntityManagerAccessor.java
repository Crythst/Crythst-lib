package io.mikaple.crythst.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the auxiliary level's entity section manager.
 *
 * <p>The void level does not run {@code ServerLevel.tick}, so its entity
 * sections would never be promoted/loaded. Block-entity tickers require
 * {@code LevelChunk.isTicking}, which in turn requires the entity section to be
 * loaded. {@code EmptyPhysicsLevel} primes the manager for its active chunks
 * without running entity AI or entity ticking.</p>
 */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManagerAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> crythst$entityManager();
}
