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
