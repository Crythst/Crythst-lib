package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.integration.minecraft.entity.projection.StructureEntityProjection;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidWorldLogicTest {
    private static final double EPS = 1.0e-8;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tickBudgetIsBoundedAndReportsRemainingWork() {
        final EmptyPhysicsLevel.TickBudget budget = new EmptyPhysicsLevel.TickBudget(3);
        assertEquals(3, budget.limit());
        assertEquals(3, budget.remaining());
        assertFalse(budget.exhausted());

        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertEquals(3, budget.used());
        assertEquals(0, budget.remaining());
        assertTrue(budget.exhausted());
        assertFalse(budget.tryConsume(), "a fourth tick is dropped");
        assertEquals(3, budget.used(), "dropped work does not advance the counter");

        final EmptyPhysicsLevel.TickBudget zero = new EmptyPhysicsLevel.TickBudget(0);
        assertTrue(zero.exhausted());
        assertFalse(zero.tryConsume());
    }

    @Test
    void tickResultAddsScheduledBlockAndFluidTicks() {
        final EmptyPhysicsLevel.TickResult result = new EmptyPhysicsLevel.TickResult(
                List.of(), 7, 5, 2, 11, 3, 1, 8, 4);
        assertEquals(12, result.scheduledTicks());
        assertEquals(7, result.blockTicks());
        assertEquals(5, result.fluidTicks());
        assertEquals(2, result.blockEvents());
        assertEquals(11, result.randomTicks());
        assertEquals(3, result.mirroredBlocks());
        assertEquals(4, result.dropped());
    }

    @Test
    void regionViewConvertsAuxiliaryPositionsToVolumeVoxels() {
        final EmptyPhysicsLevel.RegionView view = new EmptyPhysicsLevel.RegionView(
                17L, 100, 0, 200, -4, 2, 6, 3, 4, 5);

        assertEquals(new BlockPos(-4, 2, 6), view.toLocalVoxel(new BlockPos(100, 0, 200)));
        assertEquals(new BlockPos(-2, 5, 10), view.toLocalVoxel(new BlockPos(102, 3, 204)));
        assertNull(view.toLocalVoxel(new BlockPos(99, 0, 200)), "before base X");
        assertNull(view.toLocalVoxel(new BlockPos(103, 0, 200)), "after region X");
        assertNull(view.toLocalVoxel(new BlockPos(100, 4, 200)), "above region Y");
        assertNull(view.toLocalVoxel(new BlockPos(100, 0, 205)), "after region Z");
    }

    @Test
    void shouldMirrorStateDistinguishesAirSolidAndPropertyChanges() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();

        assertFalse(EmptyPhysicsLevel.shouldMirrorState(null, false, air),
                "air -> air is not an edit");
        assertTrue(EmptyPhysicsLevel.shouldMirrorState(null, false, stone),
                "air -> solid mirrors and reindexes");
        assertTrue(EmptyPhysicsLevel.shouldMirrorState(stone, true, air),
                "solid -> air mirrors and reindexes");
        assertTrue(EmptyPhysicsLevel.shouldMirrorState(stone, true, planks),
                "a block-state change mirrors even with unchanged solidity");
        assertFalse(EmptyPhysicsLevel.shouldMirrorState(stone, true, stone),
                "feedback copies must not loop");
        assertTrue(EmptyPhysicsLevel.shouldMirrorState(stone, false, stone),
                "solidity metadata changes are still applied");
    }

    @Test
    void soundParticleAndBlockEventPointsUseTheStructureMapping() {
        final Transform3d pose = new Transform3d(
                new Vector3d(30.0, 70.0, -5.0),
                new Quaterniond().rotateY(0.7).rotateX(0.2));
        final double scale = 1.75;
        final StructureEntityProjection.RegionAnchor anchor =
                new StructureEntityProjection.RegionAnchor(64, 0, 128);

        // A sound or particle can be emitted at a fractional point inside the
        // structure. It must move with the pose exactly like an entity.
        final Vec3 particle = new Vec3(66.25, 2.5, 130.75);
        final Vec3 expected = toVec(pose.transformPoint(new Vector3d(
                (particle.x - anchor.originX()) * scale,
                (particle.y - anchor.originY()) * scale,
                (particle.z - anchor.originZ()) * scale)));
        final Vec3 actual = StructureEntityProjection.projectPosition(
                pose, scale, anchor, particle);
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);

        // Block events use a block position. The projected point is converted
        // back to the containing host block.
        final BlockPos eventPos = BlockPos.containing(actual);
        assertNotNull(eventPos);
        assertEquals(BlockPos.containing(expected), eventPos);

        // The same point outside the region has no structure mapping; the
        // callers must drop it instead of leaving it in the void level.
        final EmptyPhysicsLevel.RegionView view = new EmptyPhysicsLevel.RegionView(
                1L, 64, 0, 128, 0, 0, 0, 2, 3, 2);
        assertNotNull(view.toLocalVoxel(new BlockPos(65, 1, 129)));
        assertNull(view.toLocalVoxel(new BlockPos(66, 1, 129)));
    }

    private static Vec3 toVec(Vector3d value) {
        return new Vec3(value.x, value.y, value.z);
    }
}
