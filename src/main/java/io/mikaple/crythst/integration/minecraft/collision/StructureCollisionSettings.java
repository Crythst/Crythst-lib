package io.mikaple.crythst.integration.minecraft.collision;

import net.minecraft.world.entity.Entity;

import java.util.function.Predicate;

/**
 * Runtime switches shared by the movement collision path and the legacy
 * diagnostic entry points.
 *
 * <p>No class in this package touches a physics body, so installing a filter
 * can never change the one-way rule: entities are pushed out of structures but
 * structures are never pushed by entities.</p>
 */
public final class StructureCollisionSettings {
    private static volatile Predicate<Entity> entityFilter = entity -> true;

    private StructureCollisionSettings() {
    }

    /**
     * Installs an allow/deny predicate. Returning {@code false} skips the
     * entity in the movement collision path and in the diagnostic inspector.
     *
     * @param filter predicate, or {@code null} to allow all entities
     */
    public static void setEntityFilter(Predicate<Entity> filter) {
        entityFilter = filter == null ? entity -> true : filter;
    }

    /** Whether the installed filter allows this entity. */
    public static boolean allows(Entity entity) {
        final Predicate<Entity> filter = entityFilter;
        return filter == null || filter.test(entity);
    }
}
