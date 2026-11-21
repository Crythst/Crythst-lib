package io.mikaple.crythst.integration.create;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps Create contraption entities and their passengers glued to a
 * {@link PhysicsStructure}.
 *
 * <p>The bridge is intentionally small and opt-in. It stores one local
 * {@link Transform3d} per entity, composes it with the structure's current
 * pose, and writes the result back through the vanilla
 * {@link Entity#setPos(double, double, double)} API. Because Create's
 * contraption entities override {@code setPos} (and thereby move their
 * bounding box), the correct Create entry point is called without any compile
 * dependency on Create.</p>
 *
 * <p>Server-only usage is expected. The lazy {@link ServerTickEvents#END_SERVER_TICK}
 * hook runs after {@code PhysicsTicker}/{@code MinecraftPhysics} for a given
 * server tick, so it applies the pose produced by the latest fixed physics
 * step. A riding manager may also call {@link #tick(Entity)} or
 * {@link #tickAll(ServerLevel)} explicitly; repeated application in one tick
 * is idempotent.</p>
 *
 * <h2>What this does not do</h2>
 * <ul>
 *     <li>It does not run contraption block actors, mounted storage or
 *     interaction handling. Create still does that in its own entity tick.</li>
 *     <li>It does not integrate contraption collision with the structure
 *     collider. Use the entity riding manager for that.</li>
 *     <li>It does not force Create's internal rotation state
 *     ({@code yaw}/{@code angle}); only the entity anchor is written. See
 *     {@link #apply(Entity, StructureAttachmentTarget, boolean)} for an opt-in
 *     yaw approximation.</li>
 * </ul>
 *
 * @see CreateCompat
 * @see StructureAttachmentTarget
 */
public final class CreateStructureBridge {
    /** Maximum rider recursion depth handled when translating passengers. */
    public static final int MAX_PASSENGER_DEPTH = 4;

    private static final Map<Entity, StructureAttachment> ATTACHMENTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean TICKER_REGISTERED = new AtomicBoolean();
    private static volatile boolean tickFailureLogged;

    private CreateStructureBridge() {
    }

    /**
     * Subscribes the registry to the end of the server tick. Called lazily by
     * {@link CreateCompat#register()} and on the first attachment. Idempotent.
     *
     * @return {@code true} when the hook is installed
     */
    public static boolean registerTicker() {
        if (!CreateCompat.isLoaded()) {
            return false;
        }
        if (!TICKER_REGISTERED.compareAndSet(false, true)) {
            return true;
        }
        boolean tickerInstalled = false;
        try {
            ServerTickEvents.END_SERVER_TICK.register(CreateStructureBridge::onEndServerTick);
            tickerInstalled = true;
            ServerLevelEvents.UNLOAD.register((server, level) -> detachLevel(level));
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
            return true;
        } catch (RuntimeException | LinkageError error) {
            if (!tickerInstalled) {
                TICKER_REGISTERED.set(false);
            }
            Crythst.LOGGER.warn(
                    "Could not register the Create contraption structure ticker; "
                            + "call CreateStructureBridge.tickAll(level) from the riding manager instead",
                    error);
            return false;
        }
    }

    /**
     * Attaches the entity at its current world position.
     *
     * @return the attachment, or empty when the entity/structure is gone
     */
    public static Optional<StructureAttachment> attach(Entity entity, PhysicsStructure structure) {
        if (!canAttach(entity, structure)) {
            return Optional.empty();
        }
        return attach(entity, structure, captureLocalTransform(entity, structure));
    }

    /**
     * Attaches the entity with an explicit structure-local pose.
     */
    public static Optional<StructureAttachment> attach(Entity entity, PhysicsStructure structure,
                                                       Transform3d localTransform) {
        if (!canAttach(entity, structure) || localTransform == null || !localTransform.isFinite()) {
            return Optional.empty();
        }
        final StructureAttachment attachment =
                new StructureAttachment(entity.getUUID(), structure, localTransform);
        ATTACHMENTS.put(entity, attachment);
        registerTicker();
        return Optional.of(attachment);
    }

    /**
     * Attaches the entity at an explicit structure-local position.
     */
    public static Optional<StructureAttachment> attachAtLocalPosition(Entity entity, PhysicsStructure structure,
                                                                      Vector3dc localPosition) {
        if (localPosition == null) {
            return Optional.empty();
        }
        return attach(entity, structure, Transform3d.of(localPosition));
    }

    /**
     * Re-captures the entity's current world position as the new local anchor.
     */
    public static Optional<StructureAttachment> resetAnchor(Entity entity) {
        final Optional<StructureAttachment> current = attachmentOf(entity);
        if (current.isEmpty() || entity == null) {
            return Optional.empty();
        }
        final StructureAttachment previous = current.get();
        if (!previous.isValid()) {
            ATTACHMENTS.remove(entity, previous);
            return Optional.empty();
        }
        return attach(entity, previous.structure(), captureLocalTransform(entity, previous.structure()));
    }

    /**
     * Removes the entity's attachment.
     */
    public static Optional<StructureAttachment> detach(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ATTACHMENTS.remove(entity));
    }

    /**
     * Removes every attachment that points at the given structure. Call when a
     * structure is disassembled or removed.
     *
     * @return number of removed attachments
     */
    public static int detachStructure(PhysicsStructure structure) {
        if (structure == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<Entity, StructureAttachment> entry : ATTACHMENTS.entrySet()) {
            if (entry.getValue().structure() == structure
                    && ATTACHMENTS.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes every attachment for entities in the given level. Called when a
     * level unloads and available to the riding manager for dimension changes.
     *
     * @return number of removed attachments
     */
    public static int detachLevel(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<Entity, StructureAttachment> entry : ATTACHMENTS.entrySet()) {
            if (entry.getKey().level() == level
                    && ATTACHMENTS.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes every attachment whose entity belongs to the given level and is
     * no longer present. Called automatically by {@link #tickAll(ServerLevel)}.
     */
    public static int prune(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<Entity, StructureAttachment> entry : ATTACHMENTS.entrySet()) {
            final Entity entity = entry.getKey();
            if (entity.level() != level) {
                continue;
            }
            if (entity.isRemoved() || !entry.getValue().isValid()) {
                if (ATTACHMENTS.remove(entity, entry.getValue())) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Drops every attachment. Intended for server shutdown and tests.
     */
    public static void clear() {
        ATTACHMENTS.clear();
    }

    public static int attachedCount() {
        return ATTACHMENTS.size();
    }

    public static Optional<StructureAttachment> attachmentOf(Entity entity) {
        return entity == null ? Optional.empty() : Optional.ofNullable(ATTACHMENTS.get(entity));
    }

    public static Optional<PhysicsStructure> structureOf(Entity entity) {
        return attachmentOf(entity).map(StructureAttachment::structure);
    }

    public static Optional<Transform3d> localTransformOf(Entity entity) {
        return attachmentOf(entity).map(StructureAttachment::localTransform);
    }

    /**
     * Applies the attachment for one entity.
     *
     * @return {@code true} when the entity was moved
     */
    public static boolean tick(Entity entity) {
        final Optional<StructureAttachment> attachment = attachmentOf(entity);
        if (attachment.isEmpty()) {
            return false;
        }
        final StructureAttachment value = attachment.get();
        if (!isUsable(entity, value)) {
            ATTACHMENTS.remove(entity, value);
            return false;
        }
        try {
            return apply(entity, value, false);
        } catch (RuntimeException | LinkageError error) {
            logTickFailure(error);
            return false;
        }
    }

    /**
     * Applies every attachment living in the given level. Safe to call from the
     * riding manager after the physics world has advanced.
     *
     * @return number of entities moved
     */
    public static int tickAll(ServerLevel level) {
        if (level == null || ATTACHMENTS.isEmpty()) {
            return 0;
        }
        int moved = 0;
        for (Map.Entry<Entity, StructureAttachment> entry : ATTACHMENTS.entrySet()) {
            final Entity entity = entry.getKey();
            final StructureAttachment attachment = entry.getValue();
            if (entity.level() != level) {
                continue;
            }
            if (!isUsable(entity, attachment)) {
                ATTACHMENTS.remove(entity, attachment);
                continue;
            }
            try {
                if (apply(entity, attachment, false)) {
                    moved++;
                }
            } catch (RuntimeException | LinkageError error) {
                logTickFailure(error);
            }
        }
        return moved;
    }

    /**
     * Applies a caller-owned target (for example the riding manager's rider
     * handle) without touching the registry.
     *
     * @return {@code true} when the entity was moved
     */
    public static boolean apply(Entity entity, StructureAttachmentTarget target) {
        return apply(entity, target, false);
    }

    /**
     * Applies a caller-owned target.
     *
     * @param applyYaw also write the yaw extracted from the composed rotation;
     *                 opt-in because Create owns contraption yaw internally
     * @return {@code true} when the entity was moved
     */
    public static boolean apply(Entity entity, StructureAttachmentTarget target, boolean applyYaw) {
        if (entity == null || target == null || !target.isValid()) {
            return false;
        }
        final Transform3d world = target.worldTransform();
        if (!applyWorldTransform(entity, world, applyYaw)) {
            return false;
        }
        try {
            target.onPoseApplied(world);
        } catch (RuntimeException | LinkageError error) {
            logTickFailure(error);
        }
        return true;
    }

    /**
     * Writes a world-space pose to the entity. Passengers are translated by the
     * same delta so a fast-moving structure does not leave them a tick behind;
     * vanilla {@code positionRider} still corrects their exact seat offset on
     * the next entity tick.
     *
     * @return {@code false} for a removed entity or a non-finite pose
     */
    public static boolean applyWorldTransform(Entity entity, Transform3d worldTransform, boolean applyYaw) {
        if (entity == null || worldTransform == null || entity.isRemoved()) {
            return false;
        }
        final Vector3d position = worldTransform.position();
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            return false;
        }
        final double oldX = entity.getX();
        final double oldY = entity.getY();
        final double oldZ = entity.getZ();
        entity.setPos(position.x, position.y, position.z);
        final double dx = position.x - oldX;
        final double dy = position.y - oldY;
        final double dz = position.z - oldZ;
        if (dx != 0.0 || dy != 0.0 || dz != 0.0) {
            translatePassengers(entity, dx, dy, dz, 0);
        }
        if (applyYaw) {
            entity.setYRot(yawOf(worldTransform.rotation()));
        }
        return true;
    }

    /**
     * Captures the entity's current {@link Entity#position()} in structure
     * space. Rotation is not captured by default; pass {@code captureYaw} to
     * store the entity yaw as a local rotation as well.
     */
    public static Transform3d captureLocalTransform(Entity entity, PhysicsStructure structure) {
        return captureLocalTransform(entity, structure, false);
    }

    public static Transform3d captureLocalTransform(Entity entity, PhysicsStructure structure,
                                                    boolean captureYaw) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(structure, "structure");
        final Vector3d position = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        final Quaterniond rotation = captureYaw
                ? rotationOfYaw(entity.getYRot())
                : new Quaterniond();
        return localTransform(structure.pose(), position, rotation);
    }

    /**
     * Entity position converted into structure-local metres.
     */
    public static Vector3d localPosition(Entity entity, PhysicsStructure structure) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(structure, "structure");
        return structure.pose().inverseTransformPoint(
                new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
    }

    /**
     * Pure helper: {@code structurePose^-1 * worldPosition}.
     */
    public static Transform3d localTransform(Transform3d structurePose, Vector3dc worldPosition) {
        return localTransform(structurePose, worldPosition, new Quaterniond());
    }

    /**
     * Pure helper: {@code structurePose^-1 * worldPose}.
     */
    public static Transform3d localTransform(Transform3d structurePose, Vector3dc worldPosition,
                                             Quaterniondc worldRotation) {
        Objects.requireNonNull(structurePose, "structurePose");
        Objects.requireNonNull(worldPosition, "worldPosition");
        Objects.requireNonNull(worldRotation, "worldRotation");
        return structurePose.inverse().compose(Transform3d.of(worldPosition, worldRotation));
    }

    /**
     * Pure helper: {@code structurePose * localTransform}.
     */
    public static Transform3d worldTransform(Transform3d structurePose, Transform3d localTransform) {
        Objects.requireNonNull(structurePose, "structurePose");
        Objects.requireNonNull(localTransform, "localTransform");
        return structurePose.compose(localTransform);
    }

    /**
     * Converts a Minecraft entity yaw (degrees, clockwise around +Y) to a
     * right-handed JOML rotation around +Y.
     */
    public static Quaterniond rotationOfYaw(float yawDegrees) {
        return new Quaterniond().rotateY(-Math.toRadians(yawDegrees));
    }

    /**
     * Extracts a Minecraft entity yaw in {@code [-180, 180]} from a
     * right-handed rotation.
     */
    public static float yawOf(Quaterniondc rotation) {
        Objects.requireNonNull(rotation, "rotation");
        final Vector3d euler = new Quaterniond(rotation).getEulerAnglesYXZ(new Vector3d());
        float yaw = (float) -Math.toDegrees(euler.y);
        while (yaw > 180.0f) {
            yaw -= 360.0f;
        }
        while (yaw < -180.0f) {
            yaw += 360.0f;
        }
        return yaw;
    }

    private static boolean canAttach(Entity entity, PhysicsStructure structure) {
        return entity != null && structure != null && !entity.isRemoved() && !structure.closed();
    }

    private static boolean isUsable(Entity entity, StructureAttachment attachment) {
        return entity != null && !entity.isRemoved() && attachment.isValid();
    }

    private static boolean apply(Entity entity, StructureAttachment attachment, boolean applyYaw) {
        return apply(entity, (StructureAttachmentTarget) attachment, applyYaw);
    }

    private static void translatePassengers(Entity vehicle, double dx, double dy, double dz, int depth) {
        if (depth >= MAX_PASSENGER_DEPTH) {
            return;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            passenger.setPos(passenger.getX() + dx, passenger.getY() + dy, passenger.getZ() + dz);
            translatePassengers(passenger, dx, dy, dz, depth + 1);
        }
    }

    private static void onEndServerTick(MinecraftServer server) {
        if (ATTACHMENTS.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            tickAll(level);
        }
    }

    private static void logTickFailure(Throwable error) {
        if (!tickFailureLogged) {
            tickFailureLogged = true;
            Crythst.LOGGER.warn(
                    "Error while applying a Create contraption structure anchor; "
                            + "further anchor errors are logged at debug level",
                    error);
        } else {
            Crythst.LOGGER.debug("Error while applying a Create contraption structure anchor", error);
        }
    }

    /**
     * Convenience for callers that want to know whether a given entity is
     * already anchored.
     */
    public static boolean isAttached(Entity entity) {
        return entity != null && ATTACHMENTS.containsKey(entity);
    }

    /**
     * UUID of the attached entity, for persistence.
     */
    public static Optional<UUID> attachedEntityId(Entity entity) {
        return attachmentOf(entity).map(StructureAttachment::entityId);
    }
}
