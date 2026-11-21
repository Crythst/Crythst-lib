package io.mikaple.crythst.client.render.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link SpecialModelRenderer} that bakes an ordinary item model and renders
 * all of its quads with the custom star shader.
 *
 * <p>The item model's geometry, UVs and display transforms still come from the
 * referenced base model; only the {@link RenderType} stored on each quad is
 * swapped. The shader then interprets fully transparent pixels as an animated
 * rim and pure-black pixels as a recessed star field (see
 * {@code assets/crythst/shaders/core/star.fsh}).</p>
 *
 * <p>Registered as the {@code crythst:star} special model type by
 * {@link CrythstItemSpecialRenderers#register()}.</p>
 */
public class StarItemModelRenderer implements SpecialModelRenderer<Item> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StarItemModelRenderer.class);

    private final List<BakedQuad> quads;
    private final ItemQuads itemQuads;

    public StarItemModelRenderer(List<BakedQuad> quads) {
        this.quads = quads;
        this.itemQuads = ItemQuads.split(quads);
    }

    @Override
    public void submit(@Nullable Item argument, @NonNull PoseStack poseStack, @NonNull SubmitNodeCollector submitNodeCollector, int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        submitNodeCollector.submitItem(
                poseStack,
                // A special model does not know the current ItemDisplayContext; the
                // geometry transform has already been applied by LayerRenderState.
                ItemDisplayContext.NONE,
                lightCoords,
                overlayCoords,
                outlineColor,
                ItemStackRenderState.LayerRenderState.EMPTY_TINTS,
                this.itemQuads,
                hasFoil ? ItemStackRenderState.FoilType.STANDARD : ItemStackRenderState.FoilType.NONE
        );
    }

    @Override
    public @Nullable Item extractArgument(@NonNull ItemStack stack) {
        return stack.getItem();
    }

    /**
     * Rewrites the quads baked from a normal model so they render with the star
     * shader.
     *
     * <p>During item rendering the RenderType is chosen per quad from
     * {@link BakedQuad.MaterialInfo#itemRenderType()}, so a fresh MaterialInfo is
     * built with only that field replaced. The glint render types and every other
     * material field are preserved.</p>
     */
    private static List<BakedQuad> withStarShader(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return quads;
        }

        RenderType starType = StarRenderTypes.forAtlas(quads.getFirst().materialInfo().sprite().atlasLocation());
        return quads.stream()
                .map(quad -> {
                    BakedQuad.MaterialInfo materialInfo = quad.materialInfo();
                    BakedQuad.MaterialInfo starMaterialInfo = new BakedQuad.MaterialInfo(
                            materialInfo.sprite(),
                            materialInfo.layer(),
                            starType,
                            materialInfo.itemGlintRenderType(),
                            materialInfo.itemGlintSpecialRenderType(),
                            materialInfo.tintIndex(),
                            materialInfo.shadeDirectionOverride(),
                            materialInfo.lightEmission()
                    );
                    return new BakedQuad(
                            quad.position0(), quad.position1(), quad.position2(), quad.position3(),
                            quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
                            quad.direction(),
                            starMaterialInfo
                    );
                })
                .toList();
    }

    @Override
    public void getExtents(@NonNull Consumer<Vector3fc> output) {
        for (BakedQuad quad : this.quads) {
            for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                output.accept(quad.position(vertex));
            }
        }
    }

    /**
     * The unbaked form of the special model: it bakes an ordinary model into
     * {@link BakedQuad}s during model baking.
     *
     * <p>The {@link BakingContext} passed in by the {@code minecraft:special}
     * wrapper is actually an {@link ItemModel.BakingContext}; only through it can
     * a {@link ModelBaker} (the sole entry point for quads) be obtained.</p>
     *
     * @param model the ordinary model to render, for example
     *              {@code crythst:item/example_ingot}
     */
    public record Unbaked(Identifier model) implements SpecialModelRenderer.Unbaked<Item> {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        Identifier.CODEC.fieldOf("model").forGetter(Unbaked::model)
                ).apply(instance, Unbaked::new));

        @Override
        public @NonNull MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<Item> bake(@NonNull BakingContext context) {
            if (!(context instanceof ItemModel.BakingContext itemContext)) {
                LOGGER.error("Baking context is not an ItemModel.BakingContext but {}, cannot get quads for {}",
                        context.getClass().getName(), this.model);
                return null;
            }

            ModelBaker baker = itemContext.blockModelBaker();
            ResolvedModel resolvedModel = baker.getModel(this.model);
            TextureSlots textureSlots = resolvedModel.getTopTextureSlots();
            QuadCollection quadCollection = resolvedModel.bakeTopGeometry(textureSlots, baker, BlockModelRotation.IDENTITY);
            List<BakedQuad> quads = withStarShader(quadCollection.getAll());

            if (quads.isEmpty()) {
                LOGGER.warn("Star model {} baked zero quads, the item will be invisible", this.model);
            } else {
                LOGGER.info("Star model {} baked {} quads", this.model, quads.size());
            }
            return new StarItemModelRenderer(quads);
        }
    }
}
