package io.mikaple.crythst.client.render.light;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelLightingTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aoLevelFollowsClassicThreeNeighbourRule() {
        assertEquals(3, VoxelLighting.aoLevel(false, false, false));
        assertEquals(2, VoxelLighting.aoLevel(true, false, false));
        assertEquals(2, VoxelLighting.aoLevel(false, true, false));
        assertEquals(2, VoxelLighting.aoLevel(false, false, true));
        assertEquals(1, VoxelLighting.aoLevel(true, false, true));
        assertEquals(1, VoxelLighting.aoLevel(false, true, true));
        // Two solid edge neighbours always fully darken the vertex, even when
        // the diagonal corner happens to be empty.
        assertEquals(0, VoxelLighting.aoLevel(true, true, false));
        assertEquals(0, VoxelLighting.aoLevel(true, true, true));
    }

    @Test
    void aoShadesAreMonotonicAndBounded() {
        float previous = 0.0f;
        for (int level = 0; level <= 3; level++) {
            final float shade = VoxelLighting.aoShade(level);
            assertTrue(shade >= previous, "shade must increase with AO level");
            assertTrue(shade > 0.0f && shade <= 1.0f);
            previous = shade;
        }
        assertEquals(1.0f, VoxelLighting.aoShade(3));
        assertEquals(1.0f, VoxelLighting.aoShade(42), "out-of-range levels clamp");
        assertEquals(VoxelLighting.AO_SHADE[0], VoxelLighting.aoShade(-1));
    }

    @Test
    void applyShadePreservesAlphaAndClampsChannels() {
        assertEquals(0xFFFFFFFF, VoxelLighting.applyShade(0xFFFFFFFF, 1.0f));
        assertEquals(0xFF808080, VoxelLighting.applyShade(0xFFFFFFFF, 0.5f));
        assertEquals(0x80FFFFFF, VoxelLighting.applyShade(0x80FFFFFF, 1.0f));
        assertEquals(0x80808080, VoxelLighting.applyShade(0x80FFFFFF, 0.5f));
        assertEquals(0x00000000, VoxelLighting.applyShade(0x00123456, 0.0f));
        assertEquals(0xFF000000, VoxelLighting.applyShade(0xFFFFFFFF, -1.0f));
    }

    @Test
    void fullCubeNeighbourHidesFaceButAirAndOutsideDoNot() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState air = Blocks.AIR.defaultBlockState();

        assertTrue(VoxelLighting.hidesFace(stone, stone, Direction.UP));
        assertFalse(VoxelLighting.hidesFace(stone, air, Direction.UP));
        assertFalse(VoxelLighting.hidesFace(stone, null, Direction.UP));
        assertFalse(VoxelLighting.hidesFace(null, stone, Direction.UP));
    }
}
