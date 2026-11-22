package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the movement-integrated structure collision.
 *
 * <p>{@code Entity.move} calls this instead of vanilla {@code collide} only for
 * the actual movement call. It computes the structure-clear motion, asks
 * vanilla collision to apply world blocks to that motion, resolves a residual
 * corner overlap, and filters the entity velocity against every contact
 * normal. Vanilla then performs the movement, ramp/step handling, fall damage
 * and ground/collision flags exactly as before.</p>
 *
 * <p>No position is written here and no teleport packet is produced. Both the
 * server and the client run this same function against their own copy of the
 * structure poses, which removes the old post-tick authority split.</p>
 */
public final class EntityStructureCollision {
    /** Corrections from a stale client pose are capped by the source. */
    private static final double MAX_RESIDUAL_CORRECTION = 1.0;

    private EntityStructureCollision() {
    }

    /** Vanilla collision, passed in by the mixin to avoid recursion. */
    @FunctionalInterface
    public interface VanillaCollide {
        Vec3 collide(Vec3 motion);
    }

    /**
     * Resolves structure collision for one vanilla movement call.
     *
     * @param entity  entity being moved
     * @param motion  requested vanilla motion
     * @param vanilla vanilla collision function for the same motion
     * @return motion that both structure and vanilla collision allow
     */
    public static Vec3 resolveMovement(Entity entity, Vec3 motion, VanillaCollide vanilla) {
        final StructureCollisionSource source = eligible(entity)
                ? StructureCollisionSources.forEntity(entity)
                : null;
        if (source == null) {
            return vanilla.collide(motion);
        }

        final Aabb box = toAabb(entity.getBoundingBox());
        Vector3d wanted = new Vector3d(motion.x, motion.y, motion.z);
        // Vanilla sneak edge-protection only consults world blocks. On a
        // structure floor it therefore removes the whole horizontal step
        // because the structure is not a block in level.noCollision. Restore
        // the intended component only when the structure supports the target.
        if (entity instanceof Player player && player.isShiftKeyDown()
                && entity.onGround()) {
            final Vec3 intended = entity.getDeltaMovement();
            wanted = StructureMotionSolver.restoreSneakMotion(
                    box, wanted,
                    new Vector3d(intended.x, intended.y, intended.z),
                    entity.maxUpStep(), source);
        }
        // Use the same substep count for LocalPlayer and ServerPlayer so both
        // sides run an identical loop against their copy of the poses.
        final boolean playerSubsteps = entity instanceof Player;
        final StructureMotionSolver.Result structures = StructureMotionSolver.solve(
                new StructureMotionSolver.Input(
                        box,
                        wanted,
                        entity.maxUpStep(),
                        entity.onGround(),
                        entity.getDeltaMovement().y,
                        playerSubsteps
                ),
                source
        );

        if (!structures.touched()) {
            return vanilla.collide(toVec(wanted));
        }

        final Vec3 structureMotion = toVec(structures.motion());
        Vec3 allowed = vanilla.collide(structureMotion);
        final List<StructureMotionSolver.Contact> contacts =
                new ArrayList<>(structures.contacts());

        // Vanilla may have clipped the structure-clear motion (a world wall
        // next to a structure). Resolve only a shallow residual so a corner
        // cannot become a teleport.
        boolean appliedResidual = false;
        if (!allowed.equals(structureMotion)) {
            final Aabb moved = box.offset(new Vector3d(allowed.x, allowed.y, allowed.z));
            final StructureMotionSolver.Result residual = StructureMotionSolver.resolvePenetration(
                    moved, entity.onGround(), entity.getDeltaMovement().y, source);
            final Vector3d correction = residual.motion();
            if (residual.touched() && correction.length() <= MAX_RESIDUAL_CORRECTION) {
                allowed = allowed.add(correction.x, correction.y, correction.z);
                contacts.addAll(residual.contacts());
                appliedResidual = true;
            }
        }

        if (!contacts.isEmpty()) {
            final Vector3d filtered = StructureMotionSolver.filterVelocity(
                    new Vector3d(entity.getDeltaMovement().x,
                            entity.getDeltaMovement().y,
                            entity.getDeltaMovement().z),
                    contacts
            );
            entity.setDeltaMovement(toVec(filtered));
        }

        StructureCollisionTrace.record(source.side(), entity.getType().toString(),
                structures, motion, allowed, appliedResidual ? "residual" : "");
        return allowed;
    }

    /**
     * Whether this entity participates in structure collision. The one-way
     * rule needs no special case: the kernel only reads structures.
     */
    public static boolean eligible(Entity entity) {
        if (entity == null || entity.isRemoved() || !entity.isAlive()
                || entity.isSpectator() || entity.isPassenger() || entity.noPhysics
                || entity instanceof Display || entity instanceof HangingEntity
                || entity instanceof EnderDragonPart) {
            return false;
        }
        if (!StructureCollisionSettings.allows(entity)) {
            return false;
        }
        final CrythstConfig config = MinecraftPhysics.config();
        return config != null && config.enabled && config.entityPhysicsCollisionEnabled
                && MinecraftPhysics.available();
    }

    private static Vec3 toVec(Vector3d value) {
        return new Vec3(value.x, value.y, value.z);
    }

    private static Aabb toAabb(AABB box) {
        return new Aabb(
                new Vector3d(box.minX, box.minY, box.minZ),
                new Vector3d(box.maxX, box.maxY, box.maxZ)
        );
    }
}
