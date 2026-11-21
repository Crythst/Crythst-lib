package io.mikaple.crythst.integration.minecraft.buoyancy;

import io.mikaple.crythst.config.CrythstConfig;
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.shape.PhysicsShape;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.voxel.VoxelBox;
import io.mikaple.crythst.physics.voxel.VoxelColliderData;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.StepListener;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies water buoyancy to dynamic physics structures and loose bodies.
 *
 * <p>The manager runs on {@link ServerTickEvents#START_SERVER_TICK}. On each
 * server tick it makes sure every loaded {@link LevelPhysics} has a
 * {@link StepListener}, so lift is written immediately before each individual
 * fixed step. Applying a force only once per server tick would be incorrect:
 * Bullet clears accumulated forces at the end of every step and a 20 TPS server
 * normally runs about three 1/60 s steps per tick. Register it once from the
 * common Fabric initialiser:</p>
 *
 * <pre>{@code
 * BuoyancyManager.register();
 * }</pre>
 *
 * <p>Because the force is applied by the step listener, manual
 * {@link LevelPhysics#step(int)} calls also receive buoyancy, exactly once per
 * fixed step. Do not also call {@link #apply(LevelPhysics)} in that case unless
 * you intentionally want an extra force for the next step.</p>
 *
 * <p>All state access occurs on the server thread.</p>
 */
public final class BuoyancyManager {
    /**
     * Samples per axis taken inside each collider box or loose-body OBB.
     * Three samples per axis (27 per box) is a compromise between accurately
     * finding a water line and avoiding a fluid-state lookup storm.
     */
    public static final int SAMPLES_PER_AXIS = 3;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    /**
     * Physics worlds that already have our per-step listener. Identity keys are
     * important because {@link LevelPhysics} may be replaced when a dimension
     * reloads.
     */
    private static final Set<LevelPhysics> HOOKED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private BuoyancyManager() {
    }

    /**
     * Registers the start-of-server-tick hook. Calling this more than once is
     * harmless; only the first call installs a listener.
     */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ServerTickEvents.START_SERVER_TICK.register(BuoyancyManager::tick);
        }
    }

    public static boolean registered() {
        return REGISTERED.get();
    }

    private static void tick(MinecraftServer server) {
        ensureStepHooks(server);
    }

    /**
     * Installs a {@link StepListener} on every live level physics world. Idempotent
     * and cheap when all worlds are already hooked.
     */
    private static void ensureStepHooks(MinecraftServer server) {
        if (server == null || !MinecraftPhysics.available()) {
            return;
        }
        HOOKED.removeIf(LevelPhysics::closed);
        for (ServerLevel level : server.getAllLevels()) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            if (physics == null || physics.closed() || !HOOKED.add(physics)) {
                continue;
            }
            physics.world().addStepListener(new StepListener() {
                @Override
                public void beforeStep(PhysicsWorld world, double fixedTimeStep) {
                    apply(physics);
                }
            });
        }
    }

    /**
     * Applies one buoyancy pass to every loaded physics world owned by the
     * server immediately. Normally you do not need this: {@link #register()}
     * installs per-step listeners. Calling it while those listeners are active
     * adds an extra one-step force on top of the listener's force.
     *
     * @return number of bodies that received a lift force
     */
    public static int apply(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        final CrythstConfig config = MinecraftPhysics.config();
        if (!MinecraftPhysics.available() || !config.enabled || !config.buoyancyEnabled) {
            return 0;
        }
        int applied = 0;
        for (ServerLevel level : server.getAllLevels()) {
            final LevelPhysics physics = MinecraftPhysics.getIfPresent(level);
            if (physics != null && !physics.closed()) {
                applied += apply(physics);
            }
        }
        return applied;
    }

    /**
     * Applies one step's worth of buoyancy forces to a single level.
     *
     * <p>This is the callback body used by the {@link StepListener} installed
     * from {@link #register()}. It runs immediately before each fixed step, so
     * the force is not lost when Bullet clears forces after that step. Manual
     * stepping therefore also gets buoyancy without a separate call.</p>
     *
     * <p>Call it directly only when the manager has <em>not</em> been registered
     * and you want to supply buoyancy yourself before a manual step.</p>
     *
     * @return number of bodies that received a lift force
     */
    public static int apply(LevelPhysics physics) {
        if (physics == null || physics.closed() || physics.paused()) {
            return 0;
        }
        final CrythstConfig config = MinecraftPhysics.config();
        if (!config.enabled || !config.buoyancyEnabled) {
            return 0;
        }

        final PhysicsWorld world = physics.world();
        final ServerLevel level = physics.level();
        if (world.gravityMagnitude() <= 0.0) {
            return 0;
        }

        int applied = 0;
        final List<PhysicsStructure> structures = physics.structures();
        final Set<Long> structureBodyIds = new HashSet<>(Math.max(8, structures.size() * 2));
        for (PhysicsStructure structure : structures) {
            if (structure.closed()) {
                continue;
            }
            structureBodyIds.add(structure.body().id());
            if (structure.body().type() != BodyType.DYNAMIC) {
                continue;
            }
            final double submerged = submergedStructureVolume(level, structure);
            if (applyLift(config, world, structure.body(), submerged)) {
                applied++;
            }
        }

        // Structures are also present in world.bodies(); skip them here so their
        // lift is not applied twice.
        for (BodyHandle body : world.bodies()) {
            if (structureBodyIds.contains(body.id())
                    || body.type() != BodyType.DYNAMIC
                    || body.removeRequested()
                    || !body.inWorld()) {
                continue;
            }
            final double submerged = submergedLooseBodyVolume(level, body);
            if (applyLift(config, world, body, submerged)) {
                applied++;
            }
        }
        return applied;
    }

    /**
     * @return {@code true} when a lift force was applied
     */
    private static boolean applyLift(CrythstConfig config, PhysicsWorld world,
                                     BodyHandle body, double submergedVolume) {
        if (!(submergedVolume > 0.0) || !Double.isFinite(submergedVolume)) {
            return false;
        }

        double bodyMass = body.mass();
        if (!(bodyMass > 0.0)) {
            // Loose bodies normally carry an explicit mass. If they do not,
            // derive it from the same density data the physics backend uses.
            final double shapeVolume = body.shape().volume();
            if (shapeVolume > 0.0) {
                bodyMass = shapeVolume * body.material().density();
            }
        }
        if (!(bodyMass > 0.0)) {
            return false;
        }
        // For structures body.mass() is collider mass * massScale. The block
        // masses come from BlockVoxelVolume/BlockPhysicsMapper, so the default
        // 500 kg end-stone block has density 500 kg/m³ over its 1 m³ voxel.

        final double displacedMass = BuoyancyMath.displacedMass(config.waterDensity, submergedVolume);
        final double gravity = world.gravityMagnitude();

        // netBuoyantForce is the force that should remain after Bullet's own
        // gravity integration. Bullet already applies weight, so writing the
        // net value as an additional force would subtract the body's weight
        // twice. Apply the full displaced-water force; for a light body this
        // leaves exactly the clamped net lift, while a dense body is simply
        // allowed to sink.
        final double netLift = BuoyancyMath.netBuoyantForce(
                config.waterDensity, submergedVolume, bodyMass, gravity);
        if (!(netLift > 0.0)) {
            return false;
        }
        final double buoyancy = BuoyancyMath.buoyantForce(
                config.waterDensity, submergedVolume, gravity);
        if (!(buoyancy > 0.0)) {
            return false;
        }

        // world.up() is the direction opposite gravity, so this also works for
        // custom gravity directions (e.g. sideways gravity).
        final Vector3d force = new Vector3d(world.up()).mul(buoyancy);
        body.applyCentralForce(force);

        // Viscous water drag, using buoyancyDamping as a kg/s coefficient.
        // It removes energy at the water line without changing the equilibrium
        // density, so end stone still floats.
        final double drag = BuoyancyMath.dragCoefficient(config.buoyancyDamping, displacedMass);
        if (drag > 0.0) {
            final Vector3d velocity = body.linearVelocity();
            if (velocity.lengthSquared() > 1.0e-12) {
                body.applyCentralForce(velocity.mul(-drag));
            }
        }
        body.activate();
        return true;
    }

    /**
     * Submerged solid volume of a block structure. Sampling happens per merged
     * collider box so cut-out or L-shaped structures are approximated better
     * than by their single bounding box.
     */
    private static double submergedStructureVolume(ServerLevel level, PhysicsStructure structure) {
        final VoxelColliderData collider = structure.collider();
        if (collider == null || collider.isEmpty()) {
            return 0.0;
        }
        final double blockScale = collider.blockScale();
        final Transform3d pose = structure.body().transform();
        final Quaterniond rotation = pose.rotation();
        final Vector3d centerOfMass = collider.centerOfMass();
        final Vector3d localCenter = new Vector3d();
        final Vector3d worldCenter = new Vector3d();
        final Vector3d size = new Vector3d();

        double submerged = 0.0;
        for (VoxelBox box : collider.boxes()) {
            localCenter.set(box.center()).sub(centerOfMass).mul(blockScale);
            pose.transformPoint(localCenter, worldCenter);
            size.set(box.sizeX(), box.sizeY(), box.sizeZ()).mul(blockScale);
            final double fraction = sampledSubmergedFraction(level, worldCenter, size, rotation);
            if (fraction > 0.0) {
                submerged += size.x * size.y * size.z * fraction;
            }
        }
        return submerged;
    }

    /**
     * Submerged volume of a loose body. Uses the shape's analytic volume when
     * available and falls back to its local AABB.
     */
    private static double submergedLooseBodyVolume(ServerLevel level, BodyHandle body) {
        final PhysicsShape shape = body.shape();
        final Aabb localBounds = shape.localBounds();
        final OrientedBox obb = OrientedBox.fromAabb(localBounds, body.transform());
        double volume = shape.volume();
        if (!(volume > 0.0)) {
            volume = localBounds.volume();
        }
        if (!(volume > 0.0)) {
            return 0.0;
        }
        final double fraction = sampledSubmergedFraction(level, obb.center(), obb.size(), obb.rotation());
        return volume * fraction;
    }

    /**
     * Grid-samples an oriented box and returns the fraction of samples below a
     * water surface.
     */
    private static double sampledSubmergedFraction(ServerLevel level, Vector3d center,
                                                   Vector3d size, Quaterniond rotation) {
        final double stepX = size.x / SAMPLES_PER_AXIS;
        final double stepY = size.y / SAMPLES_PER_AXIS;
        final double stepZ = size.z / SAMPLES_PER_AXIS;
        int submerged = 0;
        int total = 0;
        final Vector3d local = new Vector3d();
        final Vector3d world = new Vector3d();
        for (int ix = 0; ix < SAMPLES_PER_AXIS; ix++) {
            local.x = -0.5 * size.x + (ix + 0.5) * stepX;
            for (int iy = 0; iy < SAMPLES_PER_AXIS; iy++) {
                local.y = -0.5 * size.y + (iy + 0.5) * stepY;
                for (int iz = 0; iz < SAMPLES_PER_AXIS; iz++) {
                    local.z = -0.5 * size.z + (iz + 0.5) * stepZ;
                    world.set(local).rotate(rotation).add(center);
                    total++;
                    if (isWater(level, world.x, world.y, world.z)) {
                        submerged++;
                    }
                }
            }
        }
        return BuoyancyMath.submergedFraction(submerged, total);
    }

    /**
     * Checks a world-space point against the water fluid state without loading
     * chunks. Unloaded chunks are treated as dry rather than forcing generation
     * during a physics tick.
     */
    private static boolean isWater(ServerLevel level, double x, double y, double z) {
        final BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.isLoaded(pos)) {
            return false;
        }
        final FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) {
            return false;
        }
        // Flowing/partial water has a surface below the block top; only count
        // samples actually beneath it.
        return y < pos.getY() + fluid.getHeight(level, pos);
    }
}
