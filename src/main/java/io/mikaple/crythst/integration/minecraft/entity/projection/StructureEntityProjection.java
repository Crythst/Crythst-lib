package io.mikaple.crythst.integration.minecraft.entity.projection;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Set;

/**
 * Projects entities spawned by block logic in the auxiliary void level back
 * into the host {@link ServerLevel}.
 *
 * <p>A physics structure's blocks are copied into a reserved region of an
 * {@code EmptyPhysicsLevel} so vanilla can tick dispensers, droppers, TNT,
 * hoppers and block entities. Vanilla then spawns item entities, primed TNT,
 * arrows, minecarts and similar entities <em>inside that void level</em>.
 * Those entities are not part of the host world and would otherwise never be
 * visible or collectable.</p>
 *
 * <p>The mapping is rigid:</p>
 *
 * <pre>
 * localVoxel = voidPosition - region.localOrigin
 * world      = structure.pose() * (localVoxel * blockScale)
 * worldDir   = structure.pose().rotation * voidDirection
 * </pre>
 *
 * <p>{@link RegionAnchor#localOrigin} is the void-level coordinate of
 * structure-local voxel {@code (0,0,0)}. It already includes the volume's
 * bounds minimum, because {@code EmptyPhysicsLevel} copies
 * {@code volume.bounds().min()} to the region base. A rotated and/or scaled
 * structure therefore maps every entity exactly like a structure voxel.</p>
 *
 * <p>Projection uses vanilla's cross-dimension entity transfer
 * ({@link TeleportTransition}): the old entity is serialised, removed, a fresh
 * entity is created in the host level and the complete NBT is restored before
 * the projected position, motion and rotation are applied. This preserves the
 * item stack, pickup delay, fuse, owner/thrower UUIDs, passengers and other
 * type-specific data. Players are never projected (the void level has none),
 * and block-attached entities such as item frames and paintings are left in
 * the void level because their support block does not exist in the host
 * world.</p>
 */
public final class StructureEntityProjection {
    /**
     * Scoreboard tag for entities that must stay in the auxiliary level.
     * Mods can add it to a plot-only entity (for example a debug marker) to
     * opt out of projection. The tag is saved as normal entity NBT.
     */
    public static final String PLOT_ONLY_TAG = "crythst.plot_only";

    private static final double MIN_SCALE = 1.0e-9;

    private StructureEntityProjection() {
    }

    /**
     * Void-level origin of a structure's local voxel space.
     *
     * @param originX void-level X coordinate of structure-local voxel {@code 0}
     * @param originY void-level Y coordinate of structure-local voxel {@code 0}
     * @param originZ void-level Z coordinate of structure-local voxel {@code 0}
     */
    public record RegionAnchor(int originX, int originY, int originZ) {
        /**
         * Builds an anchor from an auxiliary region base and the bounds of the
         * volume copied into it.
         *
         * @param baseX        region base X in the void level
         * @param baseY        region base Y in the void level
         * @param baseZ        region base Z in the void level
         * @param volumeBounds bounds of the copied volume
         * @return the local origin in void-level coordinates
         */
        public static RegionAnchor ofRegion(int baseX, int baseY, int baseZ,
                                            IntBounds3 volumeBounds) {
            if (volumeBounds == null) {
                throw new NullPointerException("volumeBounds");
            }
            return new RegionAnchor(
                    baseX - volumeBounds.minX(),
                    baseY - volumeBounds.minY(),
                    baseZ - volumeBounds.minZ());
        }

        /** Converts a void-level point to fractional structure-local voxels. */
        public Vector3d toLocal(double voidX, double voidY, double voidZ) {
            return new Vector3d(voidX - originX, voidY - originY, voidZ - originZ);
        }
    }

    /**
     * Outcome of one projection attempt.
     */
    public enum Result {
        /** The entity was moved to the host level. */
        PROJECTED,
        /** The host level already contains this UUID; the void copy was removed. */
        DUPLICATE,
        /** Players are never projected. */
        SKIPPED_PLAYER,
        /** A passenger is moved together with its root vehicle. */
        SKIPPED_PASSENGER,
        /** Item frames, paintings and similar entities need a real support block. */
        SKIPPED_BLOCK_ATTACHED,
        /** Marked with {@link #PLOT_ONLY_TAG}. */
        SKIPPED_PLOT_ONLY,
        /** Already removed by vanilla before the projection pass ran. */
        SKIPPED_REMOVED,
        /** Vanilla could not create or add the target entity. */
        FAILED
    }

    /**
     * Aggregate diagnostics for one projection pass.
     */
    public record Batch(int projected, int duplicates, int skippedPlayers,
                        int skippedPassengers, int skippedBlockAttached,
                        int skippedPlotOnly, int skippedRemoved, int failed) {
        public static final Batch EMPTY =
                new Batch(0, 0, 0, 0, 0, 0, 0, 0);

        /** Adds one result to this batch. */
        public Batch plus(Result result) {
            if (result == null) {
                return this;
            }
            return switch (result) {
                case PROJECTED -> new Batch(projected + 1, duplicates,
                        skippedPlayers, skippedPassengers, skippedBlockAttached,
                        skippedPlotOnly, skippedRemoved, failed);
                case DUPLICATE -> new Batch(projected, duplicates + 1,
                        skippedPlayers, skippedPassengers, skippedBlockAttached,
                        skippedPlotOnly, skippedRemoved, failed);
                case SKIPPED_PLAYER -> new Batch(projected, duplicates,
                        skippedPlayers + 1, skippedPassengers, skippedBlockAttached,
                        skippedPlotOnly, skippedRemoved, failed);
                case SKIPPED_PASSENGER -> new Batch(projected, duplicates,
                        skippedPlayers, skippedPassengers + 1, skippedBlockAttached,
                        skippedPlotOnly, skippedRemoved, failed);
                case SKIPPED_BLOCK_ATTACHED -> new Batch(projected, duplicates,
                        skippedPlayers, skippedPassengers, skippedBlockAttached + 1,
                        skippedPlotOnly, skippedRemoved, failed);
                case SKIPPED_PLOT_ONLY -> new Batch(projected, duplicates,
                        skippedPlayers, skippedPassengers, skippedBlockAttached,
                        skippedPlotOnly + 1, skippedRemoved, failed);
                case SKIPPED_REMOVED -> new Batch(projected, duplicates,
                        skippedPlayers, skippedPassengers, skippedBlockAttached,
                        skippedPlotOnly, skippedRemoved + 1, failed);
                case FAILED -> new Batch(projected, duplicates,
                        skippedPlayers, skippedPassengers, skippedBlockAttached,
                        skippedPlotOnly, skippedRemoved, failed + 1);
            };
        }

        /** Total entities considered by this batch. */
        public int total() {
            return projected + duplicates + skippedPlayers + skippedPassengers
                    + skippedBlockAttached + skippedPlotOnly + skippedRemoved + failed;
        }

        /** One-line diagnostic summary. */
        public String summary() {
            return "projected=" + projected
                    + " duplicate=" + duplicates
                    + " skippedPlayer=" + skippedPlayers
                    + " skippedPassenger=" + skippedPassengers
                    + " skippedBlockAttached=" + skippedBlockAttached
                    + " skippedPlotOnly=" + skippedPlotOnly
                    + " skippedRemoved=" + skippedRemoved
                    + " failed=" + failed;
        }
    }

    /**
     * Maps a void-level position to world space.
     *
     * @param pose        current structure pose; {@code null} is treated as identity
     * @param blockScale  structure block scale
     * @param anchor      region local origin
     * @param voidPosition position in the auxiliary level
     * @return the projected world position
     */
    public static Vec3 projectPosition(Transform3d pose, double blockScale,
                                       RegionAnchor anchor, Vec3 voidPosition) {
        if (anchor == null) {
            throw new NullPointerException("anchor");
        }
        if (voidPosition == null) {
            throw new NullPointerException("voidPosition");
        }
        final double scale = positiveScale(blockScale);
        final Vector3d local = anchor.toLocal(
                voidPosition.x, voidPosition.y, voidPosition.z).mul(scale);
        final Vector3d world = pose == null
                ? local : pose.transformPoint(local);
        return new Vec3(world.x, world.y, world.z);
    }

    /**
     * Rotates a void-level direction (velocity, look or impulse) into the host
     * world. Translation and scale are deliberately not applied: Minecraft
     * velocities are metres per tick, and the requirement is only that the
     * structure's pose rotates them.
     *
     * @param pose          current structure pose; {@code null} is identity
     * @param voidDirection direction in the auxiliary level
     * @return the rotated world direction
     */
    public static Vec3 projectDirection(Transform3d pose, Vec3 voidDirection) {
        if (voidDirection == null) {
            throw new NullPointerException("voidDirection");
        }
        final Vector3d direction = new Vector3d(
                voidDirection.x, voidDirection.y, voidDirection.z);
        final Vector3d world = pose == null ? direction : pose.transformDirection(direction);
        return new Vec3(world.x, world.y, world.z);
    }

    /**
     * Rotates an entity's yaw/pitch into the host world by transforming its
     * look vector. A full 3D structure rotation is projected onto the closest
     * representable yaw/pitch pair.
     *
     * @param pose  current structure pose; {@code null} is identity
     * @param yaw   entity yaw in degrees
     * @param pitch entity pitch in degrees
     * @return projected Minecraft rotation ({@code x=pitch}, {@code y=yaw})
     */
    public static Vec2 projectRotation(Transform3d pose, float yaw, float pitch) {
        final Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        final Vec3 worldLook = projectDirection(pose, look);
        if (worldLook.lengthSqr() < 1.0e-20) {
            return new Vec2(pitch, yaw);
        }
        return worldLook.normalize().rotation();
    }

    /**
     * Projects one entity from the auxiliary level to {@code host}.
     *
     * <p>The caller must pass the anchor of the region that contains the
     * entity. This method never scans chunks and never loads a level.</p>
     *
     * @param host      destination level
     * @param structure structure whose pose maps the entity
     * @param anchor    region anchor containing the entity
     * @param entity    entity currently in the auxiliary level
     * @return the outcome; {@link Result#FAILED} means the entity was left in
     *         the auxiliary level
     */
    public static Result project(ServerLevel host, PhysicsStructure structure,
                                 RegionAnchor anchor, Entity entity) {
        if (host == null || structure == null || anchor == null || entity == null) {
            return Result.FAILED;
        }
        if (entity.isRemoved()) {
            return Result.SKIPPED_REMOVED;
        }
        if (entity instanceof Player) {
            return Result.SKIPPED_PLAYER;
        }
        if (entity.isPassenger()) {
            // The root vehicle is projected instead; vanilla moves its
            // passengers and recomputes their attachment points.
            return Result.SKIPPED_PASSENGER;
        }
        if (entity instanceof BlockAttachedEntity) {
            // Item frames, paintings and leash knots store an integer support
            // block position. That block belongs to the void plot, not to the
            // host world, so the entity would immediately pop off.
            return Result.SKIPPED_BLOCK_ATTACHED;
        }
        if (entity.entityTags().contains(PLOT_ONLY_TAG)) {
            return Result.SKIPPED_PLOT_ONLY;
        }
        if (host.getEntity(entity.getUUID()) != null) {
            // A previous pass already created the host copy but removing the
            // void copy failed. Drop the stale copy instead of duplicating it.
            entity.discard();
            return Result.DUPLICATE;
        }

        final Transform3d pose = structure.pose();
        final Vec3 position = projectPosition(pose, structure.blockScale(), anchor,
                entity.position());
        final Vec3 motion = projectDirection(pose, entity.getDeltaMovement());
        final Vec2 rotation = projectRotation(pose, entity.getYRot(), entity.getXRot());

        try {
            final TeleportTransition transition = new TeleportTransition(
                    host, position, motion, rotation.y, rotation.x,
                    Set.of(), TeleportTransition.DO_NOTHING);
            return entity.teleport(transition) != null
                    ? Result.PROJECTED : Result.FAILED;
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Could not project {} entity {} from the auxiliary void level to {}",
                    entity.getType(), entity.getUUID(), host.dimension().identifier(), t);
            return Result.FAILED;
        }
    }

    /**
     * Projects every entity in {@code entities}, accumulating diagnostics.
     * Entities for which {@link #project} returns {@link Result#FAILED} stay
     * in the auxiliary level; the caller may retry them later.
     */
    public static Batch projectAll(ServerLevel host, PhysicsStructure structure,
                                   RegionAnchor anchor, Iterable<? extends Entity> entities) {
        if (host == null || structure == null || anchor == null || entities == null) {
            return Batch.EMPTY;
        }
        Batch batch = Batch.EMPTY;
        for (Entity entity : entities) {
            batch = batch.plus(project(host, structure, anchor, entity));
        }
        return batch;
    }

    private static double positiveScale(double blockScale) {
        return blockScale > MIN_SCALE && Double.isFinite(blockScale)
                ? blockScale : 1.0;
    }
}
