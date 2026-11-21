package io.mikaple.crythst.integration.minecraft.interaction.client;

import io.mikaple.crythst.integration.fabric.client.ClientPhysicsView;
import io.mikaple.crythst.integration.minecraft.PlayerGravity;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureRenderState;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Client-side raycast against the render snapshots published by
 * {@link ClientPhysicsView}.
 *
 * <p>This is only used to decide whether a left click should be intercepted and
 * sent to the server. It never mutates physics state. In singleplayer the
 * integrated server publishes structure snapshots; in multiplayer the list is
 * empty, so vanilla behaviour is untouched.</p>
 */
public final class ClientStructureRay {
    private static final double EPSILON = 1.0e-9;

    private ClientStructureRay() {
    }

    /**
     * @param structure structure that was hit
     * @param distance  world-space distance from the eye to the hit
     */
    public record Hit(PhysicsStructure structure, double distance) {
    }

    /**
     * @return the nearest structure hit within {@code reach}, or {@code null}
     */
    public static Hit raycast(LocalPlayer player, double reach) {
        if (player == null || reach <= 0.0) {
            return null;
        }
        final Vec3 eye = player.getEyePosition();
        final Vector3d direction = PlayerGravity.lookDirection(player);
        final Vector3d origin = new Vector3d(eye.x, eye.y, eye.z);
        if (direction.lengthSquared() < EPSILON) {
            return null;
        }
        direction.normalize();

        final double alpha = Minecraft.getInstance().getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);
        PhysicsStructure bestStructure = null;
        double bestDistance = reach;
        for (PhysicsStructure structure : ClientPhysicsView.structures()) {
            if (structure.closed()) {
                continue;
            }
            final StructureRenderState state = structure.renderState(alpha);
            final double distance = raycast(state, origin, direction, bestDistance);
            if (distance >= 0.0 && distance < bestDistance) {
                bestDistance = distance;
                bestStructure = structure;
            }
        }
        return bestStructure == null ? null : new Hit(bestStructure, bestDistance);
    }

    /**
     * @return the distance to the nearest collider box, or {@code -1} on a miss
     */
    private static double raycast(StructureRenderState state, Vector3d origin,
                                  Vector3d direction, double maxDistance) {
        if (state.collider().boxes().isEmpty()) {
            return -1.0;
        }
        final Transform3d pose = state.pose();
        final Vector3d centerOfMass = state.centerOfMassOffset();
        final Transform3d body = Transform3d.of(
                new Vector3d(pose.position()).add(new Vector3d(centerOfMass).rotate(pose.rotation())),
                pose.rotation());
        final Vector3d localOrigin = body.inverseTransformPoint(origin);
        final Vector3d localDirection = body.inverseTransformDirection(direction);
        final double scale = state.blockScale();

        double best = maxDistance;
        boolean hit = false;
        for (VoxelBox box : state.collider().boxes()) {
            final double minX = box.minX() * scale - centerOfMass.x;
            final double minY = box.minY() * scale - centerOfMass.y;
            final double minZ = box.minZ() * scale - centerOfMass.z;
            final double maxX = (box.minX() + box.sizeX()) * scale - centerOfMass.x;
            final double maxY = (box.minY() + box.sizeY()) * scale - centerOfMass.y;
            final double maxZ = (box.minZ() + box.sizeZ()) * scale - centerOfMass.z;
            final double distance = rayBox(localOrigin, localDirection,
                    minX, minY, minZ, maxX, maxY, maxZ, best);
            if (distance >= 0.0 && distance < best) {
                best = distance;
                hit = true;
            }
        }
        return hit ? best : -1.0;
    }

    /**
     * Slab test in body-local metres. Distances are preserved because the
     * transform is rigid and the direction is normalized.
     */
    private static double rayBox(Vector3d origin, Vector3d direction,
                                 double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ,
                                 double maxDistance) {
        double tMin = 0.0;
        double tMax = maxDistance;

        if (Math.abs(direction.x) < EPSILON) {
            if (origin.x < minX || origin.x > maxX) {
                return -1.0;
            }
        } else {
            double t1 = (minX - origin.x) / direction.x;
            double t2 = (maxX - origin.x) / direction.x;
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }

        if (Math.abs(direction.y) < EPSILON) {
            if (origin.y < minY || origin.y > maxY) {
                return -1.0;
            }
        } else {
            double t1 = (minY - origin.y) / direction.y;
            double t2 = (maxY - origin.y) / direction.y;
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }

        if (Math.abs(direction.z) < EPSILON) {
            if (origin.z < minZ || origin.z > maxZ) {
                return -1.0;
            }
        } else {
            double t1 = (minZ - origin.z) / direction.z;
            double t2 = (maxZ - origin.z) / direction.z;
            if (t1 > t2) {
                final double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }

        return tMin;
    }
}
