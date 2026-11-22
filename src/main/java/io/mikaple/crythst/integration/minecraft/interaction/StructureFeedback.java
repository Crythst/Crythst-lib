package io.mikaple.crythst.integration.minecraft.interaction;

import io.mikaple.crythst.math.Transform3d;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Server-side break/place feedback for physics structure voxels.
 *
 * <p>Vanilla only emits block particles and positional sounds for blocks in
 * the real block grid. Structure voxels are simulated separately, so the
 * interaction layer has to emit the same feedback explicitly. Everything here
 * runs on the server: {@link ServerLevel#sendParticles} and
 * {@link ServerLevel#playSound} broadcast to tracking clients. Nothing is
 * played through a client-local code path.</p>
 *
 * <p>Break particles use an explicit {@link BlockParticleOption} so the block
 * state texture is preserved, but each particle is emitted at the exact
 * world-space point produced by the structure pose and the voxel-local
 * fraction. The vanilla {@code LevelEvent} path (id 2001) is deliberately not
 * used: it works in integer {@code BlockPos} space, so it loses rotation and
 * scale and would duplicate a custom burst. Callers that run vanilla block
 * logic in the auxiliary level suppress its 2001 event and route it through
 * {@link #breakBlock} instead.</p>
 *
 * <p>Placement emits the block's place sound once but deliberately emits no
 * particles: structure placement already has an authoritative impulse and
 * outline, and the small puff made the edit look noisy or like a failed
 * break.</p>
 *
 * <p>Particle counts and spreads are pure functions of the structure's
 * {@code blockScale} so they can be unit tested without a live level.</p>
 */
public final class StructureFeedback {
    /** Slightly quieter than a break, matching the vanilla place sound. */
    public static final float PLACE_VOLUME = 0.9f;
    /** Outward debris speed fraction at scale 1 (see {@link StructureParticleMath}). */
    public static final double BREAK_PARTICLE_SPEED = StructureParticleMath.DEBRIS_SPEED_FRACTION;

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
     * Emits a block-textured debris burst at exact world positions derived
     * from the structure pose and the broken voxel.
     *
     * <p>Each sample gets its own zero-count particle packet: in vanilla's
     * packet format {@code count == 0} means "spawn exactly one particle here
     * with these velocity components". This is what lets rotated and scaled
     * structures keep the debris attached to the voxel instead of rounding to
     * a {@code BlockPos} centre.</p>
     *
     * @param level      host level that owns the players
     * @param pose       structure pose at the time of the break
     * @param voxelX     broken structure-local voxel X
     * @param voxelY     broken structure-local voxel Y
     * @param voxelZ     broken structure-local voxel Z
     * @param state      previous block state (supplies the particle texture)
     * @param blockScale metres per voxel
     * @param random     level random for sample positions/pitch
     */
    public static void breakBlock(ServerLevel level, Transform3d pose,
                                  int voxelX, int voxelY, int voxelZ,
                                  BlockState state, double blockScale,
                                  RandomSource random) {
        if (level == null || pose == null || state == null || state.isAir()) {
            return;
        }
        final double scale = StructureParticleMath.safeScale(blockScale);
        final BlockParticleOption particle =
                new BlockParticleOption(ParticleTypes.BLOCK, state);
        final int count = breakParticleCount(scale);
        for (int i = 0; i < count; i++) {
            final double localX = random.nextDouble();
            final double localY = random.nextDouble();
            final double localZ = random.nextDouble();
            final Vector3d point = StructureParticleMath.worldPosition(
                    pose, voxelX, voxelY, voxelZ, scale, localX, localY, localZ);
            final Vector3d velocity = StructureParticleMath.debrisVelocity(
                    pose, localX, localY, localZ, scale);
            level.sendParticles(particle, true, false,
                    point.x, point.y, point.z,
                    0, 1.0, 1.0, 1.0,
                    velocity.x, velocity.y, velocity.z,
                    ClientboundLevelParticlesPacket.RandomizationType.DEFAULT);
        }
        final Vector3d center = StructureParticleMath.voxelCenter(
                pose, voxelX, voxelY, voxelZ, scale);
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
