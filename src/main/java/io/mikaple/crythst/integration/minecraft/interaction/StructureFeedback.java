package io.mikaple.crythst.integration.minecraft.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;

/**
 * Server-side break/place feedback for physics structure voxels.
 *
 * <p>Vanilla only emits block particles and positional sounds for blocks in
 * the real block grid. Structure voxels are simulated separately, so the
 * interaction layer has to emit the same feedback explicitly. Everything here
 * runs on the server: {@link ServerLevel#levelEvent} and
 * {@link ServerLevel#sendParticles} broadcast to tracking clients, and
 * {@link ServerLevel#playSound} broadcasts a positional sound packet. Nothing
 * is played through a client-local code path.</p>
 *
 * <p>Breaking keeps the full vanilla destroy-block effect. Placement emits the
 * block's place sound once but deliberately emits no particles: structure
 * placement already has an authoritative impulse and outline, and the small
 * puff made the edit look noisy or like a failed break.</p>
 *
 * <p>Particle counts and spreads are pure functions of the structure's
 * {@code blockScale} so they can be unit tested without a live level.</p>
 */
public final class StructureFeedback {
    /**
     * Vanilla destroy-block event (id 2001). The client handles it by playing
     * the state's break sound and running the same 4x4x4 destroy-block particle
     * burst as a real block break. Using the level event instead of a hand-built
     * burst keeps the particle count and block shape identical to vanilla.
     */
    public static final int BREAK_LEVEL_EVENT = LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK;
    /** Slightly quieter than a break, matching the vanilla place sound. */
    public static final float PLACE_VOLUME = 0.9f;
    /** Outward velocity for the fallback break debris. */
    public static final double BREAK_PARTICLE_SPEED = 0.12;

    private static final int BREAK_BASE_PARTICLES = 8;
    private static final int BREAK_PARTICLES_PER_SCALE = 4;
    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 3.0;
    private static final double MIN_SPREAD = 0.15;
    private static final double MAX_SPREAD = 0.50;
    private static final double SPREAD_PER_SCALE = 0.25;

    private StructureFeedback() {
    }

    /**
     * Number of block-break particles for a structure with the given voxel
     * scale. One normal-sized voxel (scale 1) emits 12 particles.
     */
    public static int breakParticleCount(double blockScale) {
        final double scale = clampScale(blockScale);
        return BREAK_BASE_PARTICLES
                + (int) Math.round(scale * BREAK_PARTICLES_PER_SCALE);
    }

    /**
     * Symmetric particle spread in blocks. Scales with the voxel size so a
     * large block produces a wider debris cloud.
     */
    public static double particleSpread(double blockScale) {
        final double scale = clampScale(blockScale);
        return Math.min(MAX_SPREAD, Math.max(MIN_SPREAD, scale * SPREAD_PER_SCALE));
    }

    /**
     * Emits the vanilla destroy-block event at a world-space point (normally
     * the centre of the removed voxel).
     *
     * <p>Event 2001 makes every tracking client play the state's break sound
     * and run the vanilla destroy-block particle burst (4x4x4 for a full cube).
     * This is the same path vanilla uses for a real block, so the burst is not
     * reduced by our own particle budget. The event already contains the
     * positional sound, so no separate {@code playSound} call is made.</p>
     *
     * <p>Must be called on the server thread, before a structure body is
     * closed, although the point is passed by value so the order only matters
     * for readability.</p>
     */
    public static void breakBlock(ServerLevel level, Vector3dc center, BlockState state,
                                  double blockScale, RandomSource random) {
        if (level == null || center == null || state == null || state.isAir()) {
            return;
        }
        final BlockPos pos = BlockPos.containing(center.x(), center.y(), center.z());
        if (state.shouldSpawnTerrainParticles()) {
            // 2001 broadcasts one ClientboundLevelEventPacket; the client
            // resolves the state id and runs addDestroyBlockEffect plus the
            // break sound at pos. Sent to all players within 64 blocks.
            level.levelEvent(null, BREAK_LEVEL_EVENT, pos, Block.getId(state));
            return;
        }
        // Fallback for states that opt out of vanilla terrain particles: still
        // show a small state-coloured burst and play the positional break sound
        // so the edit has visible/audible feedback.
        final double spread = particleSpread(blockScale);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                center.x(), center.y(), center.z(),
                breakParticleCount(blockScale),
                spread, spread, spread,
                BREAK_PARTICLE_SPEED);
        play(level, center, state, true, 1.0f, random);
    }

    /**
     * Emits the block's place sound once at the new voxel centre.
     *
     * <p>No particles are emitted. A placement puff made structure building
     * visually noisy and could be mistaken for a break effect; the sound is
     * enough feedback and matches vanilla's single place sound.</p>
     */
    public static void placeBlock(ServerLevel level, Vector3dc center, BlockState state,
                                  double blockScale, RandomSource random) {
        if (level == null || center == null || state == null || state.isAir()) {
            return;
        }
        play(level, center, state, false, PLACE_VOLUME, random);
    }

    private static void play(ServerLevel level, Vector3dc center, BlockState state,
                             boolean breaking, float volume, RandomSource random) {
        final SoundType sound = state.getSoundType();
        final SoundEvent event = breaking ? sound.getBreakSound() : sound.getPlaceSound();
        if (event == null) {
            return;
        }
        final float pitch = 0.95f + random.nextFloat() * 0.1f;
        // A null "except" player means every tracking client receives the
        // packet; the coordinates stay the voxel centre so the client applies
        // its normal linear distance attenuation instead of playing it as a
        // listener-relative sound.
        level.playSound(null, center.x(), center.y(), center.z(),
                event, SoundSource.BLOCKS, volume, pitch);
    }

    private static double clampScale(double blockScale) {
        if (!(blockScale > 0.0) || !Double.isFinite(blockScale)) {
            return 1.0;
        }
        return Math.min(MAX_SCALE, Math.max(MIN_SCALE, blockScale));
    }
}
