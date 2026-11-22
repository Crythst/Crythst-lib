package io.mikaple.crythst.integration.minecraft.entity.attachment;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.minecraft.EmptyPhysicsLevel;
import io.mikaple.crythst.integration.minecraft.entity.projection.StructureEntityProjection;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host-world mirrors for entities that belong to a physics structure.
 *
 * <p>Ordinary entities spawned inside the auxiliary void level (items, TNT,
 * arrows, minecarts) are ejected to the host world and then live there as
 * normal entities. Decorations and rideable seats are different: they must stay
 * rigidly attached to the moving structure and must use their full vanilla
 * client rendering (an item frame with its item, a painting, a Create seat).
 * This registry therefore creates a <em>real host entity</em> and stores only a
 * structure-local anchor. Every server tick it maps that anchor through the
 * current structure pose and moves the host mirror (and its passengers) to the
 * resulting world transform.</p>
 *
 * <p>Attached mirrors are marked {@code noPhysics}, no-gravity and invulnerable.
 * Structure collision already ignores {@code noPhysics} entities, so the mirror
 * cannot collide with or be ejected by its own structure. Passengers are also
 * excluded by the vanilla passenger rule. This is intentionally independent of
 * interaction/menu code.</p>
 *
 * <p>Anchors use the same structure-local metres as
 * {@link PhysicsStructure#pose()}. A void-level position is converted with the
 * structure's {@link EmptyPhysicsLevel.RegionView} and block scale, which keeps
 * the mapping identical to {@link StructureEntityProjection}.</p>
 *
 * <p>Server thread only.</p>
 */
public final class StructureAttachedEntities {
    /** Kind of attachment. Ordinary entities are never stored here. */
    public enum Kind {
        /** Item frames, paintings, leash knots and similar block-attached entities. */
        DECORATION,
        /** Seats and other mountable entities placed by a structure entity item. */
        RIDEABLE
    }

    /**
     * Immutable diagnostic view of one attachment.
     */
    public record AttachedEntity(UUID entityId,
                                 String entityType,
                                 long structureBodyId,
                                 Kind kind,
                                 Vector3d localAnchor,
                                 float localYaw,
                                 int riders) {
        public AttachedEntity {
            localAnchor = new Vector3d(localAnchor);
        }

        @Override
        public Vector3d localAnchor() {
            return new Vector3d(localAnchor);
        }
    }

    private static final Set<String> DEFAULT_RIDEABLE_CLASS_NAMES = Set.of(
            "com.simibubi.create.content.contraptions.actors.seat.SeatEntity",
            "com.zurrtum.create.content.contraptions.actors.seat.SeatEntity"
    );

    private final ServerLevel host;
    private final Map<UUID, Attachment> attachments = new LinkedHashMap<>();
    private final Set<EntityType<?>> rideableTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> rideableClassNames = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> adoptionFailures = new HashMap<>();
    private long totalAttached;
    private long totalAdoptedFromVoid;
    private long totalReleased;
    private int failures;
    private String lastFailure = "";

    public StructureAttachedEntities(ServerLevel host) {
        if (host == null) {
            throw new NullPointerException("host");
        }
        this.host = host;
        this.rideableClassNames.addAll(DEFAULT_RIDEABLE_CLASS_NAMES);
    }

    /**
     * Registers a modded seat/rideable entity type. Registration is optional:
     * class/id names containing {@code seat} are also recognised.
     */
    public void registerRideableType(@Nullable EntityType<?> type) {
        if (type != null) {
            rideableTypes.add(type);
        }
    }

    /** Registers a rideable class name (including superclasses/interfaces). */
    public void registerRideableClassName(@Nullable String className) {
        if (className != null && !className.isBlank()) {
            rideableClassNames.add(className);
        }
    }

    /** Whether an entity should be attached instead of ejected as ordinary. */
    public boolean shouldAttachOnEjection(Entity entity) {
        return classify(entity) != null;
    }

    /**
     * Classifies an entity for the ejection policy.
     *
     * @return {@link Kind#DECORATION}, {@link Kind#RIDEABLE}, or {@code null}
     *         when the entity is an ordinary ejected entity
     */
    public @Nullable Kind classify(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof BlockAttachedEntity || entity instanceof HangingEntity) {
            return Kind.DECORATION;
        }
        if (isRideable(entity)) {
            return Kind.RIDEABLE;
        }
        return null;
    }

    /**
     * Whether the entity is a seat/rideable entity by explicit registration,
     * entity-type id or class name. The heuristic is intentionally conservative
     * and can always be overridden with {@link #registerRideableType}.
     */
    public boolean isRideable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (rideableTypes.contains(entity.getType())) {
            return true;
        }
        final Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (looksLikeSeat(id == null ? "" : id.getPath(), entity.getClass().getName())) {
            return true;
        }
        if (rideableClassNames.isEmpty()) {
            return false;
        }
        Class<?> type = entity.getClass();
        while (type != null) {
            if (rideableClassNames.contains(type.getName())) {
                return true;
            }
            for (Class<?> iface : type.getInterfaces()) {
                if (rideableClassNames.contains(iface.getName())) {
                    return true;
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /**
     * Pure name heuristic used for modded seats. Exposed for tests and for
     * diagnostics; callers can register exact types instead.
     */
    public static boolean looksLikeSeat(String typeId, String className) {
        final String id = typeId == null ? "" : typeId.toLowerCase(Locale.ROOT);
        final String name = className == null ? "" : className.toLowerCase(Locale.ROOT);
        return id.contains("seat") || name.contains("seat");
    }

    /**
     * Attaches an existing host entity at an explicit structure-local anchor.
     *
     * @param entity      real host entity
     * @param structure   owning structure
     * @param localAnchor structure-local metres; identity pose is structure-local
     * @param localYaw    entity yaw in structure-local degrees
     * @param kind        decoration or rideable
     * @return {@code true} when stored
     */
    public boolean attach(Entity entity, PhysicsStructure structure,
                          Vector3dc localAnchor, float localYaw, Kind kind) {
        if (entity == null || structure == null || localAnchor == null || kind == null
                || structure.closed() || entity.isRemoved()
                || !Double.isFinite(localAnchor.x()) || !Double.isFinite(localAnchor.y())
                || !Double.isFinite(localAnchor.z())) {
            return false;
        }
        if (entity.level() != host) {
            return false;
        }
        final Attachment attachment = new Attachment(entity, structure,
                new Vector3d(localAnchor), localYaw, kind);
        attachments.put(entity.getUUID(), attachment);
        markMirror(entity, kind);
        totalAttached++;
        return true;
    }

    /**
     * Attaches a decorative entity (item frame, painting, leash knot, ...).
     */
    public boolean attachDecoration(Entity entity, PhysicsStructure structure,
                                    Vector3dc localAnchor, float localYaw) {
        return attach(entity, structure, localAnchor, localYaw, Kind.DECORATION);
    }

    /**
     * Attaches a rideable seat. The host mirror keeps vanilla mounting and
     * rendering; this registry only moves it rigidly with the structure.
     */
    public boolean attachRideable(Entity entity, PhysicsStructure structure,
                                  Vector3dc localAnchor, float localYaw) {
        return attach(entity, structure, localAnchor, localYaw, Kind.RIDEABLE);
    }

    /**
     * Attaches an entity at its current world position. This is the convenience
     * entry point for placement handlers that already spawned a real host
     * entity on a structure surface.
     */
    public boolean attachAtCurrentPosition(Entity entity, PhysicsStructure structure, Kind kind) {
        if (entity == null || structure == null || kind == null) {
            return false;
        }
        final Transform3d pose = structure.pose();
        final Vector3d local = pose.inverseTransformPoint(
                new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
        final float localYaw = localYaw(structure, entity.getYRot());
        return attach(entity, structure, local, localYaw, kind);
    }

    /**
     * Adopts an entity that block logic created in the auxiliary void level.
     * A fresh host entity is loaded from the void entity's NBT so item stacks,
     * frame items, painting variants and seat data survive. The void copy is
     * removed only after the host entity was accepted.
     *
     * @return {@code true} when the entity is now managed by this registry
     */
    public boolean adoptVoidEntity(PhysicsStructure structure,
                                   EmptyPhysicsLevel.RegionView region,
                                   Entity voidEntity) {
        if (structure == null || region == null || voidEntity == null
                || structure.closed() || voidEntity.isRemoved()) {
            return false;
        }
        final Kind kind = classify(voidEntity);
        if (kind == null) {
            return false;
        }
        final UUID id = voidEntity.getUUID();
        if (host.getEntity(id) != null) {
            // A previous pass already created the mirror but could not remove
            // the void copy. Drop the stale void entity; do not duplicate.
            voidEntity.discard();
            return true;
        }

        final Vector3d local = localFromVoid(region, structure.blockScale(),
                voidEntity.getX(), voidEntity.getY(), voidEntity.getZ());
        final Transform3d pose = structure.pose();
        final Vector3d world = pose.transformPoint(local);
        final float localYaw = localYaw(structure, voidEntity.getYRot());
        final float worldYaw = worldYaw(structure, localYaw);

        Entity mirror = null;
        try {
            mirror = createHostMirror(voidEntity, id, world, worldYaw, kind);
            if (mirror == null) {
                failures++;
                lastFailure = "could not create host mirror for " + voidEntity.getType();
                recordAdoptionFailure(voidEntity);
                return false;
            }
            if (!host.addFreshEntity(mirror)) {
                // A mirror with this UUID is already present; treat it as adopted.
                if (host.getEntity(id) == null) {
                    mirror.discard();
                    failures++;
                    lastFailure = "host rejected mirror for " + voidEntity.getType();
                    recordAdoptionFailure(voidEntity);
                    return false;
                }
            }
            voidEntity.discard();
            adoptionFailures.remove(id);
            totalAdoptedFromVoid++;
            return attach(mirror, structure, local, localYaw, kind);
        } catch (Throwable t) {
            failures++;
            lastFailure = "adopt " + voidEntity.getType() + ": " + t.getMessage();
            Crythst.LOGGER.warn("Could not adopt attached {} entity {} from the auxiliary void level",
                    voidEntity.getType(), id, t);
            if (mirror != null && host.getEntity(id) == null) {
                mirror.discard();
            }
            recordAdoptionFailure(voidEntity);
            return false;
        }
    }

    /**
     * A void entity that cannot be recreated in the host world (for example an
     * unknown modded type) must not be retried every tick. After a few attempts
     * drop the void copy instead of spamming "Skipping Entity with id [invalid]"
     * and leaking work.
     */
    private void recordAdoptionFailure(Entity voidEntity) {
        final int attempts = adoptionFailures.merge(voidEntity.getUUID(), 1, Integer::sum);
        if (attempts >= 3) {
            adoptionFailures.remove(voidEntity.getUUID());
            voidEntity.discard();
        }
    }

    private @Nullable Entity createHostMirror(Entity voidEntity, UUID id, Vector3d world,
                                              float worldYaw, Kind kind) {
        final TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, host.registryAccess());
        // saveWithoutId omits the "id" field that EntityType#loadEntityRecursive
        // needs; without it every adoption logs "Skipping Entity with id
        // [invalid]" and retries forever. saveAsPassenger writes the id too.
        if (!voidEntity.saveAsPassenger(output)) {
            return null;
        }
        final CompoundTag tag = output.buildResult();
        if (kind == Kind.DECORATION) {
            // Item frames support a Fixed flag so they do not pop off when the
            // host world has no support block. Unknown tags are ignored by
            // other decorative entity types.
            tag.putBoolean("Fixed", true);
        }
        final Entity mirror = EntityType.loadEntityRecursive(
                TagValueInput.create(ProblemReporter.DISCARDING, host.registryAccess(), tag),
                host, EntitySpawnReason.LOAD, net.minecraft.world.entity.EntityProcessor.NOP);
        if (mirror == null) {
            return null;
        }
        mirror.setUUID(id);
        mirror.setPos(world.x, world.y, world.z);
        mirror.setYRot(worldYaw);
        mirror.setOldPosAndRot();
        markMirror(mirror, kind);
        return mirror;
    }

    private void markMirror(Entity entity, Kind kind) {
        entity.noPhysics = true;
        entity.setNoGravity(true);
        entity.setInvulnerableTime(Integer.MAX_VALUE);
        entity.setDeltaMovement(Vec3.ZERO);
        if (kind == Kind.RIDEABLE) {
            entity.setSilent(true);
        }
    }

    /**
     * Moves every host mirror to {@code structure.pose() * localAnchor} and
     * repositions passengers rigidly. Passenger look rotation is deliberately
     * not changed; only their positions are mapped through the structure pose.
     */
    public void tick() {
        if (attachments.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<UUID, Attachment>> iterator =
                new ArrayList<>(attachments.entrySet()).iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Attachment> entry = iterator.next();
            final Attachment attachment = entry.getValue();
            final Entity entity = attachment.entity;
            if (entity.isRemoved()) {
                // A destroyed seat/frame must not be resurrected. Make sure a
                // stale passenger reference cannot outlive the removed vehicle.
                for (Entity passenger : List.copyOf(entity.getPassengers())) {
                    passenger.stopRiding();
                }
                attachments.remove(entry.getKey());
                continue;
            }
            final PhysicsStructure structure = attachment.structure;
            if (structure.closed()) {
                release(entry.getKey(), true);
                continue;
            }
            final Transform3d pose = structure.pose();
            final Vector3d world = pose.transformPoint(attachment.localAnchor);
            final float worldYaw = worldYaw(structure, attachment.localYaw);
            moveMirror(entity, world, worldYaw);
            entity.setDeltaMovement(Vec3.ZERO);
            attachment.ticks++;
            updatePassengers(attachment, pose);
        }
    }

    /**
     * Moves one host mirror. Hanging entities override {@code setPos} and only
     * update their attached block position, so plain {@code setPos} leaves the
     * frame/painting at its old world coordinates. Set the raw entity position
     * first, then let the override refresh the attached position and box.
     */
    private static void moveMirror(Entity entity, Vector3d world, float worldYaw) {
        entity.setYRot(worldYaw);
        if (entity instanceof net.minecraft.world.entity.decoration.BlockAttachedEntity) {
            entity.setPosRaw(world.x, world.y, world.z);
            entity.setPos(world.x, world.y, world.z);
        } else {
            entity.setPos(world.x, world.y, world.z);
        }
    }

    private void updatePassengers(Attachment attachment, Transform3d pose) {
        final Entity vehicle = attachment.entity;
        final List<Entity> passengers = vehicle.getPassengers();
        final Set<UUID> present = new java.util.HashSet<>();
        for (Entity passenger : passengers) {
            if (passenger == null || passenger.isRemoved()) {
                continue;
            }
            present.add(passenger.getUUID());
            final Vector3d local = attachment.riderAnchors.computeIfAbsent(
                    passenger.getUUID(),
                    ignored -> pose.inverseTransformPoint(new Vector3d(
                            passenger.getX(), passenger.getY(), passenger.getZ())));
            final Vector3d world = pose.transformPoint(local);
            passenger.setPos(world.x, world.y, world.z);
            passenger.setDeltaMovement(Vec3.ZERO);
            passenger.setOldPosAndRot();
            // Preserve the rider's own yaw/pitch. Vanilla would rotate the
            // offset by the vehicle yaw only; the full structure rotation is
            // applied above through the pose.
        }
        attachment.riderAnchors.keySet().removeIf(id -> !present.contains(id));
    }

    /** Returns whether this exact entity is a host mirror managed here. */
    public boolean isManaged(@Nullable Entity entity) {
        return entity != null && attachments.containsKey(entity.getUUID());
    }

    /**
     * Returns whether the entity is a managed mirror or a passenger riding one.
     * Movement/collision code can use this to avoid double-carrying.
     */
    public boolean isManagedOrRider(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        if (isManaged(entity)) {
            return true;
        }
        final Entity vehicle = entity.getVehicle();
        return vehicle != null && isManaged(vehicle);
    }

    /**
     * Reassigns attachments after a split. New split bodies keep the original
     * bounds and local coordinates, so the anchor chooses the component whose
     * solid volume or collider is nearest to it.
     */
    public void relocate(PhysicsStructure original, List<PhysicsStructure> results) {
        if (original == null) {
            return;
        }
        final List<PhysicsStructure> live = new ArrayList<>();
        if (results != null) {
            for (PhysicsStructure structure : results) {
                if (structure != null && !structure.closed()) {
                    live.add(structure);
                }
            }
        }
        final List<UUID> released = new ArrayList<>();
        for (Map.Entry<UUID, Attachment> entry : attachments.entrySet()) {
            final Attachment attachment = entry.getValue();
            if (attachment.structure != original) {
                continue;
            }
            final PhysicsStructure best = bestComponent(attachment.localAnchor, live);
            if (best == null) {
                released.add(entry.getKey());
            } else {
                attachment.structure = best;
            }
        }
        for (UUID id : released) {
            release(id, false);
        }
    }

    private @Nullable PhysicsStructure bestComponent(Vector3d localAnchor,
                                                     List<PhysicsStructure> live) {
        if (live.isEmpty()) {
            return null;
        }
        PhysicsStructure best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        final int x = (int) Math.floor(localAnchor.x);
        final int y = (int) Math.floor(localAnchor.y);
        final int z = (int) Math.floor(localAnchor.z);
        for (PhysicsStructure structure : live) {
            final IntBounds3 bounds = structure.volume().bounds();
            if (bounds.contains(x, y, z) && structure.volume().isSolid(x, y, z)) {
                return structure;
            }
            double distance = distanceSquaredToBounds(bounds, localAnchor);
            final var boxes = structure.collider().boxes();
            if (!boxes.isEmpty()) {
                distance = Double.POSITIVE_INFINITY;
                for (var box : boxes) {
                    distance = Math.min(distance, distanceSquaredToBox(box, localAnchor));
                }
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = structure;
            }
        }
        return best;
    }

    private static double distanceSquaredToBounds(IntBounds3 bounds, Vector3d point) {
        final double dx = axisDistance(point.x, bounds.minX(), bounds.maxX());
        final double dy = axisDistance(point.y, bounds.minY(), bounds.maxY());
        final double dz = axisDistance(point.z, bounds.minZ(), bounds.maxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSquaredToBox(
            io.mikaple.crythst.physics.voxel.VoxelBox box, Vector3d point) {
        final double dx = axisDistance(point.x, box.minX(), box.minX() + box.sizeX());
        final double dy = axisDistance(point.y, box.minY(), box.minY() + box.sizeY());
        final double dz = axisDistance(point.z, box.minZ(), box.minZ() + box.sizeZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

    /** Releases every attachment that belongs to the structure. */
    public void releaseStructure(PhysicsStructure structure) {
        if (structure == null) {
            return;
        }
        final List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, Attachment> entry : attachments.entrySet()) {
            if (entry.getValue().structure == structure) {
                ids.add(entry.getKey());
            }
        }
        for (UUID id : ids) {
            release(id, true);
        }
    }

    /**
     * Stops managing one attachment. With {@code restoreMovement} the host
     * entity is returned to normal movement; without it the entity is left at
     * its last valid pose (used when a seat was destroyed and vanilla already
     * removed/dismounted it).
     */
    public boolean release(UUID id, boolean restoreMovement) {
        final Attachment attachment = attachments.remove(id);
        if (attachment == null) {
            return false;
        }
        totalReleased++;
        final Entity entity = attachment.entity;
        if (entity != null && !entity.isRemoved() && restoreMovement) {
            if (!(entity instanceof BlockAttachedEntity)) {
                entity.noPhysics = false;
                entity.setNoGravity(false);
            }
            entity.setDeltaMovement(Vec3.ZERO);
        }
        attachment.riderAnchors.clear();
        return true;
    }

    public boolean release(@Nullable Entity entity) {
        return entity != null && release(entity.getUUID(), true);
    }

    /** Releases every attachment, leaving all host entities in the world. */
    public void clear() {
        final List<UUID> ids = new ArrayList<>(attachments.keySet());
        for (UUID id : ids) {
            release(id, true);
        }
    }

    /** Number of tracked mirrors. */
    public int attachedCount() {
        return attachments.size();
    }

    public int decorationCount() {
        int count = 0;
        for (Attachment attachment : attachments.values()) {
            if (attachment.kind == Kind.DECORATION) {
                count++;
            }
        }
        return count;
    }

    public int rideableCount() {
        int count = 0;
        for (Attachment attachment : attachments.values()) {
            if (attachment.kind == Kind.RIDEABLE) {
                count++;
            }
        }
        return count;
    }

    public int riderCount() {
        int count = 0;
        for (Attachment attachment : attachments.values()) {
            count += attachment.riderAnchors.size();
        }
        return count;
    }

    public int failureCount() {
        return failures;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public long totalAttached() {
        return totalAttached;
    }

    public long totalAdoptedFromVoid() {
        return totalAdoptedFromVoid;
    }

    public long totalReleased() {
        return totalReleased;
    }

    public List<AttachedEntity> snapshot() {
        final List<AttachedEntity> result = new ArrayList<>(attachments.size());
        for (Attachment attachment : attachments.values()) {
            final Entity entity = attachment.entity;
            if (entity == null) {
                continue;
            }
            result.add(new AttachedEntity(entity.getUUID(),
                    String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())),
                    attachment.structure.body().id(),
                    attachment.kind,
                    attachment.localAnchor,
                    attachment.localYaw,
                    attachment.riderAnchors.size()));
        }
        return List.copyOf(result);
    }

    /** One-line diagnostic summary. */
    public String summary() {
        return "attached=" + attachedCount()
                + " decorations=" + decorationCount()
                + " rideable=" + rideableCount()
                + " riders=" + riderCount()
                + " adoptedFromVoid=" + totalAdoptedFromVoid
                + " released=" + totalReleased
                + " failures=" + failures
                + (lastFailure.isEmpty() ? "" : " lastFailure=" + lastFailure);
    }

    /**
     * Converts a void-level position to structure-local metres. This is the
     * same conversion used by {@link StructureEntityProjection#projectPosition}.
     */
    public static Vector3d localFromVoid(EmptyPhysicsLevel.RegionView region,
                                         double blockScale,
                                         double x, double y, double z) {
        final double scale = blockScale > 1.0e-9 && Double.isFinite(blockScale)
                ? blockScale : 1.0;
        return new Vector3d(
                (x - region.localOriginX()) * scale,
                (y - region.localOriginY()) * scale,
                (z - region.localOriginZ()) * scale);
    }

    /** World position of a local anchor for a structure pose. */
    public static Vector3d worldAnchor(Transform3d pose, Vector3dc localAnchor) {
        return pose == null
                ? new Vector3d(localAnchor)
                : pose.transformPoint(localAnchor);
    }

    /** Converts a world yaw into structure-local yaw. */
    public static float localYaw(PhysicsStructure structure, float worldYaw) {
        return localYaw(structure.pose(), worldYaw);
    }

    /** Converts a structure-local yaw into world yaw. */
    public static float worldYaw(PhysicsStructure structure, float localYaw) {
        return worldYaw(structure.pose(), localYaw);
    }

    /** Pose-only overload, useful for tests and custom integrations. */
    public static float localYaw(Transform3d pose, float worldYaw) {
        final Transform3d safePose = pose == null ? Transform3d.identity() : pose;
        final float local = StructureEntityProjection.projectRotation(
                safePose.inverse(), worldYaw, 0.0f).y;
        return wrapDegrees(local);
    }

    /** Pose-only overload, useful for tests and custom integrations. */
    public static float worldYaw(Transform3d pose, float localYaw) {
        final Transform3d safePose = pose == null ? Transform3d.identity() : pose;
        final float world = StructureEntityProjection.projectRotation(
                safePose, localYaw, 0.0f).y;
        return wrapDegrees(world);
    }

    private static float wrapDegrees(float value) {
        float result = value % 360.0f;
        if (result >= 180.0f) {
            result -= 360.0f;
        }
        if (result < -180.0f) {
            result += 360.0f;
        }
        return result;
    }

    private static final class Attachment {
        private final Entity entity;
        private PhysicsStructure structure;
        private final Vector3d localAnchor;
        private final float localYaw;
        private final Kind kind;
        private final Map<UUID, Vector3d> riderAnchors = new LinkedHashMap<>();
        private int ticks;

        private Attachment(Entity entity, PhysicsStructure structure,
                           Vector3d localAnchor, float localYaw, Kind kind) {
            this.entity = entity;
            this.structure = structure;
            this.localAnchor = localAnchor;
            this.localYaw = localYaw;
            this.kind = kind;
        }
    }
}
