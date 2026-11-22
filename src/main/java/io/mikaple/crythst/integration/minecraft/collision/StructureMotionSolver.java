package io.mikaple.crythst.integration.minecraft.collision;

import io.mikaple.crythst.integration.minecraft.entity.EntityStructureSolver;
import io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureRidingMath;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Movement-integrated structure collision.
 *
 * <p>Vanilla moves an entity by first asking {@code Entity.collide} how much of
 * the requested motion world blocks allow. This solver runs in that same call:
 * it walks the requested motion in small substeps and removes only the part
 * that would enter a physics structure. The returned motion is then passed to
 * vanilla collision, so sliding, step height, fall handling and the
 * {@code onGround}/{@code verticalCollision} flags keep their vanilla
 * behaviour.</p>
 *
 * <p>There is intentionally no post-tick correction here. Every substep
 * resolves the current overlap and continues from the resolved position, which
 * makes the result order-independent and prevents a surface from being applied
 * twice.</p>
 *
 * <p>The solver is pure geometry: it takes a {@link StructureCollisionSource}
 * (merged boxes plus exact fractional parts) and returns a translation. It
 * never mutates a structure, applies an impulse or teleports an entity.</p>
 */
public final class StructureMotionSolver {
    /** Narrow-phase budget per structure, per substep, per pass. */
    public static final int MAX_BOXES_PER_STRUCTURE = 256;
    /** Iterations used when one merged box pushes into a neighbouring one. */
    public static final int MAX_PASSES = 3;
    /** Overlaps at or below this are resting contact and are not corrected. */
    public static final double CONTACT_SLOP = 2.0e-3;
    /** Only the nearest structures are resolved against one entity. */
    public static final int MAX_STRUCTURES = 8;
    /** Substep length: 1/64 of a block. */
    private static final double SUBSTEP_LENGTH = 0.25 / 16.0;
    /** Upper bound on substeps so a fast entity cannot explode the cost. */
    public static final int MAX_SUBSTEPS = 10;
    /** Players use a fixed substep count for client/server parity. */
    public static final int PLAYER_SUBSTEPS = 8;
    /** A contact normal above this is a floor/support normal. */
    private static final double VERTICAL_DOT = 0.55;
    /** At most this many contact normals are kept for velocity projection. */
    private static final int MAX_CONTACTS = 16;

    private StructureMotionSolver() {
    }

    /** One resolved surface contact. */
    public record Contact(long bodyId, Vector3d normal, double depth) {
        public Contact {
            normal = new Vector3d(normal);
        }

        @Override
        public Vector3d normal() {
            return new Vector3d(normal);
        }
    }

    /**
     * The motion the entity may actually perform.
     *
     * @param motion             allowed displacement, including any step-up
     * @param contacts           surfaced normals used to filter velocity
     * @param horizontalCollision whether a non-floor surface was hit
     * @param verticalCollision   whether any surface was hit
     * @param verticalCollisionBelow whether a floor/support was hit
     * @param support             whether the entity is supported by a structure
     * @param substeps            substeps used by this solve
     * @param penetration         deepest overlap seen before correction
     * @param stepped             whether a structure step-up was applied
     */
    public record Result(Vector3d motion, List<Contact> contacts,
                         boolean horizontalCollision, boolean verticalCollision,
                         boolean verticalCollisionBelow, boolean support,
                         int substeps, double penetration, boolean stepped) {
        public Result {
            motion = new Vector3d(motion);
            contacts = copyContacts(contacts);
        }

        @Override
        public Vector3d motion() {
            return new Vector3d(motion);
        }

        @Override
        public List<Contact> contacts() {
            return copyContacts(contacts);
        }

        public boolean touched() {
            return !contacts.isEmpty();
        }

        public String bodyIds() {
            if (contacts.isEmpty()) {
                return "-";
            }
            final StringBuilder ids = new StringBuilder();
            final java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
            for (Contact contact : contacts) {
                if (seen.add(contact.bodyId())) {
                    if (ids.length() > 0) {
                        ids.append(',');
                    }
                    ids.append(contact.bodyId());
                }
            }
            return ids.toString();
        }

        private static List<Contact> copyContacts(List<Contact> source) {
            final List<Contact> result = new ArrayList<>(source.size());
            for (Contact contact : source) {
                result.add(new Contact(contact.bodyId(), contact.normal(), contact.depth()));
            }
            return List.copyOf(result);
        }
    }

    /**
     * Everything the solver needs from the entity, independent of Minecraft.
     *
     * @param box           entity AABB before the motion
     * @param motion        requested displacement for this tick
     * @param stepHeight    vanilla step height ({@code 0} disables step-up)
     * @param onGround      whether vanilla considers the entity grounded
     * @param verticalSpeed entity delta-movement Y, used to widen the landing
     *                      support budget
     * @param playerSubsteps use the fixed player substep count so the client
     *                      and server run the same loop for the same player
     */
    public record Input(Aabb box, Vector3d motion, double stepHeight,
                        boolean onGround, double verticalSpeed, boolean playerSubsteps) {
        public Input {
            motion = new Vector3d(motion);
        }

        @Override
        public Vector3d motion() {
            return new Vector3d(motion);
        }
    }

    /**
     * Resolves one requested motion against every structure in {@code source}.
     */
    public static Result solve(Input input, StructureCollisionSource source) {
        final Vector3d motion = input.motion();
        final Aabb swept = input.box().union(input.box().offset(motion)).expand(1.0);
        final List<StructureCollisionBox> candidates = candidates(source, swept, input.box().center());
        if (candidates.isEmpty()) {
            return new Result(motion, List.of(), false, false, false, false, 1, 0.0, false);
        }

        final int substeps = substeps(motion, input.playerSubsteps());
        final Vector3d step = new Vector3d(motion).div(substeps);
        // The post-tick solver used a generous 0.6 m support budget to stop a
        // landing from being shoved off an edge. Inside movement that would
        // let even a zero-step-height mob climb a 0.5 m wall, so the budget is
        // limited to roughly one substep of fall. True step-up is handled
        // explicitly below with the entity's vanilla step height.
        final double supportLimit = Math.min(0.2,
                0.02 + Math.abs(input.verticalSpeed()) / substeps);
        final EntityStructureSolver.Policy policy = new EntityStructureSolver.Policy(
                0.0,
                MAX_BOXES_PER_STRUCTURE,
                CONTACT_SLOP,
                input.onGround() || input.verticalSpeed() <= 0.0,
                supportLimit
        );

        final Vector3d allowed = new Vector3d();
        final Vector3d correction = new Vector3d();
        final List<Contact> contacts = new ArrayList<>(4);
        Aabb candidate = input.box();
        boolean horizontal = false;
        boolean vertical = false;
        boolean below = false;
        boolean support = false;
        boolean stepped = false;
        double deepest = 0.0;

        for (int substep = 0; substep < substeps; substep++) {
            final Vector3d previousCenter = candidate.center();
            candidate = candidate.offset(step);
            allowed.add(step);

            for (StructureCollisionBox structure : candidates) {
                for (int pass = 0; pass < MAX_PASSES; pass++) {
                    final EntityStructureSolver.Hit hit = EntityStructureSolver.resolve(
                            candidate, previousCenter, structure.pose(), structure.boxes(),
                            structure.parts(), structure.blockScale(), policy);
                    if (hit == null || hit.penetration() <= CONTACT_SLOP) {
                        break;
                    }

                    final boolean floor = hit.normal().y > VERTICAL_DOT;
                    final boolean ceiling = hit.normal().y < -VERTICAL_DOT;
                    final double horizontalMotion = Math.hypot(step.x, step.z);
                    final boolean movingInto = horizontalMotion > 1.0e-6
                            && hit.normal().x * step.x + hit.normal().z * step.z < -1.0e-9;
                    if (!floor && !ceiling && !stepped && movingInto
                            && Math.abs(hit.normal().y) <= 0.5
                            && input.onGround() && input.stepHeight() > 0.01) {
                        final Aabb lifted = candidate.offset(
                                new Vector3d(0.0, input.stepHeight(), 0.0));
                        final EntityStructureSolver.Hit liftHit = EntityStructureSolver.resolve(
                                lifted, candidate.center(), structure.pose(), structure.boxes(),
                                structure.parts(), structure.blockScale(), policy);
                        if (liftHit == null || liftHit.penetration() <= CONTACT_SLOP) {
                            // Do not step into empty space: there must be a
                            // floor somewhere within the step height below the
                            // lifted box. Vanilla auto-step only happens when
                            // the player can actually stand on the target.
                            final Aabb probe = lifted.offset(
                                    new Vector3d(0.0, -(input.stepHeight() + 0.01), 0.0));
                            final EntityStructureSolver.Hit floorHit = EntityStructureSolver.resolve(
                                    probe, lifted.center(), structure.pose(), structure.boxes(),
                                    structure.parts(), structure.blockScale(), policy);
                            if (floorHit != null && floorHit.normal().y > VERTICAL_DOT) {
                                // Step onto the obstacle instead of stopping at its side.
                                candidate = lifted;
                                allowed.y += input.stepHeight();
                                stepped = true;
                                support = true;
                                below = true;
                                vertical = true;
                                break;
                            }
                        }
                    }

                    candidate = candidate.offset(hit.offset());
                    allowed.add(hit.offset());
                    correction.add(hit.offset());
                    deepest = Math.max(deepest, hit.penetration());
                    addContact(contacts, structure.bodyId(), hit.normal(), hit.penetration());

                    vertical = true;
                    if (floor) {
                        support = true;
                        below = true;
                    } else if (!ceiling) {
                        horizontal = true;
                    }
                }
            }
        }

        final double correctionLength = correction.length();
        final double maxCorrection = source.maxCorrection();
        if (correctionLength > maxCorrection && correctionLength > 0.0) {
            // A stale client pose (or a structure that teleported) must not
            // fling the entity. Keep the step-up part intact, which is a
            // deliberate movement rather than a de-penetration offset.
            final Vector3d clamped = new Vector3d(correction)
                    .mul(maxCorrection / correctionLength);
            allowed.sub(correction).add(clamped);
        }

        return new Result(allowed, contacts, horizontal, vertical, below, support,
                substeps, deepest, stepped);
    }

    /**
     * Resolves a residual overlap after vanilla collision has clipped the
     * structure-clear motion. Used to handle a corner where a world block
     * pushes the entity back into a structure.
     */
    public static Result resolvePenetration(Aabb box, boolean onGround,
                                            double verticalSpeed,
                                            StructureCollisionSource source) {
        return solve(new Input(box, new Vector3d(), 0.0, onGround, verticalSpeed, false), source);
    }

    /**
     * Removes the velocity component that points into every contact surface.
     * Tangential motion is preserved, which is what lets an entity slide along
     * a wall and jump away from a floor.
     */
    public static Vector3d filterVelocity(Vector3dc velocity, List<Contact> contacts) {
        final Vector3d result = new Vector3d(velocity);
        for (Contact contact : contacts) {
            final Vector3dc normal = contact.normal();
            final double into = result.dot(normal);
            if (into < 0.0) {
                result.fma(-into, normal);
            }
        }
        return result;
    }

    /**
     * Restores the horizontal part of a player's intended motion when vanilla
     * sneak edge-protection removed it because it cannot see a structure.
     *
     * <p>{@code Player.maybeBackOffFromEdge} asks {@code level.noCollision}
     * whether the space under the next position is empty. Physics structures
     * are not vanilla blocks, so above a structure floor that call reports a
     * fall and a crouched player's whole horizontal step is reduced to zero.
     * When the structure really does support the target footprint, the
     * original step is safe and must be kept; otherwise (a real structure edge)
     * vanilla's truncation remains in force.</p>
     *
     * @param box          entity box at the start of the move, after any sneak
     *                     height change
     * @param clippedMotion motion after vanilla edge protection
     * @param intendedMotion entity delta movement before edge protection
     * @param maxDownStep  vanilla max up-step used as the support probe depth
     * @param source       structure collision source, or {@code null}
     * @return the motion the entity should use for structure collision
     */
    public static Vector3d restoreSneakMotion(Aabb box, Vector3dc clippedMotion,
                                              Vector3dc intendedMotion, double maxDownStep,
                                              StructureCollisionSource source) {
        final Vector3d clipped = new Vector3d(clippedMotion);
        if (box == null || intendedMotion == null || source == null) {
            return clipped;
        }
        final double intendedX = intendedMotion.x();
        final double intendedZ = intendedMotion.z();
        if (intendedX * intendedX + intendedZ * intendedZ < 1.0e-12) {
            return clipped;
        }

        // Never enlarge motion along an axis; only put back a component that
        // vanilla shortened while keeping the same direction.
        if (Math.abs(clipped.x) > Math.abs(intendedX) + 1.0e-9
                || Math.abs(clipped.z) > Math.abs(intendedZ) + 1.0e-9) {
            return clipped;
        }
        if (Math.signum(intendedX) * Math.signum(clipped.x) < 0.0
                || Math.signum(intendedZ) * Math.signum(clipped.z) < 0.0) {
            return clipped;
        }

        final double restoredX = Math.abs(intendedX) > Math.abs(clipped.x) + 1.0e-9
                ? intendedX : clipped.x;
        final double restoredZ = Math.abs(intendedZ) > Math.abs(clipped.z) + 1.0e-9
                ? intendedZ : clipped.z;
        if (restoredX == clipped.x && restoredZ == clipped.z) {
            return clipped;
        }
        if (!hasSupportAt(box, restoredX, restoredZ, maxDownStep, source)) {
            return clipped;
        }
        return new Vector3d(restoredX, clipped.y, restoredZ);
    }

    /**
     * Whether moving the entity footprint by {@code (deltaX, deltaZ)} leaves a
     * structure top no more than {@code maxDown} below the current feet.
     * Mirrors vanilla's "is there ground under the next position" edge check,
     * but reads structure colliders instead of world blocks.
     */
    public static boolean hasSupportAt(Aabb box, double deltaX, double deltaZ,
                                       double maxDown, StructureCollisionSource source) {
        if (box == null || source == null || !(maxDown > 0.0)) {
            return false;
        }
        final Vector3d size = box.size();
        final double halfX = size.x * 0.5;
        final double halfZ = size.z * 0.5;
        final Vector3d center = box.center();
        final Vector3d feet = new Vector3d(
                center.x + deltaX, box.min().y, center.z + deltaZ);
        final Aabb query = box.offset(new Vector3d(deltaX, -maxDown, deltaZ)).expand(0.02);
        final List<StructureCollisionBox> candidates = source.candidates(query);
        for (StructureCollisionBox structure : candidates) {
            if (structure.isEmpty()) {
                continue;
            }
            if (EntityStructureRidingMath.findSupport(
                    structure.pose(), structure.boxes(), structure.parts(), structure.blockScale(),
                    feet, halfX, halfZ, maxDown, MAX_BOXES_PER_STRUCTURE) != null) {
                return true;
            }
        }
        return false;
    }

    /** Substep count used for one motion. Exposed for tests and diagnostics. */
    public static int substeps(Vector3dc motion, boolean playerSubsteps) {
        if (motion.lengthSquared() < 1.0e-12) {
            return 1;
        }
        if (playerSubsteps) {
            return PLAYER_SUBSTEPS;
        }
        final int counted = (int) (motion.length() / SUBSTEP_LENGTH);
        return Math.clamp(counted, 1, MAX_SUBSTEPS);
    }

    private static List<StructureCollisionBox> candidates(StructureCollisionSource source,
                                                          Aabb query, Vector3d entityCenter) {
        final List<StructureCollisionBox> all = source.candidates(query);
        if (all.isEmpty()) {
            return List.of();
        }
        if (all.size() <= MAX_STRUCTURES) {
            return all;
        }
        final List<StructureCollisionBox> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(
                box -> box.worldBounds().center().distanceSquared(entityCenter)));
        return List.copyOf(sorted.subList(0, MAX_STRUCTURES));
    }

    private static void addContact(List<Contact> contacts, long bodyId,
                                   Vector3dc normal, double depth) {
        if (contacts.size() >= MAX_CONTACTS) {
            return;
        }
        for (Contact existing : contacts) {
            if (existing.normal().dot(normal) > 0.9995) {
                return;
            }
        }
        contacts.add(new Contact(bodyId, new Vector3d(normal).normalize(), depth));
    }
}
