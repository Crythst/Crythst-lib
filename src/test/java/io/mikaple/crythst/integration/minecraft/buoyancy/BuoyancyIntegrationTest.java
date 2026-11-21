package io.mikaple.crythst.integration.minecraft.buoyancy;

import io.mikaple.crythst.physics.bullet.BulletPhysicsEngine;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.shape.Shapes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the force convention used by {@link BuoyancyManager}: Bullet applies
 * gravity itself, so the full displaced-water force must be written before each
 * fixed step.
 */
class BuoyancyIntegrationTest {
    private static final double WATER_DENSITY = 1000.0;

    @Test
    void endStoneRisesWhenBuoyancyIsAppliedEachFixedStep() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final BodyHandle block = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .mass(500.0)
                    .position(new Vector3d(0.0, 20.0, 0.0))
                    .build());
            final double lift = BuoyancyMath.buoyantForce(
                    WATER_DENSITY, 1.0, world.gravityMagnitude());
            final double startY = block.transform().position().y;

            for (int i = 0; i < 120; i++) {
                block.applyCentralForce(new Vector3d(world.up()).mul(lift));
                world.step(1.0 / 60.0);
            }

            assertTrue(block.transform().position().y > startY + 5.0,
                    "end stone should rise, y=" + block.transform().position().y);
        }
    }

    @Test
    void denseBodyWithoutLiftStillFalls() {
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(WorldSettings.defaults())) {
            final BodyHandle block = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .mass(2400.0)
                    .position(new Vector3d(0.0, 20.0, 0.0))
                    .build());
            final double netLift = BuoyancyMath.netBuoyantForce(
                    WATER_DENSITY, 1.0, block.mass(), world.gravityMagnitude());
            final double startY = block.transform().position().y;

            for (int i = 0; i < 120; i++) {
                if (netLift > 0.0) {
                    block.applyCentralForce(new Vector3d(world.up()).mul(netLift));
                }
                world.step(1.0 / 60.0);
            }

            assertTrue(netLift == 0.0, "dense body must be clamped to zero lift");
            assertTrue(block.transform().position().y < startY - 5.0,
                    "dense body should keep sinking, y=" + block.transform().position().y);
        }
    }

    @Test
    void buoyancyFollowsArbitraryGravityUpVector() {
        final WorldSettings settings = WorldSettings.defaults()
                .withGravity(new Vector3d(9.81, 0.0, 0.0));
        try (PhysicsWorld world = BulletPhysicsEngine.create().createWorld(settings)) {
            assertTrue(world.up().x < 0.0);
            final BodyHandle block = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
                    .mass(500.0)
                    .position(new Vector3d(20.0, 0.0, 0.0))
                    .build());
            final double lift = BuoyancyMath.buoyantForce(
                    WATER_DENSITY, 1.0, world.gravityMagnitude());
            final double startX = block.transform().position().x;

            for (int i = 0; i < 120; i++) {
                block.applyCentralForce(new Vector3d(world.up()).mul(lift));
                world.step(1.0 / 60.0);
            }

            assertTrue(block.transform().position().x < startX - 5.0,
                    "lift should follow -X world up, x=" + block.transform().position().x);
        }
    }
}
