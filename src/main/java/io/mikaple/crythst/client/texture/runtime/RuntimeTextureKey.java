package io.mikaple.crythst.client.texture.runtime;

import io.mikaple.crythst.client.texture.GrayscalePalette;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Cache key and stable dynamic identifier path for one generated entity texture.
 *
 * <p>The key is deliberately a plain string plus two integers, not an
 * {@code Identifier}, so it can be unit-tested without Minecraft on the class
 * path. {@link RuntimeTextureCache} converts the real identifier to
 * {@link String} once.</p>
 *
 * <p>Both colours are normalised through {@link GrayscalePalette}: an
 * {@code 0xRRGGBB} literal and its opaque {@code 0xFFRRGGBB} equivalent describe
 * the same output, so they must also describe the same cache entry. The palette
 * alpha is never used by the tinting formula; output alpha always comes from the
 * grayscale source.</p>
 *
 * @param sourceId     identifier of the grayscale source, for example
 *                     {@code crythst:textures/test/grayscale/humanoid.png}
 * @param primaryColor primary fill colour, normalised to opaque ARGB
 * @param outlineColor outline colour, normalised to opaque ARGB
 */
public record RuntimeTextureKey(String sourceId, int primaryColor, int outlineColor) {

    /** Prefix used by every identifier created by {@link RuntimeTextureCache}. */
    public static final String DYNAMIC_PATH_PREFIX = "dynamic/entity/";

    public RuntimeTextureKey {
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }

        final GrayscalePalette palette = GrayscalePalette.of(primaryColor, outlineColor);
        primaryColor = palette.primaryArgb();
        outlineColor = palette.outlineArgb();
    }

    /**
     * Creates a key for a single-colour tint.
     */
    public static RuntimeTextureKey of(String sourceId, int color) {
        return new RuntimeTextureKey(sourceId, color, color);
    }

    /**
     * Creates a key for a two-colour primary/outline tint.
     */
    public static RuntimeTextureKey of(String sourceId, int primaryColor, int outlineColor) {
        return new RuntimeTextureKey(sourceId, primaryColor, outlineColor);
    }

    /**
     * Returns the path part of the dynamic identifier, without namespace.
     *
     * <p>The path is a SHA-256 digest of the source identifier and both
     * normalised colours. It is deterministic across JVMs and cache lifetimes,
     * so asking for the same source and colours after a {@code clear()} produces
     * the same identifier again. The digest is hex-encoded and therefore always
     * a valid {@code Identifier} path.</p>
     */
    public String dynamicPath() {
        return DYNAMIC_PATH_PREFIX + digestHex();
    }

    private String digestHex() {
        final String material = sourceId + '\u0000'
                + Integer.toHexString(primaryColor) + '\u0000'
                + Integer.toHexString(outlineColor);
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java platform specification.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
