package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionMeshCullingTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void hidesQuadOnlyWhenTheNeighbourInThatDirectionIsSolid() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final StructureVoxelView view = new FixedView(stone, 1, 0, 0);

        // Quad faces EAST, stone neighbour at (1,0,0): hidden.
        assertTrue(SectionMesh.isCulled(view, stone, quads(Direction.EAST.ordinal()),
                0, 0, 0, 0));
        // Quad faces NORTH, no neighbour at (0,0,-1): visible.
        assertFalse(SectionMesh.isCulled(view, stone, quads(Direction.NORTH.ordinal()),
                0, 0, 0, 0));
        // A quad without a cull direction is never removed.
        assertFalse(SectionMesh.isCulled(view, stone, quads(-1), 0, 0, 0, 0));
        // Null view (defensive path) never culls.
        assertFalse(SectionMesh.isCulled(null, stone, quads(Direction.EAST.ordinal()),
                0, 0, 0, 0));
    }

    @Test
    void neighbourLookupCrossesSectionBoundaries() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        // Block at x=15 is the last cell of section 0; the occluder is the
        // first cell of the next section. The view is section-agnostic, so the
        // boundary must not matter.
        final StructureVoxelView view = new FixedView(stone, 16, 0, 0);
        assertTrue(SectionMesh.isCulled(view, stone, quads(Direction.EAST.ordinal()),
                0, 15, 0, 0));
    }

    @Test
    void enclosedBlockSkipsAllQuadsButOpenTopKeepsTheTopFace() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final Map<Long, BlockState> cells = new HashMap<>();
        for (Direction direction : Direction.values()) {
            cells.put(key(direction.getStepX(), direction.getStepY(), direction.getStepZ()),
                    stone);
        }
        final StructureVoxelView enclosed = view(cells);

        assertTrue(SectionCulling.isFullyEnclosed(enclosed, stone, 0, 0, 0),
                "six full-cube neighbours mean no visible quads");
        // Open the top and verify the top face itself is still emitted.
        cells.remove(key(0, 1, 0));
        final StructureVoxelView openTop = view(cells);
        assertFalse(SectionCulling.isFullyEnclosed(openTop, stone, 0, 0, 0));
        assertFalse(SectionMesh.isCulled(openTop, stone,
                quads(Direction.UP.ordinal()), 0, 0, 0, 0),
                "the exposed top face must never be culled by enclosed logic");
    }

    @Test
    void enclosedBlockDoesNotHideASideFaceWhenThatNeighbourIsOpen() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final Map<Long, BlockState> cells = new HashMap<>();
        for (Direction direction : Direction.values()) {
            if (direction != Direction.EAST) {
                cells.put(key(direction.getStepX(), direction.getStepY(), direction.getStepZ()),
                        stone);
            }
        }
        final StructureVoxelView view = view(cells);

        assertFalse(SectionCulling.isFullyEnclosed(view, stone, 0, 0, 0));
        assertFalse(SectionMesh.isCulled(view, stone,
                quads(Direction.EAST.ordinal()), 0, 0, 0, 0));
    }

    private static StructureVoxelView view(Map<Long, BlockState> cells) {
        return new StructureVoxelView() {
            @Override
            public boolean isOccluded(int x, int y, int z) {
                final BlockState state = cells.get(key(x, y, z));
                return state != null && state.canOcclude();
            }

            @Override
            public BlockState stateAt(int x, int y, int z) {
                return cells.get(key(x, y, z));
            }
        };
    }

    private static long key(int x, int y, int z) {
        return (((long) x) & 0x3FFFFFFL) << 38
                | (((long) y) & 0xFFFL) << 26
                | (((long) z) & 0x3FFFFFFL);
    }

    private static BlockGeometry.Quads quads(int face) {
        return new BlockGeometry.Quads(
                new float[12], new float[8], new float[12],
                new int[]{0xFFFFFFFF}, new int[]{0},
                new byte[]{(byte) face}, 1);
    }

    private record FixedView(BlockState state, int x, int y, int z) implements StructureVoxelView {
        @Override
        public boolean isOccluded(int queryX, int queryY, int queryZ) {
            return queryX == x && queryY == y && queryZ == z;
        }

        @Override
        public BlockState stateAt(int queryX, int queryY, int queryZ) {
            return queryX == x && queryY == y && queryZ == z ? state : null;
        }
    }
}
