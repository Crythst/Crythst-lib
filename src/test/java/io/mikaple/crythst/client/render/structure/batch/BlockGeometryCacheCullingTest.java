package io.mikaple.crythst.client.render.structure.batch;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-quad cull-face resolution for quads whose {@code BakedQuad.direction()}
 * is null, plus the unculled-bucket rule that keeps inset slab/stair faces.
 *
 * <p>Live model baking needs a client model manager, so these tests exercise
 * the pure resolution step and feed the resulting face into
 * {@link SectionMesh#isCulled}.</p>
 */
class BlockGeometryCacheCullingTest {
    /** Winding normal (0, 1, 0). */
    private static final float[] UP_QUAD = {
            0.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 0.0f
    };
    /** Winding normal (-1, 0, 1) / sqrt(2): diagonal, must not become cardinal. */
    private static final float[] DIAGONAL_QUAD = {
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f,
            0.0f, 1.0f, 0.0f
    };
    private static final float[] DEGENERATE_QUAD = new float[12];

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nullDirectionInACullBucketUsesTheWindingOnlyWhenCardinal() {
        assertEquals(Direction.UP,
                BlockGeometryCache.resolveCullFace(null, true, UP_QUAD));
        assertNull(BlockGeometryCache.resolveCullFace(null, true, DIAGONAL_QUAD),
                "a diagonal cross/plant quad has no single neighbour to test");
        assertNull(BlockGeometryCache.resolveCullFace(null, true, DEGENERATE_QUAD));
    }

    @Test
    void unculledBucketNeverProducesACullFace() {
        // Vanilla emits getQuads(null) unconditionally, even when the quad's
        // geometric facing points at a full block. This is the slab/stair top
        // and bottom inset face case.
        assertNull(BlockGeometryCache.resolveCullFace(Direction.UP, false, UP_QUAD));
        assertNull(BlockGeometryCache.resolveCullFace(null, false, UP_QUAD));
    }

    @Test
    void bakedDirectionBeatsTheWinding() {
        // A custom quad may carry a direction that disagrees with its winding;
        // the baked direction is authoritative for that quad.
        assertEquals(Direction.DOWN,
                BlockGeometryCache.resolveCullFace(Direction.DOWN, true, UP_QUAD));
    }

    @Test
    void derivedCardinalFaceIsCulledButADiagonalOrUnculledQuadIsNot() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final StructureVoxelView stoneAbove = new FixedView(stone, 0, 1, 0);

        // The null-direction quad resolves to UP, so a full block above hides it.
        final Direction derived = BlockGeometryCache.resolveCullFace(null, true, UP_QUAD);
        assertEquals(Direction.UP, derived);
        assertTrue(SectionMesh.isCulled(stoneAbove, stone, quads(derived), 0, 0, 0, 0));

        // The same quad in the unculled bucket (what vanilla's getQuads(null)
        // means) must stay visible.
        assertFalse(SectionMesh.isCulled(stoneAbove, stone,
                quads(BlockGeometryCache.resolveCullFace(null, false, UP_QUAD)),
                0, 0, 0, 0));

        // Diagonal quads have no cardinal cull face and always stay visible.
        assertFalse(SectionMesh.isCulled(stoneAbove, stone,
                quads(BlockGeometryCache.resolveCullFace(null, true, DIAGONAL_QUAD)),
                0, 0, 0, 0));
    }

    private static BlockGeometry.Quads quads(Direction face) {
        return new BlockGeometry.Quads(
                new float[12], new float[8], new float[12],
                new int[]{0xFFFFFFFF}, new int[]{0},
                new byte[]{(byte) (face == null ? -1 : face.ordinal())}, 1);
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
