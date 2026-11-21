package io.mikaple.crythst.client.texture.runtime;

import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.equipment.EquipmentAsset;

/**
 * Opt-in example that applies a runtime-tinted grayscale texture to diamond
 * armor.
 *
 * <p>This class is intentionally <strong>disabled by default</strong>. It exists
 * to demonstrate the 26.3 integration point and is used by the optional
 * {@code EquipmentLayerRendererRuntimeTextureMixin}. Nothing happens unless the
 * JVM property {@code -Dcrythst.runtimeTextures.diamondArmor=true} is set.
 * The mixin config itself is registered in this repository's
 * {@code fabric.mod.json}, but it has no visible effect while the property is
 * unset.</p>
 *
 * <p>The example uses the bundled test masks:</p>
 * <ul>
 *     <li>{@code humanoid.png} for {@code HUMANOID} / {@code HUMANOID_BABY}</li>
 *     <li>{@code humanoid_leggings.png} for {@code HUMANOID_LEGGINGS}</li>
 * </ul>
 *
 * <p>Production code should replace {@link #textureFor} with its own source
 * texture and palette selection. The important part is the call shape: return a
 * generated {@link Identifier} (or {@code null} to leave vanilla untouched) for
 * the {@code EquipmentClientInfo.LayerType} being submitted.</p>
 */
public final class RuntimeArmorTextures {

    /** Set to {@code true} to enable the demo hook. */
    public static final String ENABLE_PROPERTY = "crythst.runtimeTextures.diamondArmor";

    private static final Identifier DIAMOND_ASSET =
            Identifier.fromNamespaceAndPath("minecraft", "diamond");
    private static final Identifier HUMANOID_MASK =
            Identifier.fromNamespaceAndPath("crythst",
                    "textures/test/grayscale/humanoid.png");
    private static final Identifier LEGGINGS_MASK =
            Identifier.fromNamespaceAndPath("crythst",
                    "textures/test/grayscale/humanoid_leggings.png");

    private static final int PRIMARY_COLOR = 0xFF55DDDD;
    private static final int OUTLINE_COLOR = 0xFF0B6666;

    private RuntimeArmorTextures() {
    }

    /** Whether the demo hook is enabled for this JVM. */
    public static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    /**
     * Returns the generated armor texture for a diamond equipment layer, or
     * {@code null} when the hook is disabled or the layer is not a humanoid
     * armor layer.
     *
     * <p>Called from the render thread, from the equipment layer submit path.</p>
     */
    public static Identifier textureFor(EquipmentClientInfo.LayerType layerType,
                                        ResourceKey<EquipmentAsset> assetId) {
        if (!enabled() || layerType == null || assetId == null
                || !DIAMOND_ASSET.equals(assetId.identifier())) {
            return null;
        }

        return switch (layerType) {
            case HUMANOID, HUMANOID_BABY -> EntityGrayscaleTextures.textureFor(
                    HUMANOID_MASK, PRIMARY_COLOR, OUTLINE_COLOR);
            case HUMANOID_LEGGINGS -> EntityGrayscaleTextures.textureFor(
                    LEGGINGS_MASK, PRIMARY_COLOR, OUTLINE_COLOR);
            default -> null;
        };
    }
}
