package io.mikaple.crythst.physics.world;

/**
 * Marker user-data for static bodies generated from Minecraft terrain.
 *
 * <p>Debug renderers can use this to avoid drawing every terrain section as a
 * regular physics body.</p>
 */
public final class TerrainBody {
    public static final TerrainBody INSTANCE = new TerrainBody();

    private TerrainBody() {
    }

    @Override
    public String toString() {
        return "TerrainBody";
    }
}
