package io.mikaple.crythst.client.texture.runtime;

import com.mojang.blaze3d.platform.NativeImage;
import io.mikaple.crythst.client.texture.GrayscaleColorizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Creates tinted entity textures at runtime and registers them with the vanilla
 * {@link TextureManager}, without reloading resource packs.
 *
 * <h2>No-reload contract</h2>
 *
 * <p>Nothing in this class calls {@code Minecraft.reloadResourcePacks()},
 * {@link TextureManager#reload}, or {@code TextureManager.registerForNextReload}.
 * A generated texture is uploaded immediately and is visible on the next frame.
 * The resource manager is only used to <em>read</em> the grayscale PNG; reading a
 * resource does not start a reload.</p>
 *
 * <h2>Lifetime and memory</h2>
 *
 * <p>A {@link DynamicTexture} owns both a CPU-side {@link NativeImage} and a GPU
 * texture. {@link #get} keeps the registered texture alive until
 * {@link #clear()}/{@link #releaseAll()} is called, at which point the texture is
 * unregistered with {@link TextureManager#release(Identifier)} (which closes it).
 * GPU cost is {@code width * height * 4} bytes for the single mip level; the
 * CPU-side image costs the same again. Generated textures are not part of any
 * resource pack and are not persisted.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Texture creation and registration touch the GPU and therefore must run on
 * the Minecraft client thread, which is also the render thread. {@link #get}
 * enforces this. Other threads must use {@link #getAsync}; it submits the work to
 * the client thread with {@link Minecraft#submit}. The cache itself is a
 * {@link ConcurrentHashMap}, and creation is serialised by one lock so repeated
 * calls cannot register two textures for the same key.</p>
 *
 * <h2>26.3 details</h2>
 *
 * <p>{@link DynamicTexture}'s image constructor allocates a single-mip,
 * {@code RGBA8_UNORM} GPU texture and calls {@code upload()} itself exactly
 * once. Do not call {@code upload()} again. The source PNG is decoded by
 * {@link NativeImage#read(InputStream)}, which always yields an RGBA image;
 * tinting is done in ARGB (the convention of {@link GrayscaleColorizer}) and
 * written back with {@link NativeImage#setPixel(int, int, int)}, which converts
 * ARGB to the ABGR byte layout expected by the GPU upload.</p>
 */
public final class RuntimeTextureCache {

    /** Namespace used for every generated identifier. */
    public static final String DYNAMIC_NAMESPACE = "crythst";

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeTextureCache.class);

    private final ConcurrentMap<RuntimeTextureKey, Identifier> byKey = new ConcurrentHashMap<>();
    private final Object creationLock = new Object();

    /**
     * Returns the dynamic identifier for a one-colour tint, creating and
     * registering the texture on first use.
     *
     * <p>This is exactly the two-colour variant with the same primary and
     * outline colour, and shares its cache entry.</p>
     */
    public Identifier get(Identifier grayscaleSource, int primaryColor) {
        return get(grayscaleSource, primaryColor, primaryColor);
    }

    /**
     * Returns the dynamic identifier for a primary/outline tint, creating and
     * registering the texture on first use.
     *
     * <p>Repeated calls with the same source and colours return the same
     * {@code Identifier}; the texture is uploaded once. Must be called on the
     * Minecraft client/render thread.</p>
     *
     * @throws IllegalStateException if called from another thread or if the
     *                               source image cannot be loaded
     */
    public Identifier get(Identifier grayscaleSource, int primaryColor, int outlineColor) {
        Objects.requireNonNull(grayscaleSource, "grayscaleSource");
        requireClientThread("RuntimeTextureCache.get");

        final RuntimeTextureKey key = new RuntimeTextureKey(
                grayscaleSource.toString(), primaryColor, outlineColor);

        final Identifier cached = byKey.get(key);
        if (cached != null) {
            return cached;
        }

        synchronized (creationLock) {
            final Identifier raced = byKey.get(key);
            if (raced != null) {
                return raced;
            }

            final Identifier dynamicId = Identifier.fromNamespaceAndPath(
                    DYNAMIC_NAMESPACE, key.dynamicPath());
            final DynamicTexture texture = createTexture(grayscaleSource, key, dynamicId);
            try {
                Minecraft.getInstance().getTextureManager().register(dynamicId, texture);
            } catch (RuntimeException e) {
                // The texture was never owned by the manager, so close it here.
                texture.close();
                throw e;
            }
            byKey.put(key, dynamicId);
            return dynamicId;
        }
    }

    /**
     * Asynchronous variant of {@link #get}. If already on the client thread the
     * value is completed immediately; otherwise the work is submitted to the
     * client thread.
     */
    public CompletableFuture<Identifier> getAsync(Identifier grayscaleSource, int primaryColor) {
        return getAsync(grayscaleSource, primaryColor, primaryColor);
    }

    /**
     * Asynchronous variant of {@link #get(Identifier, int, int)}.
     */
    public CompletableFuture<Identifier> getAsync(Identifier grayscaleSource,
                                                  int primaryColor,
                                                  int outlineColor) {
        Objects.requireNonNull(grayscaleSource, "grayscaleSource");
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            return CompletableFuture.completedFuture(get(grayscaleSource, primaryColor, outlineColor));
        }
        return minecraft.submit(() -> get(grayscaleSource, primaryColor, outlineColor));
    }

    /**
     * Removes and closes every texture created by this cache.
     *
     * <p>Safe to call more than once. When called from another thread the
     * operation is scheduled on the client thread; use
     * {@link #releaseAll()} if a synchronous shutdown is required.</p>
     */
    public void clear() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clear);
            return;
        }
        releaseAll();
    }

    /**
     * Synchronously removes and closes every texture created by this cache.
     *
     * <p>Must be called on the client/render thread. Vanilla
     * {@link TextureManager#release(Identifier)} removes the entry and closes the
     * {@link DynamicTexture}; closing it a second time here would be redundant,
     * so this method only calls {@code release}.</p>
     */
    public void releaseAll() {
        requireClientThread("RuntimeTextureCache.releaseAll()");
        final TextureManager textureManager = Minecraft.getInstance().getTextureManager();

        synchronized (creationLock) {
            for (final Identifier dynamicId : byKey.values()) {
                try {
                    // release() calls safeClose() -> AbstractTexture.close().
                    textureManager.release(dynamicId);
                } catch (RuntimeException e) {
                    LOGGER.warn("Failed to release runtime texture {}", dynamicId, e);
                }
            }
            byKey.clear();
        }
    }

    /**
     * Releases one entry, if present. Returns {@code true} when a texture was
     * unregistered.
     */
    public boolean release(Identifier grayscaleSource, int primaryColor, int outlineColor) {
        Objects.requireNonNull(grayscaleSource, "grayscaleSource");
        requireClientThread("RuntimeTextureCache.release");

        final RuntimeTextureKey key = new RuntimeTextureKey(
                grayscaleSource.toString(), primaryColor, outlineColor);
        final Identifier dynamicId;
        synchronized (creationLock) {
            dynamicId = byKey.remove(key);
        }
        if (dynamicId == null) {
            return false;
        }
        try {
            Minecraft.getInstance().getTextureManager().release(dynamicId);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to release runtime texture {}", dynamicId, e);
            return false;
        }
    }

    /**
     * Releases one single-colour entry, if present.
     */
    public boolean release(Identifier grayscaleSource, int primaryColor) {
        return release(grayscaleSource, primaryColor, primaryColor);
    }

    /** Number of live entries. Intended for diagnostics and tests. */
    public int size() {
        return byKey.size();
    }

    /** Whether an entry for this source and colours is currently registered. */
    public boolean contains(Identifier grayscaleSource, int primaryColor, int outlineColor) {
        return byKey.containsKey(new RuntimeTextureKey(
                grayscaleSource.toString(), primaryColor, outlineColor));
    }

    private static DynamicTexture createTexture(Identifier sourceId,
                                                RuntimeTextureKey key,
                                                Identifier dynamicId) {
        final NativeImage source = loadImage(sourceId);
        NativeImage tinted = null;
        try {
            tinted = tint(source, key);
            final NativeImage uploaded = tinted;
            final Supplier<String> label = () -> "crythst runtime " + dynamicId;
            // The image constructor creates the RGBA8 GPU texture and calls
            // upload() once as part of construction; no second upload() here.
            return new DynamicTexture(label, uploaded);
        } catch (RuntimeException | Error e) {
            if (tinted != null) {
                tinted.close();
            }
            throw e;
        } finally {
            source.close();
        }
    }

    private static NativeImage tint(NativeImage source, RuntimeTextureKey key) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        // NativeImage.read() always returns Format.RGBA, so this is ARGB.
        final int[] sourcePixels = source.getPixels();
        final int[] tintedPixels = key.primaryColor() == key.outlineColor()
                ? GrayscaleColorizer.tint(sourcePixels, key.primaryColor())
                : GrayscaleColorizer.tintWithOutline(
                        sourcePixels, width, height, key.primaryColor(), key.outlineColor());

        // DynamicTexture always creates an RGBA8_UNORM GPU texture in 26.3.
        // Re-encode to the same format explicitly instead of assuming the
        // decoded image has a particular component layout.
        final NativeImage result = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                final int row = y * width;
                for (int x = 0; x < width; x++) {
                    result.setPixel(x, y, tintedPixels[row + x]);
                }
            }
            return result;
        } catch (RuntimeException | Error e) {
            result.close();
            throw e;
        }
    }

    private static NativeImage loadImage(Identifier sourceId) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Optional<Resource> resource = minecraft.getResourceManager().getResource(sourceId);
        if (resource.isPresent()) {
            try (InputStream in = resource.get().open()) {
                return NativeImage.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to read runtime texture source " + sourceId, e);
            }
        }

        // Fallback for resources visible to the class loader but not through the
        // client resource manager (for example a library jar). This does not
        // reload anything either.
        final String classpath = "assets/" + sourceId.getNamespace() + "/" + sourceId.getPath();
        try (InputStream in = RuntimeTextureCache.class.getClassLoader()
                .getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("Missing runtime texture source " + sourceId
                        + " (also tried classpath " + classpath + ")", new FileNotFoundException(
                        classpath));
            }
            return NativeImage.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read runtime texture source " + sourceId + " from classpath", e);
        }
    }

    private static void requireClientThread(String operation) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException(operation
                    + " must run on the Minecraft client/render thread; "
                    + "use the getAsync(...) overload from other threads");
        }
    }
}
