package net.minecraft.client.renderer.rendertype;

/**
 * Bridge giving Crythst access to package-private
 * {@code RenderType.create(String, RenderSetup)}.
 *
 * <p>This class deliberately lives in
 * {@code net.minecraft.client.renderer.rendertype} so that it shares the package
 * of {@link RenderType} and may call it directly. Mod classes and Minecraft are
 * loaded by the same Knot class loader, so at runtime they belong to the same
 * runtime package and package-private access is legal.</p>
 *
 * <p>This is the same technique used by EnderTech. It avoids a Mixin accessor
 * or reflection. (Fabric API's transitive access wideners also widen this
 * method; this helper keeps the technique self-contained and independent of
 * that widening.)</p>
 */
public final class CrythstRenderTypes {

    private CrythstRenderTypes() {
    }

    /**
     * Creates a {@link RenderType} from a prepared {@link RenderSetup}.
     *
     * @param name  unique render type name, for example {@code crythst_star}
     * @param setup pipeline, textures, lightmap/overlay and outline properties
     * @return the created render type
     */
    public static RenderType create(String name, RenderSetup setup) {
        return RenderType.create(name, setup);
    }
}
