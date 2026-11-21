package io.mikaple.crythst.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.mikaple.crythst.Crythst;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON configuration. Unknown fields are preserved by Gson only when the same
 * class is used; the file is intentionally small and forward-compatible.
 */
public final class CrythstConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public double fixedTimeStep = 1.0 / 60.0;
    public int maxSubSteps = 4;
    public int solverIterations = 10;
    public boolean allowSleep = true;
    public boolean deterministic = true;
    public double maxFrameSeconds = 0.25;
    public double rebaseDistance = 2048.0;
    public volatile boolean debug = true;
    public double defaultBlockMass = 1000.0;
    public double defaultFriction = 0.7;
    public double defaultRestitution = 0.0;

    public boolean worldCollisionEnabled = true;
    public double worldCollisionRadius = 8.0;
    public int worldCollisionRefreshTicks = 10;
    public int worldCollisionMaxSections = 64;
    public double worldCollisionLookahead = 32.0;
    public boolean unstuckEnabled = true;
    public int unstuckIntervalTicks = 10;
    public double unstuckStrength = 3.0;
    public double unstuckMaxDistance = 8.0;
    public double unstuckMaxSpeed = 2.0;
    public boolean playerUnstuckEnabled = true;

    public boolean buoyancyEnabled = true;
    public double waterDensity = 1000.0;
    public double buoyancyDamping = 0.5;
    public double endStoneBlockMass = 500.0;

    public boolean entityPhysicsCollisionEnabled = true;
    public double entityCollisionMargin = 0.02;

    public boolean playerCameraGravity = true;
    public boolean playerObbCollision = true;
    public double playerObbCollisionStrength = 0.6;

    public boolean structureBlockInteractionEnabled = true;
    public double miningImpulseScale = 1.0;
    public double shovelWaterReaction = 1.0;

    /**
     * Whether each physics world creates a real void {@code ServerLevel} for
     * ticking the block logic of its structures.
     */
    public boolean subLevelEnabled = true;

    /**
     * Vanilla random-tick speed used for the auxiliary void level. {@code 0}
     * disables random block ticks while still allowing chunk ticking.
     */
    public int subLevelRandomTickSpeed = 3;

    public static CrythstConfig load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            final CrythstConfig config = new CrythstConfig();
            config.save(path);
            return config;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            final CrythstConfig config = GSON.fromJson(reader, CrythstConfig.class);
            return config == null ? new CrythstConfig() : config.sanitised();
        } catch (IOException | RuntimeException e) {
            Crythst.LOGGER.warn("Could not read {}, using defaults", path, e);
            return new CrythstConfig();
        }
    }

    public void save(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Crythst.LOGGER.warn("Could not write {}", path, e);
        }
    }

    public CrythstConfig sanitised() {
        fixedTimeStep = fixedTimeStep > 0.0 ? fixedTimeStep : 1.0 / 60.0;
        maxSubSteps = Math.max(1, maxSubSteps);
        solverIterations = Math.max(1, solverIterations);
        maxFrameSeconds = maxFrameSeconds > 0.0 ? maxFrameSeconds : 0.25;
        rebaseDistance = rebaseDistance > 0.0 ? rebaseDistance : 2048.0;
        defaultBlockMass = defaultBlockMass > 0.0 ? defaultBlockMass : 1000.0;
        worldCollisionRadius = worldCollisionRadius > 0.0 ? worldCollisionRadius : 8.0;
        worldCollisionRefreshTicks = Math.max(1, worldCollisionRefreshTicks);
        worldCollisionMaxSections = Math.max(1, worldCollisionMaxSections);
        worldCollisionLookahead = worldCollisionLookahead >= 0.0 ? worldCollisionLookahead : 32.0;
        subLevelRandomTickSpeed = Math.clamp(subLevelRandomTickSpeed, 0, 4096);
        return this;
    }
}
