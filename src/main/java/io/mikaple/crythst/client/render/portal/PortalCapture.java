package io.mikaple.crythst.client.render.portal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.FilterMode;
import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.integration.portal.PortalSnapshot;
import io.mikaple.crythst.integration.portal.PortalTransform;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Captures the real destination scene into one offscreen texture per portal.
 *
 * <h2>When and how</h2>
 *
 * <p>{@link LevelExtractionEvents#END_EXTRACTION} runs on the render thread
 * after the current frame's render state (including the interpolated camera)
 * has been extracted and before {@code LevelRenderer.render} begins. At that
 * point no framegraph pass is active, so calling
 * {@link net.minecraft.client.renderer.LevelRenderer#render} a second time with
 * a transformed {@link CameraRenderState} is safe.</p>
 *
 * <ol>
 *     <li>Build a portal camera from the main camera and the link transform
 *     (position and orientation are transformed by the portal rotation).</li>
 *     <li>Temporarily replace {@link LevelRenderState#cameraRenderState}, call
 *     {@code LevelRenderer.render} with the same projection, then restore the
 *     main camera state.</li>
 *     <li>Copy the main render target's colour texture into a per-portal
 *     offscreen target (the main target is cleared by the normal pass right
 *     after this event, so the temporary contents are harmless).</li>
 *     <li>The portal quad in the main pass samples that texture using the
 *     portal's screen-space UVs. Because the destination camera is exactly the
 *     main camera transformed through the portal, the screen-space projection
 *     lines up pixel-for-pixel.</li>
 * </ol>
 *
 * <p>Recursion depth is 1 in this milestone. During the capture pass nested
 * portals are drawn with the procedural shader (the {@link #capturing} flag),
 * never with the texture currently being written, so there is no feedback
 * loop. Portal-to-portal recursion beyond one level is a later TODO item.</p>
 *
 * <p>Capture is throttled to {@link #CAPTURE_INTERVAL_NANOS} and limited to
 * {@link #MAX_PORTALS_PER_CAPTURE} nearest visible portals per round. If the
 * destination is cross-dimensional, invisible, or the capture fails, the
 * renderer falls back to the procedural surface.</p>
 */
public final class PortalCapture {
    /** 20 captures per second; the portal view intentionally updates slower than the display. */
    private static final long CAPTURE_INTERVAL_NANOS = 50_000_000L;
    /** Nearest visible portals captured per round. */
    private static final int MAX_PORTALS_PER_CAPTURE = 2;
    /** Do not pay for a second scene render for distant portals. */
    private static final double MAX_CAPTURE_DISTANCE = 192.0;
    private static final double MAX_CAPTURE_DISTANCE_SQUARED =
            MAX_CAPTURE_DISTANCE * MAX_CAPTURE_DISTANCE;

    private static final Map<UUID, ViewTarget> TARGETS = new HashMap<>();
    private static final Set<UUID> READY = new HashSet<>();

    private static CrossFrameResourcePool resourcePool;
    private static FogRenderer fogRenderer;
    private static long lastCaptureNanos;
    private static boolean capturing;
    private static boolean registered;
    private static boolean failureLogged;

    private PortalCapture() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        LevelExtractionEvents.END_EXTRACTION.register(PortalCapture::onEndExtraction);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
    }

    /** True while a destination scene is being rendered into an offscreen texture. */
    public static boolean isCapturing() {
        return capturing;
    }

    /** True when a fresh destination texture is available for this portal. */
    public static boolean hasView(UUID portalId) {
        return READY.contains(portalId);
    }

    /** Texture registered for the portal, or {@code null} when no capture exists. */
    public static Identifier textureId(UUID portalId) {
        final ViewTarget target = TARGETS.get(portalId);
        return target == null ? null : target.textureId;
    }

    private static void onEndExtraction(LevelExtractionContext context) {
        if (capturing) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null) {
            READY.clear();
            pruneTargets(Set.of());
            return;
        }

        final LevelRenderState levelState = context.levelState();
        final CameraRenderState mainCamera = levelState == null ? null : levelState.cameraRenderState;
        if (mainCamera == null || mainCamera.pos == null) {
            return;
        }

        final List<PortalSnapshot> allPortals = PortalClientCache.get();
        final Set<UUID> live = new HashSet<>(Math.max(8, allPortals.size() * 2));
        for (PortalSnapshot portal : allPortals) {
            live.add(portal.id());
        }
        pruneTargets(live);

        if (allPortals.isEmpty()) {
            READY.clear();
            return;
        }

        final long now = System.nanoTime();
        if (now - lastCaptureNanos < CAPTURE_INTERVAL_NANOS) {
            return;
        }
        lastCaptureNanos = now;

        final List<PortalSnapshot> candidates = new ArrayList<>();
        for (PortalSnapshot portal : allPortals) {
            if (!portal.dimension().equals(level.dimension())) {
                continue;
            }
            if (!portal.viewableIn(level.dimension())) {
                continue;
            }
            if (!isVisible(mainCamera, portal)) {
                continue;
            }
            candidates.add(portal);
        }
        if (candidates.isEmpty()) {
            READY.clear();
            return;
        }
        final Vector3d cameraPosition = new Vector3d(
                mainCamera.pos.x, mainCamera.pos.y, mainCamera.pos.z);
        candidates.sort(Comparator.comparingDouble(portal -> portal.plane().distanceSquared(cameraPosition)));

        final RenderTarget mainTarget = context.gameRenderer().mainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTexture() == null
                || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }

        ensureResources();
        if (resourcePool == null || fogRenderer == null) {
            return;
        }

        final FogData fogData = copyFog(mainCamera.fogData);
        fogRenderer.updateBuffer(fogData);
        final GpuBufferSlice fogSlice = fogRenderer.getBuffer(FogRenderer.FogMode.WORLD);
        final Vector4f fogColor = new Vector4f(fogData.color);

        capturing = true;
        final Set<UUID> captured = new HashSet<>();
        try {
            int count = 0;
            for (PortalSnapshot portal : candidates) {
                if (count >= MAX_PORTALS_PER_CAPTURE) {
                    break;
                }
                final PortalTransform transform = PortalTransform.between(
                        portal.plane(), portal.destinationPlane());
                final CameraRenderState portalCamera = createPortalCamera(mainCamera, transform);
                if (portalCamera == null) {
                    continue;
                }

                final ViewTarget target = ensureTarget(portal, mainTarget);
                if (target == null) {
                    continue;
                }

                final CameraRenderState previous = levelState.cameraRenderState;
                boolean rendered = false;
                try {
                    levelState.cameraRenderState = portalCamera;
                    context.levelRenderer().render(resourcePool, false, portalCamera,
                            fogSlice, fogColor, false, false);
                    rendered = true;
                } catch (Throwable error) {
                    if (!failureLogged) {
                        failureLogged = true;
                        Crythst.LOGGER.warn("Crythst portal destination capture failed; "
                                + "falling back to the procedural surface", error);
                    }
                } finally {
                    levelState.cameraRenderState = previous;
                }

                if (rendered) {
                    target.target.copyColorFrom(mainTarget);
                    captured.add(portal.id());
                }
                count++;
            }
        } finally {
            capturing = false;
            READY.clear();
            READY.addAll(captured);
            fogRenderer.endFrame();
            resourcePool.endFrame();
        }
    }

    private static boolean isVisible(CameraRenderState camera, PortalSnapshot portal) {
        if (camera.pos.distanceToSqr(
                portal.plane().origin().x, portal.plane().origin().y, portal.plane().origin().z)
                > MAX_CAPTURE_DISTANCE_SQUARED) {
            return false;
        }
        if (camera.cullFrustum == null || !camera.initialized) {
            return true;
        }
        final var bounds = portal.plane().bounds();
        return camera.cullFrustum.isVisible(new AABB(
                bounds.min().x, bounds.min().y, bounds.min().z,
                bounds.max().x, bounds.max().y, bounds.max().z));
    }

    /**
     * Builds the camera that looks through the portal. The main camera is
     * transformed by the same rigid link transform used for entities, so the
     * destination view is exactly what the player would see from the exit.
     */
    private static CameraRenderState createPortalCamera(CameraRenderState main, PortalTransform transform) {
        final Vector3d position = transform.transformPoint(
                new Vector3d(main.pos.x, main.pos.y, main.pos.z));

        final CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(position.x, position.y, position.z);
        camera.blockPos = BlockPos.containing(camera.pos);
        camera.initialized = true;
        camera.isPanoramicMode = false;
        camera.isFrustumCaptured = false;
        camera.isFirstPerson = false;
        camera.smartCull = main.smartCull;
        camera.cameraEntityPartialTicks = main.cameraEntityPartialTicks;
        camera.fogType = main.fogType;
        camera.fogData = copyFog(main.fogData);
        camera.hudFov = main.hudFov;
        camera.depthFar = main.depthFar;
        camera.projectionMatrix = new Matrix4f(main.projectionMatrix);
        camera.entityRenderState = main.entityRenderState;

        final Quaternionf rotation = new Quaternionf(transform.rotation());
        final Quaternionf orientation = new Quaternionf(main.orientation).premul(rotation);
        camera.orientation = orientation;
        camera.viewRotationMatrix = new Matrix4f().rotation(new Quaternionf(orientation).conjugate());

        final Vector3f forward = orientation.transform(new Vector3f(0.0f, 0.0f, -1.0f));
        final float[] yawPitch = PortalTransform.yawPitch(
                new Vector3d(forward.x, forward.y, forward.z));
        camera.yRot = yawPitch[0];
        camera.xRot = yawPitch[1];

        final Frustum frustum = new Frustum(camera.viewRotationMatrix, camera.projectionMatrix);
        frustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        camera.cullFrustum = frustum;
        return camera;
    }

    private static void ensureResources() {
        if (resourcePool == null) {
            resourcePool = new CrossFrameResourcePool(3);
        }
        if (fogRenderer == null) {
            fogRenderer = new FogRenderer();
        }
    }

    private static ViewTarget ensureTarget(PortalSnapshot portal, RenderTarget mainTarget) {
        ViewTarget existing = TARGETS.get(portal.id());
        if (existing == null) {
            final Identifier textureId = Identifier.fromNamespaceAndPath(
                    Crythst.MOD_ID, "portal_view/" + portal.id());
            final PortalViewTexture texture = new PortalViewTexture(
                    "crythst_portal_view_" + portal.id(), mainTarget,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            existing = new ViewTarget(portal.id(), textureId, texture);
            TARGETS.put(portal.id(), existing);
        }

        if (existing.target == null
                || existing.target.width != mainTarget.width
                || existing.target.height != mainTarget.height) {
            final GpuFormat colorFormat = mainTarget.getColorTexture().getFormat();
            final GpuFormat depthFormat = mainTarget.getDepthTexture() == null
                    ? GpuFormat.D32_FLOAT
                    : mainTarget.getDepthTexture().getFormat();
            final TextureTarget target = new TextureTarget(
                    "crythst_portal_view_" + portal.id(),
                    mainTarget.width, mainTarget.height,
                    colorFormat, depthFormat);
            if (existing.target != null) {
                existing.target.destroyBuffers();
            }
            existing.target = target;
            existing.texture.setTarget(target,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }
        return existing;
    }

    private static FogData copyFog(FogData source) {
        final FogData copy = new FogData();
        if (source == null) {
            copy.color = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
            copy.environmentalStart = 0.0f;
            copy.environmentalEnd = 256.0f;
            copy.renderDistanceStart = 0.0f;
            copy.renderDistanceEnd = 256.0f;
            copy.skyEnd = 256.0f;
            copy.cloudEnd = 256.0f;
            return copy;
        }
        copy.environmentalStart = source.environmentalStart;
        copy.environmentalEnd = source.environmentalEnd;
        copy.renderDistanceStart = source.renderDistanceStart;
        copy.renderDistanceEnd = source.renderDistanceEnd;
        copy.skyEnd = source.skyEnd;
        copy.cloudEnd = source.cloudEnd;
        copy.color = new Vector4f(source.color);
        return copy;
    }

    private static void pruneTargets(Set<UUID> live) {
        final Iterator<Map.Entry<UUID, ViewTarget>> iterator = TARGETS.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, ViewTarget> entry = iterator.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            entry.getValue().close();
            iterator.remove();
        }
        READY.retainAll(live);
    }

    private static void close() {
        capturing = false;
        for (ViewTarget target : TARGETS.values()) {
            target.close();
        }
        TARGETS.clear();
        READY.clear();
        if (fogRenderer != null) {
            fogRenderer.close();
            fogRenderer = null;
        }
        if (resourcePool != null) {
            resourcePool.clear();
            resourcePool = null;
        }
    }

    private static final class ViewTarget {
        private final UUID portalId;
        private final Identifier textureId;
        private final PortalViewTexture texture;
        private TextureTarget target;

        private ViewTarget(UUID portalId, Identifier textureId, PortalViewTexture texture) {
            this.portalId = portalId;
            this.textureId = textureId;
            this.texture = texture;
        }

        private void close() {
            if (target != null) {
                target.destroyBuffers();
                target = null;
            }
        }
    }
}
