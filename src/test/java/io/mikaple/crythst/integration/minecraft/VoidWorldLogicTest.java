package io.mikaple.crythst.integration.minecraft;

import io.mikaple.crythst.integration.minecraft.entity.projection.StructureEntityProjection;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    void replacedChunkObjectIsRegisteredAgainButSameChunkIsNot() {
        final Object firstChunk = new Object();
        final Object replacement = new Object();

        assertTrue(EmptyPhysicsLevel.needsTickContainerRegistration(null, firstChunk),
                "a newly activated chunk registers its tick container");
        assertFalse(EmptyPhysicsLevel.needsTickContainerRegistration(firstChunk, firstChunk),
                "the same live chunk must not be unpacked/registered every tick");
        assertTrue(EmptyPhysicsLevel.needsTickContainerRegistration(firstChunk, replacement),
                "an unloaded/replaced chunk object needs its new containers registered");
        assertFalse(EmptyPhysicsLevel.needsTickContainerRegistration(firstChunk, null),
                "an unavailable chunk is skipped, not treated as a new chunk");
        assertFalse(EmptyPhysicsLevel.needsTickContainerRegistration(null, null),
                "no chunk means no registration work");
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
    void blockEntityDataKeepsItsVoxelWhenRegionBoundsChange() {
        final EmptyPhysicsLevel.RegionView oldRegion = new EmptyPhysicsLevel.RegionView(
                17L, 100, 0, 200, 0, 2, -3, 3, 4, 5);
        final IntBounds3 newBounds = new IntBounds3(-1, 0, -4, 2, 6, 2);
        final CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        final CompoundTag removedFurnace = new CompoundTag();
        removedFurnace.putString("id", "minecraft:furnace");

        final Map<BlockPos, CompoundTag> rebased = EmptyPhysicsLevel.rebaseBlockEntityData(
                Map.of(new BlockPos(0, 1, 2), chest, new BlockPos(2, 1, 2), removedFurnace),
                oldRegion, newBounds);

        assertEquals(Map.of(new BlockPos(1, 3, 3), chest), rebased);
    }

    @Test
    void copyStateKeepsNonSolidLogicBlocksAndUsesStoneOnlyForSolidFallback() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        final BlockState water = Blocks.WATER.defaultBlockState();
        final BlockState torch = Blocks.REDSTONE_TORCH.defaultBlockState();

        assertEquals(wire, EmptyPhysicsLevel.copyState(wire, false),
                "redstone wire has no collision shape but must be copied");
        assertEquals(water, EmptyPhysicsLevel.copyState(water, false),
                "fluids have no solid cell but must be copied");
        assertEquals(torch, EmptyPhysicsLevel.copyState(torch, false),
                "torches have no collision shape but must be copied");
        assertEquals(Blocks.STONE.defaultBlockState(),
                EmptyPhysicsLevel.copyState(null, true),
                "a solid cell without state falls back to stone");
        assertNull(EmptyPhysicsLevel.copyState(air, false),
                "empty non-solid cells are skipped");
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

    @Test
    void dueTicksUsePriorityAndSubTickOrderAcrossChunks() {
        final List<ScheduledTick<Block>> ticks = new ArrayList<>();
        ticks.add(new ScheduledTick<>(Blocks.STONE, new BlockPos(1, 0, 0),
                100L, TickPriority.LOW, 1L));
        ticks.add(new ScheduledTick<>(Blocks.STONE, new BlockPos(2, 0, 0),
                95L, TickPriority.NORMAL, 5L));
        ticks.add(new ScheduledTick<>(Blocks.STONE, new BlockPos(3, 0, 0),
                120L, TickPriority.NORMAL, 2L));
        ticks.add(new ScheduledTick<>(Blocks.STONE, new BlockPos(4, 0, 0),
                110L, TickPriority.HIGH, 9L));

        EmptyPhysicsLevel.sortDueTicks(ticks);

        // All ticks are already due. Vanilla orders them by priority and then
        // sub-tick, not by how overdue they were (triggerTick).
        assertEquals(new BlockPos(4, 0, 0), ticks.get(0).pos());
        assertEquals(new BlockPos(3, 0, 0), ticks.get(1).pos());
        assertEquals(new BlockPos(2, 0, 0), ticks.get(2).pos());
        assertEquals(new BlockPos(1, 0, 0), ticks.get(3).pos());
    }

    @Test
    void capturedTickTagsSortByPriorityThenSubTick() {
        final ListTag ticks = new ListTag();
        ticks.add(tickTag(TickPriority.LOW, 2L));
        ticks.add(tickTag(TickPriority.HIGH, 9L));
        ticks.add(tickTag(TickPriority.NORMAL, 7L));
        ticks.add(tickTag(TickPriority.NORMAL, 3L));

        EmptyPhysicsLevel.sortCapturedTickTags(ticks);

        assertEquals(TickPriority.HIGH.getValue(),
                ticks.getCompoundOrEmpty(0).getIntOr("Priority", 0));
        assertEquals(3L, ticks.getCompoundOrEmpty(1).getLongOr("Order", 0L));
        assertEquals(7L, ticks.getCompoundOrEmpty(2).getLongOr("Order", 0L));
        assertEquals(TickPriority.LOW.getValue(),
                ticks.getCompoundOrEmpty(3).getIntOr("Priority", 0));
    }

    private static CompoundTag tickTag(TickPriority priority, long order) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("Priority", priority.getValue());
        tag.putLong("Order", order);
        return tag;
    }

    private static Vec3 toVec(Vector3d value) {
        return new Vec3(value.x, value.y, value.z);
    }
}
