package io.mikaple.crythst.integration.minecraft.ai;

import io.mikaple.crythst.api.sublevel.view.StructureLevelView;
import io.mikaple.crythst.math.Aabb;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePathIndexTest {
    private static final double EPSILON = 1.0e-6;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Unit tests do not load a datapack, so vanilla fluid/block tag
        // bindings are empty. Bind only the tags exercised below; production
        // pathfinding uses the normally loaded datapack tags.
        bindTags(Fluids.WATER.builtInRegistryHolder(), FluidTags.WATER);
        bindTags(Fluids.LAVA.builtInRegistryHolder(), FluidTags.LAVA);
        bindTags(Blocks.OAK_FENCE.builtInRegistryHolder(), BlockTags.FENCES);
        bindTags(Blocks.COBBLESTONE_WALL.builtInRegistryHolder(), BlockTags.WALLS);
    }

    @AfterAll
    static void unbindTags() {
        bindTags(Fluids.WATER.builtInRegistryHolder());
        bindTags(Fluids.LAVA.builtInRegistryHolder());
        bindTags(Blocks.OAK_FENCE.builtInRegistryHolder());
        bindTags(Blocks.COBBLESTONE_WALL.builtInRegistryHolder());
    }

    @SafeVarargs
    private static void bindTags(Object holder, TagKey<?>... tags) {
        try {
            final Method method = holder.getClass().getDeclaredMethod(
                    "bindTags", Collection.class);
            method.setAccessible(true);
            method.invoke(holder, Set.of(tags));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not bind test tags", failure);
        }
    }

    @Test
    void mapsWorldBlocksToLocalVoxelsForRotatedAndScaledPoses() {
        final TestView identity = new TestView(new IntBounds3(0, 0, 0, 3, 2, 3),
                Transform3d.identity(), 1.0);
        identity.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        identity.put(1, 0, 1, Blocks.GLASS.defaultBlockState());
        final StructurePathVolume volume = StructurePathVolume.of(identity);

        assertEquals(new BlockPos(0, 0, 0), volume.localVoxel(new BlockPos(0, 0, 0)));
        assertEquals(new BlockPos(1, 0, 1), volume.localVoxel(new BlockPos(1, 0, 1)));
        assertNull(volume.localVoxel(new BlockPos(9, 0, 9)));
        assertEquals(Blocks.STONE.defaultBlockState(),
                volume.blockStateAtWorld(new BlockPos(0, 0, 0)));

        final Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(90.0));
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 64.0, -7.0), rotation);
        final TestView rotated = new TestView(new IntBounds3(0, 0, 0, 2, 2, 4), pose, 2.0);
        rotated.put(1, 0, 2, Blocks.OAK_PLANKS.defaultBlockState());
        final StructurePathVolume rotatedVolume = StructurePathVolume.of(rotated);
        final BlockPos world = blockPosOfLocal(rotated, pose, 1, 0, 2);
        assertEquals(new BlockPos(1, 0, 2), rotatedVolume.localVoxel(world));
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(),
                rotatedVolume.blockStateAtWorld(world));

        // The broad-phase box must contain the transformed floor corners.
        assertTrue(rotatedVolume.worldBounds().contains(
                pose.transformPoint(new Vector3d(0.0, 0.0, 0.0))));
        assertTrue(rotatedVolume.worldBounds().contains(
                pose.transformPoint(new Vector3d(4.0, 4.0, 8.0))));

        // Scale 0.5 puts several structure voxels in one world cell. The
        // centre sample can land in an air voxel, so edge sampling must still
        // find the solid voxel touching the cell corner.
        final TestView scaled = new TestView(new IntBounds3(0, 0, 0, 2, 1, 1),
                Transform3d.identity(), 0.5);
        scaled.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        final StructurePathVolume scaledVolume = StructurePathVolume.of(scaled);
        assertEquals(Blocks.STONE.defaultBlockState(),
                scaledVolume.blockStateAtWorld(new BlockPos(0, 0, 0)));
    }

    @Test
    void probePrefersRealWorldBlocksAndMergesMultipleStructures() {
        final TestView first = new TestView(new IntBounds3(0, 0, 0, 2, 2, 2),
                Transform3d.identity(), 1.0);
        first.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        first.put(0, 0, 1, Blocks.STONE.defaultBlockState());
        final TestView second = new TestView(new IntBounds3(8, 0, 0, 10, 2, 2),
                Transform3d.identity(), 1.0);
        second.put(8, 0, 0, Blocks.DIRT.defaultBlockState());

        final StructurePathIndex index = StructurePathIndex.of(
                List.of(StructurePathVolume.of(first), StructurePathVolume.of(second)),
                new Aabb(new Vector3d(0, 0, 0), new Vector3d(16, 16, 16)));

        final TestWorld world = new TestWorld();
        world.put(new BlockPos(0, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState());
        world.put(new BlockPos(1, 1, 1), Blocks.WATER.defaultBlockState());
        final StructurePathProbe probe = StructurePathProbe.of(world, index, null, 0, 16);

        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(),
                probe.getBlockState(new BlockPos(0, 0, 0)),
                "world blocks stay authoritative");
        assertEquals(Blocks.STONE.defaultBlockState(),
                probe.getBlockState(new BlockPos(0, 0, 1)),
                "a neighbouring structure voxel is still visible");
        assertEquals(Blocks.DIRT.defaultBlockState(),
                probe.getBlockState(new BlockPos(8, 0, 0)));
        assertTrue(probe.getBlockState(new BlockPos(4, 0, 0)).isAir());
        assertTrue(probe.getFluidState(new BlockPos(8, 0, 0)).isEmpty());
    }

    @Test
    void structureCollisionShapesUseStructureNeighbours() {
        final TestView view = new TestView(new IntBounds3(0, 0, 0, 3, 4, 3),
                Transform3d.identity(), 1.0);
        view.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        view.put(0, 1, 0, Blocks.STONE_SLAB.defaultBlockState());
        view.put(2, 0, 0, Blocks.OAK_STAIRS.defaultBlockState());

        final StructurePathIndex index = StructurePathIndex.of(
                List.of(StructurePathVolume.of(view)),
                new Aabb(new Vector3d(0, 0, 0), new Vector3d(8, 8, 8)));
        final StructurePathProbe probe = StructurePathProbe.of(
                new TestWorld(), index, null, 0, 16);

        final List<VoxelShape> slabCollisions = new ArrayList<>();
        probe.getBlockCollisions(null, new AABB(0.1, 1.1, 0.1, 0.9, 1.9, 0.9))
                .forEach(slabCollisions::add);
        assertEquals(1, slabCollisions.size());
        assertEquals(1.5, slabCollisions.get(0).bounds().maxY, EPSILON,
                "a bottom slab contributes only its 0.5-high collision box");

        final List<VoxelShape> stairCollisions = new ArrayList<>();
        probe.getBlockCollisions(null, new AABB(2.1, 0.1, 0.1, 2.9, 0.9, 0.9))
                .forEach(stairCollisions::add);
        assertEquals(1, stairCollisions.size());
        assertFalse(stairCollisions.get(0).isEmpty());

        assertEquals(1.5, StructurePathFloor.floorLevel(probe, new BlockPos(0, 2, 0)),
                EPSILON,
                "floor height follows the slab collision shape, not a full block");
    }

    @Test
    void nodeTypesSeeStructureFloorsWallsWaterLavaAndDanger() {
        final TestView view = new TestView(new IntBounds3(0, 0, 0, 8, 4, 4),
                Transform3d.identity(), 1.0);
        view.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        view.put(1, 0, 0, Blocks.WATER.defaultBlockState());
        view.put(2, 0, 0, Blocks.LAVA.defaultBlockState());
        view.put(3, 0, 0, Blocks.CACTUS.defaultBlockState());
        view.put(4, 0, 0, Blocks.OAK_FENCE.defaultBlockState());
        view.put(5, 0, 0, Blocks.COBBLESTONE_WALL.defaultBlockState());

        final StructurePathIndex index = StructurePathIndex.of(
                List.of(StructurePathVolume.of(view)),
                new Aabb(new Vector3d(0, 0, 0), new Vector3d(16, 16, 16)));
        final StructurePathProbe probe = StructurePathProbe.of(
                new TestWorld(), index, null, 0, 16);

        assertEquals(PathType.BLOCKED,
                StructurePathNodeTypes.pathType(probe, new BlockPos(0, 0, 0)));
        assertEquals(PathType.OPEN,
                StructurePathNodeTypes.pathType(probe, new BlockPos(0, 1, 0)));
        assertEquals(PathType.WATER,
                StructurePathNodeTypes.pathType(probe, new BlockPos(1, 0, 0)),
                "water state=" + probe.getBlockState(new BlockPos(1, 0, 0)));
        assertEquals(PathType.LAVA,
                StructurePathNodeTypes.pathType(probe, new BlockPos(2, 0, 0)),
                "lava state=" + probe.getBlockState(new BlockPos(2, 0, 0)));
        assertEquals(PathType.DAMAGING,
                StructurePathNodeTypes.pathType(probe, new BlockPos(3, 0, 0)),
                "cactus state=" + probe.getBlockState(new BlockPos(3, 0, 0)));
        assertEquals(PathType.FENCE,
                StructurePathNodeTypes.pathType(probe, new BlockPos(4, 0, 0)));
        assertEquals(PathType.FENCE,
                StructurePathNodeTypes.pathType(probe, new BlockPos(5, 0, 0)));
    }

    @Test
    void surfaceScanFindsPlatformInsteadOfTerrainBelow() {
        final TestView view = new TestView(new IntBounds3(0, -4, 0, 4, 0, 4),
                Transform3d.identity(), 1.0);
        view.put(0, -1, 0, Blocks.STONE.defaultBlockState());
        final StructurePathIndex index = StructurePathIndex.of(
                List.of(StructurePathVolume.of(view)),
                new Aabb(new Vector3d(0, -8, 0), new Vector3d(8, 8, 8)));
        final StructurePathProbe probe = StructurePathProbe.of(
                new TestWorld(), index, null, -8, 16);

        final BlockPos surface = StructurePathSurface.findSurfacePosition(
                probe, new BlockPos(0, 3, 0), 32);
        assertEquals(new BlockPos(0, 0, 0), surface,
                "scan down through world air, stop above the structure floor");
    }

    @Test
    void cacheRebuildsWhenPoseOrContentStampChanges() {
        final MutableSource source = new MutableSource();
        final TestView first = new TestView(new IntBounds3(0, 0, 0, 2, 2, 2),
                Transform3d.identity(), 1.0);
        first.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        source.volumes = List.of(StructurePathVolume.of(first));

        final StructurePathIndex index = StructurePathIndex.build(source,
                new Aabb(new Vector3d(0, 0, 0), new Vector3d(8, 8, 8)));
        assertEquals(Blocks.STONE.defaultBlockState(),
                index.structureState(new BlockPos(0, 0, 0)));

        final TestView moved = new TestView(new IntBounds3(0, 0, 0, 2, 2, 2),
                Transform3d.of(new Vector3d(0.0, 3.0, 0.0)), 1.0);
        moved.put(0, 0, 0, Blocks.DIRT.defaultBlockState());
        source.volumes = List.of(StructurePathVolume.of(moved));
        source.stamp++;
        for (int i = 0; i < StructurePathLimits.VALIDATION_INTERVAL; i++) {
            index.structureState(new BlockPos(0, 0, 0));
        }
        assertEquals(Blocks.DIRT.defaultBlockState(),
                index.structureState(new BlockPos(0, 3, 0)),
                "index invalidates on the next validation interval");
        assertEquals(1, index.stats().rebuilds());
    }

    @Test
    void queryBudgetFallsBackToVanilla() {
        final TestView view = new TestView(new IntBounds3(0, 0, 0, 2, 2, 2),
                Transform3d.identity(), 1.0);
        view.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        final StructurePathIndex index = StructurePathIndex.of(
                List.of(StructurePathVolume.of(view)),
                new Aabb(new Vector3d(0, 0, 0), new Vector3d(8, 8, 8)), 2);

        assertNotNull(index.structureState(new BlockPos(0, 0, 0)));
        assertNotNull(index.structureState(new BlockPos(0, 0, 0)));
        assertNull(index.structureState(new BlockPos(0, 0, 0)));
        assertTrue(index.stats().budgetExhausted());
        assertEquals(2, index.stats().queries());
    }

    private static BlockPos blockPosOfLocal(TestView view, Transform3d pose,
                                            int x, int y, int z) {
        final double scale = view.blockScale();
        final Vector3d world = pose.transformPoint(new Vector3d(
                (x + 0.5) * scale, (y + 0.5) * scale, (z + 0.5) * scale));
        // The world block whose centre is nearest this voxel centre.
        return new BlockPos((int) Math.round(world.x - 0.5),
                (int) Math.round(world.y - 0.5),
                (int) Math.round(world.z - 0.5));
    }

    private static final class MutableSource implements StructurePathSource {
        private List<StructurePathVolume> volumes = List.of();
        private long stamp = 1L;

        @Override
        public List<StructurePathVolume> snapshot(Aabb query) {
            return volumes;
        }

        @Override
        public long stamp() {
            return stamp;
        }

        @Override
        public String side() {
            return "test";
        }
    }

    private static final class TestView implements StructureLevelView {
        private final IntBounds3 bounds;
        private final Transform3d pose;
        private final double scale;
        private final Map<BlockPos, BlockState> states = new HashMap<>();

        private TestView(IntBounds3 bounds, Transform3d pose, double scale) {
            this.bounds = bounds;
            this.pose = pose;
            this.scale = scale;
        }

        private void put(int x, int y, int z, BlockState state) {
            states.put(new BlockPos(x, y, z), state);
        }

        @Override
        public @Nullable io.mikaple.crythst.physics.structure.PhysicsStructure structure() {
            return null;
        }

        @Override
        public @Nullable net.minecraft.world.level.Level level() {
            return null;
        }

        @Override
        public double blockScale() {
            return scale;
        }

        @Override
        public IntBounds3 bounds() {
            return bounds;
        }

        @Override
        public Transform3d pose() {
            return pose;
        }

        @Override
        public boolean isCurrent() {
            return true;
        }

        @Override
        public BlockState getBlockState(BlockPos localPos) {
            return states.getOrDefault(localPos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public RegistryAccess registryAccess() {
            return RegistryAccess.EMPTY;
        }
    }

    private static final class TestWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> states = new HashMap<>();

        private void put(BlockPos pos, BlockState state) {
            states.put(pos, state);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public int getMinY() {
            return -8;
        }
    }
}
