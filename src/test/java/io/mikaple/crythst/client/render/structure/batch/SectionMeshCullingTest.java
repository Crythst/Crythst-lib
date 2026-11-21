package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
