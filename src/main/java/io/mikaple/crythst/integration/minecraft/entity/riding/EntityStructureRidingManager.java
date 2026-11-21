package io.mikaple.crythst.integration.minecraft.entity.riding;

import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Carries entities that stand on physics structures.
 *
 * <p>The pass runs after the physics world has advanced. Ordinary structure
 * contact is resolved inside vanilla {@code Entity.move}, so this pass only
 * carries an entity with a moving platform; it does not correct penetration.
 * For every eligible entity it detects a structure top surface just below the
 * entity's feet and stores the entity position in structure-local space. On
 * later ticks the entity is moved by the structure's pose delta, so a moving or
 * rotating platform carries the entity with it. The entity's own movement is
 * preserved by re-anchoring the local point every tick.</p>
 *
 * <p>The manager never writes to a structure: it only reads the pose, the
 * immutable collider boxes and the body OBB. Entities therefore cannot push,
 * rotate or wake a structure through this pass.</p>
 *
 * <p>Server thread only. Register it after
 * {@code PhysicsTicker.register()} (the physics tick and the collision pass)
 * with {@link #register()}.</p>
 */
public final class EntityStructureRidingManager {
    /** Default vertical tolerance used by the automatic support probe. */
    public static final double DEFAULT_SUPPORT_TOLERANCE =
            EntityStructureRidingMath.DEFAULT_SUPPORT_TOLERANCE;
    /** Default maximum distance between an entity and its last anchor. */
    public static final double DEFAULT_MAX_ATTACHMENT_DISTANCE = 4.0;
    /** Default radius around an allowlisted entity's structure used to bind it. */
    public static final double DEFAULT_ALLOWLIST_ATTACH_RADIUS = 2.0;
    /** A pose delta larger than this is treated as a teleport, not carrying. */
    public static final double MAX_CARRY_DELTA = 16.0;
    /** Upward drift above the hard-locked anchor that looks like a jump. */
    private static final double JUMP_DETACH_DRIFT = 0.25;
    /** Minimum upward velocity for the hard-lock jump check. */
    private static final double JUMP_DETACH_VELOCITY = 0.05;
    /** Keep pathological entity counts bounded. */
    public static final int MAX_ENTITIES_PER_LEVEL = 2048;
    /** Only the nearest structures are considered for one entity. */
    public static final int MAX_STRUCTURES_PER_ENTITY = 8;
    /** Narrow-phase budget per structure. */
    public static final int MAX_BOXES_PER_STRUCTURE = 256;

    /**
     * Create contraption entities are recognised by class name only. This
     * avoids a compile-time dependency on Create and works for both the
     * Zurrtum/Create port and upstream Create.
     */
    private static final Set<String> DEFAULT_ALLOWLISTED_CLASS_NAMES = Set.of(
            "com.zurrtum.create.content.contraptions.AbstractContraptionEntity",
            "com.simibubi.create.content.contraptions.AbstractContraptionEntity"
    );

    private static final Map<UUID, Attachment> ATTACHMENTS = new HashMap<>();
    private static final Set<EntityType<?>> ALLOWED_ENTITY_TYPES = ConcurrentHashMap.newKeySet();
    private static final Set<String> ALLOWLISTED_CLASS_NAMES = ConcurrentHashMap.newKeySet();

    private static volatile Predicate<Entity> entityFilter = entity -> true;
    private static volatile boolean enabled = true;
    private static volatile boolean rotateYaw = true;
    private static volatile double supportTolerance = DEFAULT_SUPPORT_TOLERANCE;
    private static volatile double maxAttachmentDistance = DEFAULT_MAX_ATTACHMENT_DISTANCE;
    private static volatile double allowlistAttachRadius = DEFAULT_ALLOWLIST_ATTACH_RADIUS;
    private static volatile boolean registered;
    private static volatile TickStats lastStats = new TickStats(0, 0, 0, 0, 0, 0, 0, 0L);

    static {
        ALLOWLISTED_CLASS_NAMES.addAll(DEFAULT_ALLOWLISTED_CLASS_NAMES);
    }

    private EntityStructureRidingManager() {
    }

    /**
     * Subscribes the riding pass to the end of the server tick.
     *
     * <p>Call this after {@code PhysicsTicker.register()} so the pass sees the
     * current pose. Calling it more than once is harmless.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(EntityStructureRidingManager::onEndServerTick);
        ServerLevelEvents.UNLOAD.register((server, level) -> detachLevel(level.dimension()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    /**
     * Enables or disables the pass without unregistering the Fabric callback.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether the structure's yaw delta is applied to attached entities.
     */
    public static void setRotateYaw(boolean value) {
        rotateYaw = value;
    }

    public static boolean isRotateYaw() {
        return rotateYaw;
    }

    public static void setSupportTolerance(double value) {
        supportTolerance = Math.clamp(value, 0.0, 1.0);
    }

    public static double supportTolerance() {
        return supportTolerance;
    }

    public static void setMaxAttachmentDistance(double value) {
        maxAttachmentDistance = Math.max(0.0, value);
    }

    public static double maxAttachmentDistance() {
        return maxAttachmentDistance;
    }

    public static void setAllowlistAttachRadius(double value) {
        allowlistAttachRadius = Math.max(0.0, value);
    }

    public static double allowlistAttachRadius() {
        return allowlistAttachRadius;
    }

    /**
     * Installs an optional allow/deny predicate. Returning {@code false} skips
     * the entity and removes an existing attachment. Base exclusions
     * (spectators, passengers, dead/removed entities, displays and dragon
     * parts) always apply.
     */
    public static void setEntityFilter(Predicate<Entity> filter) {
        entityFilter = filter == null ? entity -> true : filter;
    }

    /**
     * Binds an entity to an explicit structure-local point.
     *
     * <p>The point uses the same local metres as
     * {@link PhysicsStructure#pose()}. An explicit binding is a hard lock: the
     * entity is placed at {@code pose.transformPoint(localPoint)} every tick
     * and its local yaw is preserved. Use it for seats, contraption anchors or
     * entities that are not detected by the foot probe.</p>
     *
     * @return {@code true} when the binding was stored
     */
    public static boolean bind(Entity entity, PhysicsStructure structure, Vector3dc localPoint) {
        if (entity == null || structure == null || localPoint == null
                || structure.closed() || !(entity.level() instanceof ServerLevel level)
                || !isBaseEligible(entity) || !isFinite(localPoint)) {
            return false;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
        if (physics == null || physics.closed() || !physics.structures().contains(structure)) {
            return false;
        }
        final Transform3d pose = structure.pose();
        final Vector3d local = new Vector3d(localPoint);
        final double localYaw = EntityStructureRidingMath.localYawDegrees(
                entity.getYRot(), pose.rotation());
        ATTACHMENTS.put(entity.getUUID(), new Attachment(
                structure,
                entity.level().dimension(),
                local,
                true,
                isAllowlisted(entity),
                pose,
                localYaw
        ));
        return true;
    }

    /**
     * Binds an entity at its current position.
     */
    public static boolean bind(Entity entity, PhysicsStructure structure) {
        if (entity == null || structure == null || structure.closed()) {
            return false;
        }
        final Vector3d world = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        final Vector3d local = EntityStructureRidingMath.toLocal(structure.pose(), world);
        return bind(entity, structure, local);
    }

    public static boolean unbind(Entity entity) {
        return entity != null && ATTACHMENTS.remove(entity.getUUID()) != null;
    }

    public static boolean isBound(Entity entity) {
        return entity != null && ATTACHMENTS.containsKey(entity.getUUID());
    }

    /**
     * Snapshot of an entity's attachment, safe to inspect from the server
     * thread.
     */
    public static Optional<EntityStructureAttachment> attachment(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        final Attachment attachment = ATTACHMENTS.get(entity.getUUID());
        if (attachment == null) {
            return Optional.empty();
        }
        return Optional.of(new EntityStructureAttachment(
                attachment.structure,
                attachment.structureBodyId,
                attachment.localPoint,
                attachment.localYaw,
                attachment.explicit,
                attachment.allowlisted,
                attachment.ticks
        ));
    }

    public static int boundCount() {
        return ATTACHMENTS.size();
    }

    /**
     * Removes every attachment. Useful for tests and server shutdown.
     */
    public static void clear() {
        ATTACHMENTS.clear();
    }

    /**
     * Diagnostics from the most recent tick.
     */
    public static TickStats lastStats() {
        return lastStats;
    }

    /**
     * Adds an entity type to the explicit allowlist. Allowlisted entities stay
     * bound even when the automatic foot probe cannot find a surface.
     */
    public static void addAllowedEntityType(EntityType<?> type) {
        if (type != null) {
            ALLOWED_ENTITY_TYPES.add(type);
        }
    }

    public static boolean removeAllowedEntityType(EntityType<?> type) {
        return type != null && ALLOWED_ENTITY_TYPES.remove(type);
    }

    public static void setAllowedEntityTypes(Collection<EntityType<?>> types) {
        ALLOWED_ENTITY_TYPES.clear();
        if (types == null) {
            return;
        }
        for (EntityType<?> type : types) {
            if (type != null) {
                ALLOWED_ENTITY_TYPES.add(type);
            }
        }
    }

    public static Set<EntityType<?>> allowedEntityTypes() {
        return Set.copyOf(ALLOWED_ENTITY_TYPES);
    }

    /**
     * Adds an entity class name (including superclasses and interfaces) to the
     * allowlist. The name is never passed to {@code Class.forName}, so a
     * missing optional mod is safe.
     */
    public static void addAllowlistedClassName(String className) {
        if (className != null && !className.isBlank()) {
            ALLOWLISTED_CLASS_NAMES.add(className);
        }
    }

    public static boolean removeAllowlistedClassName(String className) {
        return className != null && ALLOWLISTED_CLASS_NAMES.remove(className);
    }

    public static void setAllowlistedClassNames(Collection<String> classNames) {
        ALLOWLISTED_CLASS_NAMES.clear();
        if (classNames == null) {
            addDefaultClassNames();
            return;
        }
        for (String className : classNames) {
            if (className != null && !className.isBlank()) {
                ALLOWLISTED_CLASS_NAMES.add(className);
            }
        }
    }

    public static Set<String> allowlistedClassNames() {
        return Set.copyOf(ALLOWLISTED_CLASS_NAMES);
    }

    public static Set<String> defaultAllowlistedClassNames() {
        return DEFAULT_ALLOWLISTED_CLASS_NAMES;
    }

    /**
     * Re-adds the built-in Create contraption class names.
     */
    public static void addDefaultClassNames() {
        ALLOWLISTED_CLASS_NAMES.addAll(DEFAULT_ALLOWLISTED_CLASS_NAMES);
    }

    /**
     * Whether the entity is allowlisted by type or by class hierarchy name.
     */
    public static boolean isAllowlisted(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (ALLOWED_ENTITY_TYPES.contains(entity.getType())) {
            return true;
        }
        if (ALLOWLISTED_CLASS_NAMES.isEmpty()) {
            return false;
        }
        return classHierarchyContains(entity.getClass(), ALLOWLISTED_CLASS_NAMES);
    }

    /**
     * Fabric callback: carry entities in every level that has a physics world.
     */
    public static void onEndServerTick(MinecraftServer server) {
        final long start = System.nanoTime();
        if (!enabled || !MinecraftPhysics.available()) {
            lastStats = new TickStats(0, 0, 0, 0, 0, 0, 0, System.nanoTime() - start);
            return;
        }

        int levels = 0;
        int structureCount = 0;
        int entityCount = 0;
        int attachedCount = 0;
        int carriedCount = 0;
        int autoAttached = 0;
        int detached = 0;

        for (ServerLevel level : server.getAllLevels()) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            if (physics == null || physics.closed()) {
                detached += detachLevel(level.dimension());
                continue;
            }
            final List<StructureView> structures = snapshot(physics);
            if (structures.isEmpty()) {
                detached += detachLevel(level.dimension());
                continue;
            }
            levels++;
            structureCount += structures.size();

            final Set<UUID> present = new HashSet<>();
            int scanned = 0;
            boolean completeScan = true;
            for (Entity entity : level.getAllEntities()) {
                if (scanned >= MAX_ENTITIES_PER_LEVEL) {
                    completeScan = false;
                    break;
                }
                scanned++;
                final UUID id = entity.getUUID();
                present.add(id);

                if (!isBaseEligible(entity)) {
                    if (ATTACHMENTS.remove(id) != null) {
                        detached++;
                    }
                    continue;
                }

                final Attachment existing = ATTACHMENTS.get(id);
                final boolean allowlisted = isAllowlisted(entity);
                if (entity.noPhysics && !allowlisted
                        && (existing == null || !existing.explicit)) {
                    if (ATTACHMENTS.remove(id) != null) {
                        detached++;
                    }
                    continue;
                }

                final Predicate<Entity> filter = entityFilter;
                if (filter != null && !filter.test(entity)) {
                    if (ATTACHMENTS.remove(id) != null) {
                        detached++;
                    }
                    continue;
                }

                entityCount++;
                if (existing != null) {
                    if (!existing.dimension.equals(level.dimension())
                            || !carry(entity, existing, structures)) {
                        ATTACHMENTS.remove(id);
                        detached++;
                    } else {
                        carriedCount++;
                        attachedCount++;
                    }
                    continue;
                }

                final StructureView support = findAutoAttachment(entity, structures, allowlisted);
                if (support != null) {
                    attach(entity, support, false, allowlisted);
                    autoAttached++;
                    attachedCount++;
                }
            }

            if (completeScan) {
                detached += detachMissing(level.dimension(), present);
            }
        }

        lastStats = new TickStats(levels, structureCount, entityCount, attachedCount,
                carriedCount, autoAttached, detached, System.nanoTime() - start);
    }

    private static List<StructureView> snapshot(LevelPhysics physics) {
        final List<PhysicsStructure> structures = physics.structures();
        if (structures.isEmpty()) {
            return List.of();
        }
        final List<StructureView> views = new ArrayList<>(structures.size());
        for (PhysicsStructure structure : structures) {
            if (structure.closed()) {
                continue;
            }
            final VoxelColliderData collider = structure.collider();
            if (collider.isEmpty()) {
                continue;
            }
            final Transform3d pose = structure.pose();
            final OrientedBox obb = structure.obb();
            final Aabb broad = obb.toAabb();
            views.add(new StructureView(
                    structure,
                    pose,
                    broad.expand(1.0),
                    broad.center(),
                    collider.boxes(),
                    collider.customParts(),
                    structure.blockScale()
            ));
        }
        return views;
    }

    private static boolean isBaseEligible(Entity entity) {
        return !entity.isRemoved()
                && entity.isAlive()
                && !entity.isSpectator()
                && !entity.isPassenger()
                && !(entity instanceof Display)
                && !(entity instanceof EnderDragonPart);
    }

    private static StructureView findAutoAttachment(Entity entity, List<StructureView> structures,
                                                    boolean allowlisted) {
        final double queryExpansion = allowlisted ? allowlistAttachRadius : 0.0;
        final List<StructureView> candidates = candidates(entity, structures, queryExpansion);
        if (candidates.isEmpty()) {
            return null;
        }

        StructureView best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        final Vector3d entityPosition = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        for (StructureView view : candidates) {
            final boolean supported = findSupport(entity, view, entityPosition) != null;
            if (allowlisted) {
                if (supported || nearAllowlisted(entity, view)) {
                    return view;
                }
                continue;
            }
            if (!supported) {
                continue;
            }
            final double distance = view.center().distanceSquared(entityPosition);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = view;
            }
        }
        return best;
    }

    private static List<StructureView> candidates(Entity entity, List<StructureView> structures,
                                                  double expansion) {
        final Aabb query = toAabb(entity.getBoundingBox()).expand(Math.max(0.0, expansion));
        List<StructureView> candidates = null;
        for (StructureView view : structures) {
            if (!view.bounds().intersects(query)) {
                continue;
            }
            if (candidates == null) {
                candidates = new ArrayList<>(4);
            }
            candidates.add(view);
        }
        if (candidates == null) {
            return List.of();
        }
        if (candidates.size() > MAX_STRUCTURES_PER_ENTITY) {
            final Vector3d entityPosition = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
            candidates.sort(Comparator.comparingDouble(
                    view -> view.center().distanceSquared(entityPosition)));
            candidates.subList(MAX_STRUCTURES_PER_ENTITY, candidates.size()).clear();
        }
        return candidates;
    }

    private static EntityStructureRidingMath.Support findSupport(Entity entity, StructureView view,
                                                                 Vector3d feet) {
        final AABB box = entity.getBoundingBox();
        final double halfX = (box.maxX - box.minX) * 0.5;
        final double halfZ = (box.maxZ - box.minZ) * 0.5;
        return EntityStructureRidingMath.findSupport(
                view.pose(),
                view.boxes(),
                view.parts(),
                view.blockScale(),
                feet,
                halfX,
                halfZ,
                supportTolerance,
                MAX_BOXES_PER_STRUCTURE
        );
    }

    private static boolean nearAllowlisted(Entity entity, StructureView view) {
        final Vector3d point = new Vector3d(
                entity.getX(),
                entity.getY() + entity.getBbHeight() * 0.5,
                entity.getZ()
        );
        if (!view.structure().obb().expand(allowlistAttachRadius).contains(point)) {
            return false;
        }
        return view.bounds().expand(allowlistAttachRadius)
                .intersects(toAabb(entity.getBoundingBox()));
    }

    private static void attach(Entity entity, StructureView view, boolean explicit,
                               boolean allowlisted) {
        final Vector3d world = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        final Vector3d local = EntityStructureRidingMath.toLocal(view.pose(), world);
        attach(entity, view, local, explicit, allowlisted);
    }

    private static void attach(Entity entity, StructureView view, Vector3d local,
                               boolean explicit, boolean allowlisted) {
        final Transform3d pose = view.pose();
        ATTACHMENTS.put(entity.getUUID(), new Attachment(
                view.structure(),
                entity.level().dimension(),
                local,
                explicit,
                allowlisted,
                pose,
                EntityStructureRidingMath.localYawDegrees(entity.getYRot(), pose.rotation())
        ));
    }

    private static boolean carry(Entity entity, Attachment attachment,
                                 List<StructureView> structures) {
        if (attachment.structure.closed()) {
            return false;
        }
        final StructureView view = findView(structures, attachment.structure);
        if (view == null) {
            return false;
        }

        final Transform3d currentPose = view.pose();
        final Vector3d expected = attachment.lastPose.transformPoint(attachment.localPoint);
        final Vec3 current = entity.position();
        final Vector3d currentPosition = new Vector3d(current.x, current.y, current.z);
        final double drift = expected.distanceSquared(currentPosition);
        if (drift > maxAttachmentDistance * maxAttachmentDistance) {
            return false;
        }
        if (attachment.explicit && !attachment.allowlisted
                && drift > JUMP_DETACH_DRIFT * JUMP_DETACH_DRIFT
                && currentPosition.y > expected.y + JUMP_DETACH_DRIFT
                && entity.getDeltaMovement().y > JUMP_DETACH_VELOCITY) {
            // A hard-locked entity cannot normally move away. Upward motion
            // that exceeds the anchor is a jump, so release it instead of
            // dragging it back down.
            return false;
        }

        final Vector3d targetAnchor = currentPose.transformPoint(attachment.localPoint);
        final Vector3d anchorDelta = new Vector3d(targetAnchor).sub(expected);
        if (anchorDelta.lengthSquared() > MAX_CARRY_DELTA * MAX_CARRY_DELTA) {
            return false;
        }

        final Vector3d targetPosition;
        if (attachment.explicit) {
            targetPosition = targetAnchor;
        } else {
            targetPosition = new Vector3d(currentPosition).add(anchorDelta);
        }
        if (!isFinite(targetPosition)) {
            return false;
        }

        if (!attachment.explicit && !attachment.allowlisted
                && findSupport(entity, view, targetPosition) == null) {
            return false;
        }

        final double newStructureYaw = EntityStructureRidingMath.structureYawDegrees(
                currentPose.rotation());
        final float targetYaw;
        if (attachment.explicit) {
            targetYaw = rotateYaw
                    ? (float) EntityStructureRidingMath.worldYawDegrees(
                    attachment.localYaw, currentPose.rotation())
                    : entity.getYRot();
        } else {
            double yaw = entity.getYRot();
            if (rotateYaw) {
                final double oldStructureYaw = EntityStructureRidingMath.structureYawDegrees(
                        attachment.lastPose.rotation());
                yaw += EntityStructureRidingMath.wrapDegrees(
                        newStructureYaw - oldStructureYaw);
            }
            targetYaw = (float) yaw;
        }

        if (EntityStructureRidingMath.needsCarryUpdate(
                currentPosition, targetPosition, entity.getYRot(), targetYaw)) {
            if (entity instanceof ServerPlayer player) {
                // Moving-platform riding must not glue the player with a
                // contact teleport. Add the platform's per-tick displacement to
                // the player velocity instead; vanilla movement then carries
                // the player and the client keeps predicting its own position.
                final Vec3 motion = player.getDeltaMovement();
                player.setDeltaMovement(
                        motion.x + anchorDelta.x,
                        motion.y + anchorDelta.y,
                        motion.z + anchorDelta.z);
            } else {
                setPosition(entity, targetPosition, targetYaw);
            }
        }
        entity.setOnGround(true);
        entity.resetFallDistance();
        final Vec3 motion = entity.getDeltaMovement();
        if (motion.y < 0.0) {
            entity.setDeltaMovement(motion.x, 0.0, motion.z);
        }

        if (!attachment.explicit) {
            attachment.localPoint.set(
                    EntityStructureRidingMath.toLocal(currentPose, targetPosition));
            attachment.localYaw = EntityStructureRidingMath.wrapDegrees(
                    targetYaw - newStructureYaw);
        }
        attachment.lastPose = currentPose;
        attachment.ticks++;
        return true;
    }

    private static StructureView findView(List<StructureView> structures,
                                          PhysicsStructure structure) {
        for (StructureView view : structures) {
            if (view.structure() == structure) {
                return view;
            }
        }
        return null;
    }

    private static void setPosition(Entity entity, Vector3d position, float yaw) {
        entity.setPos(position.x, position.y, position.z);
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
    }

    private static Aabb toAabb(AABB box) {
        return new Aabb(
                new Vector3d(box.minX, box.minY, box.minZ),
                new Vector3d(box.maxX, box.maxY, box.maxZ)
        );
    }

    private static int detachLevel(ResourceKey<Level> dimension) {
        int removed = 0;
        final Iterator<Map.Entry<UUID, Attachment>> iterator = ATTACHMENTS.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Attachment> entry = iterator.next();
            if (entry.getValue().dimension.equals(dimension)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static int detachMissing(ResourceKey<Level> dimension, Set<UUID> present) {
        int removed = 0;
        final Iterator<Map.Entry<UUID, Attachment>> iterator = ATTACHMENTS.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Attachment> entry = iterator.next();
            if (entry.getValue().dimension.equals(dimension)
                    && !present.contains(entry.getKey())) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static boolean classHierarchyContains(Class<?> type, Set<String> names) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (names.contains(current.getName())) {
                return true;
            }
            for (Class<?> iface : current.getInterfaces()) {
                if (interfaceHierarchyContains(iface, names)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean interfaceHierarchyContains(Class<?> type, Set<String> names) {
        if (names.contains(type.getName())) {
            return true;
        }
        for (Class<?> parent : type.getInterfaces()) {
            if (interfaceHierarchyContains(parent, names)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFinite(Vector3dc point) {
        return Double.isFinite(point.x()) && Double.isFinite(point.y())
                && Double.isFinite(point.z());
    }

    /**
     * Per-tick diagnostics for logs and debug commands.
     */
    public record TickStats(int levels, int structures, int entities, int attached, int carried,
                            int autoAttached, int detached, long nanos) {
        public boolean changedAnything() {
            return carried > 0 || autoAttached > 0 || detached > 0;
        }
    }

    private record StructureView(
            PhysicsStructure structure,
            Transform3d pose,
            Aabb bounds,
            Vector3d center,
            List<VoxelBox> boxes,
            List<io.mikaple.crythst.physics.voxel.VoxelCellPart> parts,
            double blockScale
    ) {
    }

    private static final class Attachment {
        private final PhysicsStructure structure;
        private final long structureBodyId;
        private final ResourceKey<Level> dimension;
        private final Vector3d localPoint;
        private final boolean explicit;
        private final boolean allowlisted;
        private Transform3d lastPose;
        private double localYaw;
        private int ticks;

        private Attachment(PhysicsStructure structure, ResourceKey<Level> dimension,
                           Vector3d localPoint, boolean explicit, boolean allowlisted,
                           Transform3d lastPose, double localYaw) {
            this.structure = structure;
            this.structureBodyId = structure.body().id();
            this.dimension = dimension;
            this.localPoint = new Vector3d(localPoint);
            this.explicit = explicit;
            this.allowlisted = allowlisted;
            this.lastPose = lastPose;
            this.localYaw = localYaw;
        }
    }
}
