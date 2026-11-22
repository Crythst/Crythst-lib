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
 * A {@link SpecialModelRenderer} that renders a normal item model and then
 * overlays the star effect on the model's opaque black "window" texels.
 *
 * <p>The renderer deliberately owns <b>two</b> submissions of the same baked
 * geometry instead of replacing the model's render types:</p>
 * <ol>
 *     <li><b>Real model pass.</b> The quads keep their original
 *     {@link BakedQuad.MaterialInfo#itemRenderType()} (cutout or translucent
 *     item sheet), glint render types, tint, lightmap and depth state. This
 *     pass is, pixel for pixel, an ordinary item model and writes the depth
 *     that the second pass tests against.</li>
 *     <li><b>Star mask pass.</b> The same quads are submitted with the star
 *     shader. The shader discards every texel that is not part of the effect
 *     mask, so it only replaces opaque near-black pixels. Its pipeline never
 *     writes depth and uses {@link StarRenderPlan#STAR_DEPTH_TEST}; the real
 *     model's depth buffer therefore acts as the silhouette mask and stars
 *     cannot draw through the item, other items, or world geometry.</li>
 * </ol>
 *
 * <p>See {@link StarRenderPlan} for the phase/order rules and
 * {@link StarMaskMath} for the tested mask/projection math.</p>
 *
 * <p>Registered as the {@code crythst:star} special model type by
 * {@link CrythstItemSpecialRenderers#register()}.</p>
 */
public class StarItemModelRenderer implements SpecialModelRenderer<Item> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StarItemModelRenderer.class);

    /** Original quads, used for extents and as the source of both passes. */
    private final List<BakedQuad> quads;
    /** Real-model quads grouped by whether their vanilla render type blends. */
    private final ItemQuads realQuads;
    /** The same geometry with the star overlay render types. */
    private final ItemQuads starQuads;

    public StarItemModelRenderer(List<BakedQuad> quads) {
        this.quads = List.copyOf(quads);
        this.realQuads = ItemQuads.split(this.quads);
        this.starQuads = ItemQuads.split(withStarShader(this.quads));
    }

    @Override
    public void submit(@Nullable Item argument, @NonNull PoseStack poseStack, @NonNull SubmitNodeCollector submitNodeCollector, int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        // Pass 1: the ordinary model. Keep its render types, glint and depth
        // state untouched. `FoilType.STANDARD` selects the vanilla glint render
        // types, so enchantment glint behaves exactly as on a non-star model.
        submitNodeCollector.submitItem(
                poseStack,
                // A special model does not know the current ItemDisplayContext;
                // the geometry transform has already been applied by LayerRenderState.
                ItemDisplayContext.NONE,
                lightCoords,
                overlayCoords,
                outlineColor,
                ItemStackRenderState.LayerRenderState.EMPTY_TINTS,
                this.realQuads,
                hasFoil ? ItemStackRenderState.FoilType.STANDARD : ItemStackRenderState.FoilType.NONE
        );

        // Pass 2: the star mask overlay. It uses the very same geometry and
        // pose, so its depth values match pass 1 exactly. No depth write: the
        // real model's depth buffer is the silhouette mask. No outline and no
        // foil: the real pass already contributed both.
        submitNodeCollector.submitItem(
                poseStack,
                ItemDisplayContext.NONE,
                lightCoords,
                overlayCoords,
                0,
                ItemStackRenderState.LayerRenderState.EMPTY_TINTS,
                this.starQuads,
                ItemStackRenderState.FoilType.NONE
        );
    }

    @Override
    public @Nullable Item extractArgument(@NonNull ItemStack stack) {
        return stack.getItem();
    }

    /**
     * Rewrites the quads baked from a normal model so a second submission can
     * render the star overlay.
     *
     * <p>Only {@link BakedQuad.MaterialInfo#itemRenderType()} changes. The
     * overlay type is chosen per quad from the original render type, so cutout
     * quads stay in the solid phase and translucent quads stay in the
     * translucent phase; the overlay therefore always runs after the real
     * model. The glint render types are left untouched because the overlay is
     * submitted with {@link ItemStackRenderState.FoilType#NONE}.</p>
     */
    private static List<BakedQuad> withStarShader(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return quads;
        }

        return quads.stream()
                .map(quad -> {
                    BakedQuad.MaterialInfo materialInfo = quad.materialInfo();
                    boolean translucent = StarRenderPlan.star(
                            materialInfo.itemRenderType().hasBlending()).blended();
                    RenderType starType = StarRenderTypes.forAtlas(
                            materialInfo.sprite().atlasLocation(), translucent);
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
            List<BakedQuad> quads = quadCollection.getAll();

            if (quads.isEmpty()) {
                LOGGER.warn("Star model {} baked zero quads, the item will be invisible", this.model);
            } else {
                LOGGER.info("Star model {} baked {} quads", this.model, quads.size());
            }
            return new StarItemModelRenderer(quads);
        }
    }
}
