package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises the real vanilla {@code getStateForPlacement} hooks with the
 * synthetic click produced by {@link StructurePlacementMath}. The fake level
 * returns air/empty fluid everywhere, which is exactly the structure-voxel
 * situation for orientation rules.
 */
class StructurePlacementVanillaRulesTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stairsUsePlayerFacingAndClickedHalf() {
        final BlockHitResult side = StructurePlacementMath.blockHit(
                new Vector3d(2.0, 1.7, 0.7), Direction.EAST);
        final BlockState sideState = Blocks.OAK_STAIRS.getStateForPlacement(
                context(side, Direction.EAST));

        assertEquals(Direction.EAST, sideState.getValue(StairBlock.FACING));
        assertEquals(Half.TOP, sideState.getValue(StairBlock.HALF));

        final BlockHitResult top = StructurePlacementMath.blockHit(
                new Vector3d(1.5, 3.0, 0.5), Direction.UP);
        final BlockState topState = Blocks.OAK_STAIRS.getStateForPlacement(
                context(top, Direction.NORTH));

        assertEquals(Direction.NORTH, topState.getValue(StairBlock.FACING));
        assertEquals(Half.BOTTOM, topState.getValue(StairBlock.HALF));
    }

    @Test
    void slabsUseClickedFaceAndTopBottomFraction() {
        final BlockHitResult highSide = StructurePlacementMath.blockHit(
                new Vector3d(2.0, 1.7, 0.7), Direction.EAST);
        final BlockState top = Blocks.OAK_SLAB.getStateForPlacement(
                context(highSide, Direction.NORTH));
        assertEquals(SlabType.TOP, top.getValue(SlabBlock.TYPE));

        final BlockHitResult lowSide = StructurePlacementMath.blockHit(
                new Vector3d(2.0, 1.2, 0.7), Direction.EAST);
        final BlockState bottom = Blocks.OAK_SLAB.getStateForPlacement(
                context(lowSide, Direction.NORTH));
        assertEquals(SlabType.BOTTOM, bottom.getValue(SlabBlock.TYPE));

        final BlockHitResult ceiling = StructurePlacementMath.blockHit(
                new Vector3d(1.5, 3.0, 0.5), Direction.DOWN);
        final BlockState fromBelow = Blocks.OAK_SLAB.getStateForPlacement(
                context(ceiling, Direction.NORTH));
        assertEquals(SlabType.TOP, fromBelow.getValue(SlabBlock.TYPE));
    }

    @Test
    void pillarAxisFollowsTheClickedWorldFace() {
        assertEquals(Direction.Axis.X, logAxis(Direction.EAST));
        assertEquals(Direction.Axis.X, logAxis(Direction.WEST));
        assertEquals(Direction.Axis.Y, logAxis(Direction.UP));
        assertEquals(Direction.Axis.Y, logAxis(Direction.DOWN));
        assertEquals(Direction.Axis.Z, logAxis(Direction.NORTH));
        assertEquals(Direction.Axis.Z, logAxis(Direction.SOUTH));
    }

    @Test
    void rotatedAndScaledStructureStairsUseLocalFacing() {
        final Transform3d pose = new Transform3d(new Vector3d(10.0, 20.0, 30.0),
                new Quaterniond().rotateY(Math.toRadians(90.0)));
        final double scale = 2.0;
        final Vector3d localClick = new Vector3d(1.5, 2.0, 1.5);
        final Vector3d worldClick = pose.transformPoint(new Vector3d(localClick).mul(scale));
        final Vector3d roundTrip = StructurePlacementMath.localPoint(pose, worldClick, scale);

        assertEquals(localClick.x, roundTrip.x, 1.0e-9);
        assertEquals(localClick.y, roundTrip.y, 1.0e-9);
        assertEquals(localClick.z, roundTrip.z, 1.0e-9);

        // The player looks east in the structure frame. With a 90-degree
        // structure yaw that is a different world direction, so a world-frame
        // context would select NORTH rather than EAST.
        final StructurePlacementContext context = localContext(
                StructurePlacementMath.localBlockHit(roundTrip, Direction.UP, 1, 1, 1),
                new BlockPos(1, 2, 1), Direction.UP, new Vector3d(1.0, 0.0, 0.0));
        final BlockState state = Blocks.OAK_STAIRS.getStateForPlacement(context);

        assertNotNull(state);
        assertEquals(Direction.EAST, state.getValue(StairBlock.FACING));
        assertEquals(Half.BOTTOM, state.getValue(StairBlock.HALF));
    }

    @Test
    void logsUseEveryLocalFaceAxis() {
        for (Direction face : Direction.values()) {
            final BlockPos clicked = new BlockPos(2, 2, 2);
            final BlockPos target = clicked.relative(face);
            final Vector3d point = new Vector3d(
                    2.5 + face.getStepX() * 0.5,
                    2.5 + face.getStepY() * 0.5,
                    2.5 + face.getStepZ() * 0.5);
            final StructurePlacementContext context = localContext(
                    StructurePlacementMath.localBlockHit(point, face, 2, 2, 2),
                    target, face, new Vector3d(0.0, 0.0, 1.0));
            final BlockState state = Blocks.OAK_LOG.getStateForPlacement(context);

            assertNotNull(state);
            assertEquals(face.getAxis(), state.getValue(RotatedPillarBlock.AXIS),
                    "local face " + face);
        }
    }

    @Test
    void slabHalfUsesLocalClickFractionWithScale() {
        final Transform3d pose = new Transform3d(new Vector3d(-4.0, 7.0, 3.0),
                new Quaterniond().rotateY(Math.toRadians(-35.0)));
        final double scale = 3.0;

        final BlockState top = slabAt(pose, scale, 0.75);
        assertEquals(SlabType.TOP, top.getValue(SlabBlock.TYPE));

        final BlockState bottom = slabAt(pose, scale, 0.25);
        assertEquals(SlabType.BOTTOM, bottom.getValue(SlabBlock.TYPE));
    }

    private static BlockState slabAt(Transform3d pose, double scale, double localY) {
        final Vector3d localClick = new Vector3d(1.0, localY, 0.5);
        final Vector3d worldClick = pose.transformPoint(new Vector3d(localClick).mul(scale));
        final Vector3d roundTrip = StructurePlacementMath.localPoint(pose, worldClick, scale);
        final StructurePlacementContext context = localContext(
                StructurePlacementMath.localBlockHit(roundTrip, Direction.EAST, 0, 0, 0),
                new BlockPos(1, 0, 0), Direction.EAST, new Vector3d(0.0, 0.0, 1.0));
        return Blocks.OAK_SLAB.getStateForPlacement(context);
    }

    @Test
    void gravityRotatedLookUsesLocalYawPitchNotWorldYaw() {
        final Transform3d pose = new Transform3d(new Vector3d(),
                new Quaterniond().rotateY(Math.toRadians(90.0)));
        final Vector3d localLook = new Vector3d(0.0, -0.6, -0.8);
        final Vector3d worldLook = pose.transformDirection(localLook);
        final Vector3d roundTrip = StructurePlacementMath.localLook(pose, worldLook);

        assertEquals(localLook.x, roundTrip.x, 1.0e-9);
        assertEquals(localLook.y, roundTrip.y, 1.0e-9);
        assertEquals(localLook.z, roundTrip.z, 1.0e-9);
        assertNotEquals(StructurePlacementMath.lookYaw(worldLook),
                StructurePlacementMath.lookYaw(roundTrip));

        final StructurePlacementContext context = localContext(
                StructurePlacementMath.localBlockHit(
                        new Vector3d(1.5, 1.5, 1.5), Direction.UP, 1, 1, 1),
                new BlockPos(1, 2, 1), Direction.UP, roundTrip);

        // Local north (z = -1) is yaw 180, while the same world look is west
        // (yaw 90). getRotation and getHorizontalDirection must follow local.
        assertEquals(180.0f, Math.abs(context.getRotation()), 1.0e-4);
        assertEquals(Direction.NORTH, context.getHorizontalDirection());
        assertEquals(Direction.DOWN, context.getNearestLookingVerticalDirection());
        // Vanilla moves the clicked face's opposite to the front for a
        // non-replacing placement, so DOWN (the opposite of the UP face) is
        // the first candidate even though local look is mostly north.
        assertEquals(Direction.DOWN, context.getNearestLookingDirection());
    }

    private static StructurePlacementContext localContext(BlockHitResult localHit,
                                                          BlockPos target,
                                                          Direction localFace,
                                                          Vector3d localLook) {
        return new StructurePlacementContext(newFakeLevel(), null, ItemStack.EMPTY,
                localHit, target, localFace, localLook);
    }

    private static Direction.Axis logAxis(Direction face) {
        final BlockHitResult hit = StructurePlacementMath.blockHit(
                new Vector3d(1.5, 3.0, 0.5), face);
        final BlockState state = Blocks.OAK_LOG.getStateForPlacement(
                context(hit, Direction.NORTH));
        return state.getValue(RotatedPillarBlock.AXIS);
    }

    private static BlockPlaceContext context(BlockHitResult hit, Direction horizontal) {
        // The placement-state rules under test do not inspect the stack.
        return new TestContext(newFakeLevel(), ItemStack.EMPTY, hit, horizontal);
    }

    /**
     * Bypasses the {@code Level} constructor. The constructor eagerly builds a
     * paletted-container factory from the dynamic biome registry, which is not
     * available in a unit test. Every method used by the placement rules is
     * overridden below, so the uninitialised fields are never read.
     */
    private static FakeLevel newFakeLevel() {
        try {
            final java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            return (FakeLevel) unsafe.allocateInstance(FakeLevel.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate fake level", e);
        }
    }

    private static final class TestContext extends BlockPlaceContext {
        private final Direction horizontal;

        private TestContext(Level level, ItemStack stack, BlockHitResult hit,
                            Direction horizontal) {
            super(level, null, InteractionHand.MAIN_HAND, stack, hit);
            this.horizontal = horizontal;
        }

        @Override
        public Direction getHorizontalDirection() {
            return horizontal;
        }
    }

    /**
     * Minimal void level. Only block-state/fluid lookups are used by the vanilla
     * placement rules under test.
     */
    private static final class FakeLevel extends Level {
        /**
         * Never invoked: {@link #newFakeLevel()} allocates without running
         * constructors. Declared only to satisfy Java's super-constructor rule.
         */
        @SuppressWarnings("unused")
        private FakeLevel() {
            super(null, null, null, null, false, false, 0L, 384);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return FeatureFlagSet.of();
        }

        @Override
        public void gameEvent(Holder<net.minecraft.world.level.gameevent.GameEvent> gameEvent,
                              net.minecraft.world.phys.Vec3 position,
                              net.minecraft.world.level.gameevent.GameEvent.Context context) {
        }

        @Override
        public void levelEvent(Entity source, int eventId, BlockPos pos, int data) {
        }

        @Override
        public void playSound(Entity source, BlockPos pos, net.minecraft.sounds.SoundEvent sound,
                              net.minecraft.sounds.SoundSource category, float volume, float pitch) {
        }

        @Override
        public void addParticle(net.minecraft.core.particles.ParticleOptions particle,
                                double x, double y, double z,
                                double xSpeed, double ySpeed, double zSpeed) {
        }

        @Override
        public long nextSubTickCount() {
            return 0L;
        }

        @Override
        public LevelData getLevelData() {
            return null;
        }

        @Override
        public net.minecraft.server.MinecraftServer getServer() {
            return null;
        }

        @Override
        public net.minecraft.util.RandomSource getRandom() {
            return net.minecraft.util.RandomSource.create();
        }

        @Override
        public <T> net.minecraft.world.ticks.ScheduledTick<T> createTick(
                BlockPos pos, T type, int delay,
                net.minecraft.world.ticks.TickPriority priority) {
            return new net.minecraft.world.ticks.ScheduledTick<>(type, pos, delay, priority, 0L);
        }

        @Override
        public <T> net.minecraft.world.ticks.ScheduledTick<T> createTick(
                BlockPos pos, T type, int delay) {
            return new net.minecraft.world.ticks.ScheduledTick<>(type, pos, delay, 0L);
        }

        @Override
        public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block>
                getBlockTicks() {
            return emptyTicks();
        }

        @Override
        public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid>
                getFluidTicks() {
            return emptyTicks();
        }

        private static <T> net.minecraft.world.ticks.LevelTickAccess<T> emptyTicks() {
            return new net.minecraft.world.ticks.LevelTickAccess<>() {
                @Override
                public void schedule(net.minecraft.world.ticks.ScheduledTick<T> tick) {
                }

                @Override
                public boolean hasScheduledTick(BlockPos pos, T type) {
                    return false;
                }

                @Override
                public int count() {
                    return 0;
                }

                @Override
                public boolean willTickThisTick(BlockPos pos, T type) {
                    return false;
                }
            };
        }

        @Override
        public net.minecraft.world.level.chunk.ChunkSource getChunkSource() {
            return null;
        }

        @Override
        public int getSeaLevel() {
            return 0;
        }

        @Override
        public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
            return null;
        }

        @Override
        public List<? extends net.minecraft.world.entity.player.Player> players() {
            return List.of();
        }

        @Override
        public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        }

        @Override
        public void playSeededSound(Entity source, double x, double y, double z,
                                    Holder<net.minecraft.sounds.SoundEvent> sound,
                                    net.minecraft.sounds.SoundSource category,
                                    float volume, float pitch, long seed) {
        }

        @Override
        public void playSeededSound(Entity source, Entity except, Holder<net.minecraft.sounds.SoundEvent> sound,
                                    net.minecraft.sounds.SoundSource category,
                                    float volume, float pitch, long seed) {
        }

        @Override
        public void explode(Entity source, net.minecraft.world.damagesource.DamageSource damageSource,
                            net.minecraft.world.level.ExplosionDamageCalculator calculator,
                            double x, double y, double z, float radius, boolean fire,
                            Level.ExplosionInteraction interaction,
                            net.minecraft.core.particles.ParticleOptions smallParticles,
                            net.minecraft.core.particles.ParticleOptions largeParticles,
                            net.minecraft.util.random.WeightedList<net.minecraft.core.particles.ExplosionParticleInfo> blockParticles,
                            Holder<net.minecraft.sounds.SoundEvent> sound) {
        }

        @Override
        public String gatherChunkSourceStats() {
            return "";
        }

        @Override
        public void setRespawnData(LevelData.RespawnData respawnData) {
        }

        @Override
        public LevelData.RespawnData getRespawnData() {
            return null;
        }

        @Override
        public Entity getEntity(int id) {
            return null;
        }

        @Override
        public Collection<EnderDragonPart> dragonParts() {
            return List.of();
        }

        @Override
        public TickRateManager tickRateManager() {
            return null;
        }

        @Override
        public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(
                net.minecraft.world.level.saveddata.maps.MapId id) {
            return null;
        }

        @Override
        public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
        }

        @Override
        public net.minecraft.world.scores.Scoreboard getScoreboard() {
            return null;
        }

        @Override
        public net.minecraft.world.item.crafting.RecipeAccess recipeAccess() {
            return null;
        }

        @Override
        protected net.minecraft.world.level.entity.LevelEntityGetter<Entity> getEntities() {
            return null;
        }

        @Override
        public net.minecraft.world.clock.ClockManager clockManager() {
            return null;
        }

        @Override
        public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes() {
            return null;
        }
    }
}
