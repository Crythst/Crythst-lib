package io.mikaple.crythst.client.texture.runtime.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.mikaple.crythst.client.texture.runtime.RuntimeArmorTextures;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optional 26.3 diamond-armor example hook.
 *
 * <p>Vanilla's nine-argument {@code EquipmentLayerRenderer.renderLayers} always
 * forwards to an eleven-argument overload with a {@code null} explicit texture,
 * which makes that overload the texture-selection point. This redirect replaces
 * the forwarding call and supplies a runtime-generated identifier when
 * {@link RuntimeArmorTextures} is enabled. With the property unset,
 * {@link RuntimeArmorTextures#textureFor} returns {@code null}, so the forwarded
 * call is exactly the vanilla call.</p>
 *
 * <p>This mixin is registered through {@code crythst-texture.mixins.json}. The
 * config is non-required and its injectors are non-required, so a future target
 * change logs a warning instead of crashing. The JVM property remains the
 * independent switch that decides whether generated textures are used.</p>
 */
@Mixin(EquipmentLayerRenderer.class)
public abstract class EquipmentLayerRendererRuntimeTextureMixin {

    @Redirect(
            method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
                    + "Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/client/model/Model;"
                    + "Ljava/lang/Object;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;"
                            + "renderLayers("
                            + "Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
                            + "Lnet/minecraft/resources/ResourceKey;"
                            + "Lnet/minecraft/client/model/Model;"
                            + "Ljava/lang/Object;"
                            + "Lnet/minecraft/world/item/ItemStack;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                            + "ILnet/minecraft/resources/Identifier;II)V"
            )
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void crythst$useRuntimeArmorTexture(EquipmentLayerRenderer renderer,
                                                       EquipmentClientInfo.LayerType layerType,
                                                       ResourceKey<EquipmentAsset> assetId,
                                                       Model<?> model,
                                                       Object state,
                                                       ItemStack stack,
                                                       PoseStack poseStack,
                                                       SubmitNodeCollector collector,
                                                       int light,
                                                       Identifier texture,
                                                       int outlineColor,
                                                       int order) {
        final Identifier generated = RuntimeArmorTextures.textureFor(layerType, assetId);
        // The original call site passes null here; passing generated preserves
        // vanilla for every armor piece the example does not claim.
        renderer.renderLayers(layerType, assetId, (Model) model, state, stack, poseStack,
                collector, light, generated, outlineColor, order);
    }
}
