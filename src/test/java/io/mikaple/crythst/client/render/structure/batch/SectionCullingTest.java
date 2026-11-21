package io.mikaple.crythst.client.render.structure.batch;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionCullingTest {
    private static final double EPSILON = 1.0e-9;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sectionBoundsIncludeAllEightCornersAndTheModelMargin() {
        final Transform3d pose = Transform3d.of(new Vector3d(10.0, 20.0, 30.0));
        final AABB bounds = SectionCulling.sectionBounds(pose,
                0.0, 0.0, 0.0,
                16.0, 8.0, 4.0,
                1.0);

        assertEquals(9.0, bounds.minX, EPSILON);
        assertEquals(19.0, bounds.minY, EPSILON);
        assertEquals(29.0, bounds.minZ, EPSILON);
        assertEquals(27.0, bounds.maxX, EPSILON);
        assertEquals(29.0, bounds.maxY, EPSILON);
        assertEquals(35.0, bounds.maxZ, EPSILON);
    }

    @Test
    void rotatedSectionBoundsAreConservativeForEveryCorner() {
        final Quaterniond rotation = new Quaterniond().rotationY(Math.toRadians(90.0));
        final Transform3d pose = Transform3d.of(new Vector3d(1.0, 2.0, 3.0), rotation);
        final double margin = 0.5;
        final AABB bounds = SectionCulling.sectionBounds(pose,
                -0.5, 0.0, 0.0,
                2.5, 1.0, 1.0,
                margin);

        // rotationY(90) maps (x, z) to (z, -x).
        assertEquals(0.5, bounds.minX, EPSILON);
        assertEquals(1.5, bounds.minY, EPSILON);
        assertEquals(0.0, bounds.minZ, EPSILON);
        assertEquals(2.5, bounds.maxX, EPSILON);
        assertEquals(3.5, bounds.maxY, EPSILON);
        assertEquals(4.0, bounds.maxZ, EPSILON);

        // Every transformed corner must be inside the returned hull. This is
        // the property that makes the frustum test conservative.
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d point = pose.transformPoint(new Vector3d(
                    (corner & 1) == 0 ? -0.5 - margin : 2.5 + margin,
                    (corner & 2) == 0 ? 0.0 - margin : 1.0 + margin,
                    (corner & 4) == 0 ? 0.0 - margin : 1.0 + margin));
            assertTrue(point.x >= bounds.minX - EPSILON && point.x <= bounds.maxX + EPSILON);
            assertTrue(point.y >= bounds.minY - EPSILON && point.y <= bounds.maxY + EPSILON);
            assertTrue(point.z >= bounds.minZ - EPSILON && point.z <= bounds.maxZ + EPSILON);
        }
    }

    @Test
    void enclosedBlockRequiresSixFullCubeNeighbours() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final Map<Long, BlockState> cells = new HashMap<>();
        for (Direction direction : Direction.values()) {
            cells.put(key(direction.getStepX(), direction.getStepY(), direction.getStepZ()), stone);
        }
        final StructureVoxelView enclosed = view(cells);

        assertTrue(SectionCulling.isFullyEnclosed(enclosed, stone, 0, 0, 0));

        // Opening the top neighbour must keep the top face: this is the
        // regression the enclosed culling could otherwise introduce.
        cells.remove(key(0, 1, 0));
        assertFalse(SectionCulling.isFullyEnclosed(view(cells), stone, 0, 0, 0));
    }

    @Test
    void shapedOrTransparentNeighbourDoesNotEnclose() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final Map<Long, BlockState> cells = new HashMap<>();
        for (Direction direction : Direction.values()) {
            cells.put(key(direction.getStepX(), direction.getStepY(), direction.getStepZ()), stone);
        }
        final BlockState glass = Blocks.GLASS.defaultBlockState();
        cells.put(key(0, 1, 0), glass);

        assertFalse(SectionCulling.isFullyEnclosed(view(cells), stone, 0, 0, 0),
                "glass is not a full opaque cube");
        assertFalse(SectionCulling.isFullyEnclosed(view(cells), glass, 0, 0, 0),
                "a non-full-cube block itself is never enclosed");
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
}
