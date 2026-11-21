package io.mikaple.crythst.api.entity;

import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Entity helpers for Crythst structures.
 *
 * <p>All methods that read a physics structure require a
 * {@link ServerLevel}; on a client-only level they return {@code null}, zero or
 * {@code false}. This is intentional: a remote client has no authoritative
 * physics world. Use {@link CrythstApi#structures(Level)} for the read-only
 * structure list that is available on both sides.</p>
 *
 * <p>Push/pop semantics: {@link #pushEntityLocal} stores the entity's position,
 * look direction and velocity in a structure-local frame. Nothing is moved at
 * push time. {@link #popEntityLocal} projects the stored frame through the
 * structure's <em>current</em> pose, adds the structure's local material
 * velocity and converts the result back to Minecraft's metres-per-tick
 * velocity. Frames are kept in a weak, per-entity stack so nested push/pop pairs
 * are safe. All methods run on the server thread.</p>
 */
public final class EntityStructureUtil {
    private static final Map<Entity, Deque<LocalFrame>> FRAMES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private EntityStructureUtil() {
    }

    /**
     * Finds the structure whose solid volume contains the entity's feet
     * position.
     *
     * @return a live structure, or {@code null} on the client or when the entity
     *         is not inside a structure
     */
    public static @Nullable PhysicsStructure getContaining(Level level, Entity entity) {
        if (entity == null) {
            return null;
        }
        return getContaining(level, entity.position());
    }

    /**
     * Finds the structure whose solid volume contains a world-space point.
     */
    public static @Nullable PhysicsStructure getContaining(Level level, Vec3 point) {
        if (point == null) {
            return null;
        }
        return getContaining(level, new Vector3d(point.x, point.y, point.z));
    }

    /**
     * Finds the structure whose solid volume contains a world-space point.
     */
    public static @Nullable PhysicsStructure getContaining(Level level, Vector3dc point) {
        if (!(level instanceof ServerLevel serverLevel) || point == null) {
            return null;
        }
        final LevelPhysics physics = MinecraftPhysics.getIfPresent(serverLevel);
        if (physics == null) {
            return null;
        }
        for (PhysicsStructure structure : physics.structures()) {
            if (structure.closed() || !structure.obb().contains(point)) {
                continue;
            }
            // The OBB also contains the hollow parts of an L-shape. Resolve the
            // voxel and require a solid cell so "containing" means the same
            // thing as "standing on/in the structure".
            final Vector3d voxel = structure.worldToVoxel(new Vector3d(), point);
            final int x = (int) Math.floor(voxel.x + 0.5);
            final int y = (int) Math.floor(voxel.y + 0.5);
            final int z = (int) Math.floor(voxel.z + 0.5);
            if (structure.volume().bounds().contains(x, y, z)
                    && structure.volume().isSolid(x, y, z)) {
                return structure;
            }
        }
        return null;
    }

    /**
     * Computes the structure's material velocity at a world-space point,
     * including angular velocity ({@code v + ω × r}).
     *
     * @param dest destination in {@code m/s}; zero when no structure contains
     *             the point
     * @return {@code dest}
     */
    public static Vector3d getVelocity(Level level, Vector3dc point, Vector3d dest) {
        if (dest == null) {
            throw new NullPointerException("dest");
        }
        final PhysicsStructure structure = getContaining(level, point);
        if (structure == null) {
            return dest.zero();
        }
        return dest.set(structure.velocityAt(point));
    }

    /**
     * Convenience overload using Minecraft's vector type.
     */
    public static Vector3d getVelocity(Level level, Vec3 point, Vector3d dest) {
        return getVelocity(level, new Vector3d(point.x, point.y, point.z), dest);
    }

    /**
     * Computes a structure's material velocity at a world-space point,
     * including angular velocity ({@code v + ω × r}).
     *
     * <p>Unlike {@link #getVelocity(Level, Vector3dc, Vector3d)}, this overload
     * takes an already-resolved structure, so it does not perform a containing
     * lookup and works even when the point is just outside the solid volume.
     * It must run on the server thread because it reads the live rigid
     * body.</p>
     *
     * @param structure structure providing the body velocity; must not be
     *                  {@code null}
     * @param point     world-space point in metres; must not be {@code null}
     * @param dest      destination in {@code m/s}
     * @return {@code dest}
     * @throws NullPointerException when an argument is {@code null}
     */
    public static Vector3d getVelocityAtPoint(PhysicsStructure structure, Vector3dc point,
                                              Vector3d dest) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (point == null) {
            throw new NullPointerException("point");
        }
        if (dest == null) {
            throw new NullPointerException("dest");
        }
        return dest.set(structure.velocityAt(point));
    }

    /**
     * Computes the squared distance between two points while respecting
     * structure-local frames.
     *
     * <p>When both points resolve to the same structure, both are transformed
     * into that structure's current local frame and compared there. Otherwise
     * the plain world-space distance is returned. A point that is not inside a
     * solid structure is already world-space, so no projection is needed.</p>
     *
     * <p>This is an approximation of Sable's
     * {@code distanceSquaredWithSubLevels}: Crythst structures live in world
     * space rather than in separate plot coordinates, and a rigid pose
     * preserves distances. Consequently the same-structure branch yields the
     * same numeric distance as the vanilla branch, but makes the chosen frame
     * explicit. It never returns a distance smaller than the true world-space
     * separation.</p>
     *
     * <p><strong>Side:</strong> if {@code level} is a client level, or it has no
     * physics world, or either point is outside a solid structure, this is
     * exactly {@link Vec3#distanceToSqr(Vec3)} and is safe on either side.
     * Structure lookup itself requires a server level.</p>
     *
     * @param level level used for structure lookup; may be {@code null}, which
     *              forces the world-space branch
     * @param a     first point in world metres; must not be {@code null}
     * @param b     second point in world metres; must not be {@code null}
     * @return squared distance in {@code m²}
     * @throws NullPointerException when {@code a} or {@code b} is {@code null}
     */
    public static double distanceSquaredWithStructures(Level level, Vec3 a, Vec3 b) {
        if (a == null) {
            throw new NullPointerException("a");
        }
        if (b == null) {
            throw new NullPointerException("b");
        }
        final PhysicsStructure first = getContaining(level, a);
        if (first != null && first == getContaining(level, b)) {
            return distanceSquaredInFrame(first.pose(), a, b);
        }
        return a.distanceToSqr(b);
    }

    /**
     * Returns the entity's eye position interpolated between the previous and
     * current tick.
     *
     * <p>Minecraft keeps the previous tick position in {@code xo/yo/zo} and the
     * current position in {@code getX/getY/getZ}. This helper lerps all three
     * axes and adds the entity's current eye height in metres. Unlike the
     * client-only Sable helper, it does not apply a structure render pose: a
     * common API must stay loadable on a dedicated server, where no client
     * structure source exists. Use the structure pose at the call site if
     * structure-local interpolation is required.</p>
     *
     * <p><strong>Side:</strong> common; safe on both sides. Values of
     * {@code partialTick} outside {@code [0, 1]} extrapolate.</p>
     *
     * @param entity      entity to sample; must not be {@code null}
     * @param partialTick fraction between the previous and current tick
     * @return a new world-space eye position in metres
     * @throws NullPointerException when {@code entity} is {@code null}
     */
    public static Vec3 getEyePositionInterpolated(Entity entity, float partialTick) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        return interpolateEyePosition(entity.xo, entity.yo, entity.zo,
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getEyeHeight(), partialTick);
    }

    /**
     * Stores an entity's current world state in a structure-local frame.
     *
     * @param structure structure providing the frame
     * @param entity    entity to project
     * @param anchor    unrotated offset in metres added before the inverse
     *                  transform and subtracted after the forward transform;
     *                  use {@link Vec3#ZERO} for the entity origin
     */
    public static void pushEntityLocal(PhysicsStructure structure, Entity entity, Vec3 anchor) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        final Vec3 actualAnchor = anchor == null ? Vec3.ZERO : anchor;
        final Transform3d pose = structure.pose();
        final Vector3d anchorVector = new Vector3d(actualAnchor.x, actualAnchor.y, actualAnchor.z);

        final Vector3d worldPosition = new Vector3d(entity.getX(), entity.getY(), entity.getZ())
                .add(anchorVector);
        final Vector3d localPosition = pose.inverseTransformPoint(worldPosition).sub(anchorVector);
        final Vec3 look = entity.getLookAngle();
        final Vec3 delta = entity.getDeltaMovement();
        final Vector3d localLook = pose.inverseTransformDirection(
                new Vector3d(look.x, look.y, look.z));
        final Vector3d localVelocity = pose.inverseTransformDirection(
                new Vector3d(delta.x, delta.y, delta.z).mul(20.0));
        final LocalFrame frame = new LocalFrame(localPosition, localLook, localVelocity,
                new Vector3d(anchorVector));
        FRAMES.computeIfAbsent(entity, ignored -> new ArrayDeque<>()).push(frame);
    }

    /**
     * Convenience overload with no anchor offset.
     */
    public static void pushEntityLocal(PhysicsStructure structure, Entity entity) {
        pushEntityLocal(structure, entity, Vec3.ZERO);
    }

    /**
     * Projects the last pushed frame back into world space using the structure's
     * current pose and velocity.
     *
     * @return {@code false} when no frame was pushed for this entity
     */
    public static boolean popEntityLocal(PhysicsStructure structure, Entity entity, Vec3 anchor) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        final LocalFrame frame = popFrame(entity);
        if (frame == null) {
            return false;
        }
        final Transform3d pose = structure.pose();
        final Vector3d anchorVector = anchor == null
                ? frame.anchor : new Vector3d(anchor.x, anchor.y, anchor.z);
        final Vector3d worldPosition = pose.transformPoint(
                new Vector3d(frame.localPosition).add(anchorVector)).sub(anchorVector);
        final Vector3d worldLook = pose.transformDirection(frame.localLook);
        final Vec2 rotation = new Vec3(worldLook.x, worldLook.y, worldLook.z).rotation();

        entity.snapTo(worldPosition.x, worldPosition.y, worldPosition.z,
                rotation.y, rotation.x);

        final Vector3d structureVelocity = structure.velocityAt(worldPosition);
        final Vector3d worldVelocity = pose.transformDirection(frame.localVelocity)
                .add(structureVelocity)
                .mul(1.0 / 20.0);
        entity.setDeltaMovement(worldVelocity.x, worldVelocity.y, worldVelocity.z);
        return true;
    }

    /**
     * Convenience overload with no anchor offset.
     */
    public static boolean popEntityLocal(PhysicsStructure structure, Entity entity) {
        return popEntityLocal(structure, entity, Vec3.ZERO);
    }

    /**
     * Projects an entity's current state out of a structure-local frame and
     * into world space, using the structure's current pose.
     *
     * <p>This is the forward counterpart of
     * {@link #pushEntityLocal(PhysicsStructure, Entity, Vec3)}: it reads the
     * entity's current local position, look direction and delta movement and
     * rewrites all three in world space. The anchor follows the same convention
     * as push: it is added before the forward transform and subtracted after
     * it.</p>
     *
     * <p>Unlike {@link #popEntityLocal(PhysicsStructure, Entity, Vec3)}, this
     * method neither consumes a cached frame nor adds the structure's material
     * velocity. The local delta movement is only rotated, so a value in
     * metres per tick stays in metres per tick. For a frame captured by
     * {@link #pushEntityLocal(PhysicsStructure, Entity, Vec3)} around a
     * stationary structure, the position mapping is its exact inverse.</p>
     *
     * <p><strong>Side:</strong> server thread only, because it reads the live
     * structure pose.</p>
     *
     * @param structure structure that defines the local frame; must not be
     *                  {@code null}
     * @param entity    entity whose current state is expressed in the local
     *                  frame; must not be {@code null}
     * @param anchor    unrotated offset in metres added before the forward
     *                  transform and subtracted after it; use
     *                  {@link Vec3#ZERO} for the entity origin
     * @throws NullPointerException when {@code structure} or {@code entity} is
     *                              {@code null}
     */
    public static void projectOutOfStructure(PhysicsStructure structure, Entity entity,
                                             Vec3 anchor) {
        if (structure == null) {
            throw new NullPointerException("structure");
        }
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        final Vec3 actualAnchor = anchor == null ? Vec3.ZERO : anchor;
        final Transform3d pose = structure.pose();
        final Vector3d anchorVector = new Vector3d(actualAnchor.x, actualAnchor.y, actualAnchor.z);

        final Vector3d localPosition = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        final Vector3d worldPosition = projectToWorld(
                pose, localPosition, anchorVector, new Vector3d());

        final Vec3 look = entity.getLookAngle();
        final Vector3d worldLook = pose.transformDirection(
                new Vector3d(look.x, look.y, look.z));
        final Vec2 rotation = new Vec3(worldLook.x, worldLook.y, worldLook.z).rotation();
        entity.snapTo(worldPosition.x, worldPosition.y, worldPosition.z,
                rotation.y, rotation.x);

        final Vec3 delta = entity.getDeltaMovement();
        final Vector3d worldVelocity = pose.transformDirection(
                new Vector3d(delta.x, delta.y, delta.z));
        entity.setDeltaMovement(worldVelocity.x, worldVelocity.y, worldVelocity.z);
    }

    /**
     * Convenience overload with no anchor offset.
     */
    public static void projectOutOfStructure(PhysicsStructure structure, Entity entity) {
        projectOutOfStructure(structure, entity, Vec3.ZERO);
    }

    /**
     * Discards all stored frames for an entity. Use when an entity is removed or
     * teleported by other code.
     */
    public static void clearLocalFrames(Entity entity) {
        if (entity != null) {
            FRAMES.remove(entity);
        }
    }

    /**
     * Convenience overload that finds the structure at the entity's position.
     *
     * @return {@code false} when the entity is not inside a structure
     */
    public static boolean pushEntityLocal(Level level, Entity entity) {
        final PhysicsStructure structure = getContaining(level, entity);
        if (structure == null) {
            return false;
        }
        pushEntityLocal(structure, entity);
        return true;
    }

    static double distanceSquaredInFrame(Transform3d pose, Vec3 a, Vec3 b) {
        final Vector3d localA = pose.inverseTransformPoint(
                new Vector3d(a.x, a.y, a.z));
        final Vector3d localB = pose.inverseTransformPoint(
                new Vector3d(b.x, b.y, b.z));
        return localA.distanceSquared(localB);
    }

    static Vec3 interpolateEyePosition(double previousX, double previousY, double previousZ,
                                       double currentX, double currentY, double currentZ,
                                       double eyeHeight, float partialTick) {
        final double x = previousX + (currentX - previousX) * partialTick;
        final double y = previousY + (currentY - previousY) * partialTick + eyeHeight;
        final double z = previousZ + (currentZ - previousZ) * partialTick;
        return new Vec3(x, y, z);
    }

    private static @Nullable LocalFrame popFrame(Entity entity) {
        final Deque<LocalFrame> stack = FRAMES.get(entity);
        if (stack == null) {
            return null;
        }
        synchronized (FRAMES) {
            final LocalFrame frame = stack.poll();
            if (stack.isEmpty()) {
                FRAMES.remove(entity);
            }
            return frame;
        }
    }

    static Vector3d projectToLocal(Transform3d pose, Vector3dc worldPosition,
                                   Vector3dc anchor, Vector3d dest) {
        final Vector3d world = new Vector3d(worldPosition).add(anchor);
        return pose.inverseTransformPoint(world).sub(anchor, dest);
    }

    static Vector3d projectToWorld(Transform3d pose, Vector3dc localPosition,
                                   Vector3dc anchor, Vector3d dest) {
        final Vector3d local = new Vector3d(localPosition).add(anchor);
        return pose.transformPoint(local).sub(anchor, dest);
    }

    private record LocalFrame(Vector3d localPosition, Vector3d localLook,
                              Vector3d localVelocity, Vector3d anchor) {
    }
}
