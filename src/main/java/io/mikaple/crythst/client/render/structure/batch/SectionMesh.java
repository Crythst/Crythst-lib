package io.mikaple.crythst.client.render.structure.batch;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.client.render.block.SpecialBlockRenderers;
import io.mikaple.crythst.client.render.light.VoxelLighting;
import io.mikaple.crythst.math.Transform3d;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3d;

import java.util.Arrays;

/**
 * One section's cached, section-local vertex soup for a single render layer.
 *
 * <p>There is no model, quad or sprite resolution left in the frame path: the
 * arrays already contain atlas UVs, tint colours (with baked vertex AO),
 * normals and emission. A frame only transforms the vertices by the section
 * pose and writes them to the {@link VertexConsumer}. The positions are local
 * to the 16x16x16 section, so float32 precision is excellent even when the
 * structure is far from the camera.</p>
 *
 * <p>Internal faces are removed while building by testing the adjacent cell in
 * the same body's occupancy grid (including across section boundaries). For
 * exposed faces, each quad remembers the adjacent voxel cell. The frame path
 * samples the world lightmap at that cell, at most once per quad and with a
 * shared per-frame budget, so lighting follows the structure rather than the
 * solid block centre. Vertex AO is baked into the colour at build time and
 * therefore costs nothing per frame.</p>
 *
 * <p>The {@link SubmitNodeCollector.CustomGeometryRenderer} is created once per
 * mesh and reuses this instance's {@code frameLight} field, so the per-frame
 * path does not allocate a lambda per batch. The mesh is submitted at most once
 * per frame on the render thread, which is why mutating the field immediately
 * before {@code submitCustomGeometry} is safe.</p>
 */
final class SectionMesh {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Re-sample an unchanged face every 16 frames so light updates are seen. */
    private static final int LIGHT_REFRESH_MASK = 15;
    /**
     * Re-check moved faces every 4 frames instead of every frame. World light
     * changes slowly and the structure pose moves smoothly, so this removes
     * three quarters of the per-quad world-position transforms without a
     * visible lighting lag.
     */
    private static final int LIGHT_POSITION_CHECK_MASK = 3;

    private final Vector3f scratchPosition = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();
    private final Vector3d scratchLight = new Vector3d();
    private final BlockPos.MutableBlockPos scratchLightPos = new BlockPos.MutableBlockPos();

    private final BlockGeometry.Layer layer;
    private float[] positions;
    private float[] uvs;
    private int[] colors;
    private byte[] lightEmission;
    private short[] normalIndex;
    /** Packed adjacent-cell position per quad, or {@link Long#MIN_VALUE}. */
    private long[] faceSample;
    /** Cached lightmap value per quad, or {@code -1} when not sampled yet. */
    private int[] faceLight;
    /** World cell the cached light was sampled from. */
    private long[] lightKey;
    private int vertexCount;
    private int quadCount;
    private final SubmitNodeCollector.CustomGeometryRenderer renderer = this::render;
    private float[] normalPalette = new float[18];
    private float[] frameNormals = new float[18];
    private int normalCount;
    private int frameLight;

    private SectionMesh(BlockGeometry.Layer layer, float[] positions, float[] uvs,
                        int[] colors, byte[] lightEmission, short[] normalIndex, int vertexCount) {
        this.layer = layer;
        this.positions = positions;
        this.uvs = uvs;
        this.colors = colors;
        this.lightEmission = lightEmission;
        this.normalIndex = normalIndex;
        this.vertexCount = vertexCount;
        this.quadCount = vertexCount / 4;
        this.faceSample = new long[quadCount];
        this.faceLight = new int[quadCount];
        this.lightKey = new long[quadCount];
        Arrays.fill(this.faceSample, Long.MIN_VALUE);
        Arrays.fill(this.faceLight, -1);
        Arrays.fill(this.lightKey, Long.MIN_VALUE);
    }

    /**
     * Builds all non-empty layer meshes for one section.
     *
     * <p>The input state array uses {@code (y * sizeZ + z) * sizeX + x} ordering
     * and only covers the part of the 16^3 section that intersects the snapshot
     * bounds. Thin sections therefore do not pay for a full 4096-entry array.
     * {@code view} provides the rest of the body for neighbour culling and AO,
     * and may be {@code null} when no structure context is available.</p>
     */
    static SectionMesh[] build(BlockState[] states, int sizeX, int sizeY, int sizeZ,
                               int originX, int originY, int originZ,
                               StructureVoxelView view) {
        return build(states, sizeX, sizeY, sizeZ, originX, originY, originZ, view, null);
    }

    /**
     * Builds all non-empty layer meshes for one section.
     *
     * @param enclosedOut optional one-element counter receiving the number of
     *                    full solid blocks skipped by enclosed-block culling
     */
    static SectionMesh[] build(BlockState[] states, int sizeX, int sizeY, int sizeZ,
                               int originX, int originY, int originZ,
                               StructureVoxelView view, int[] enclosedOut) {
        final boolean anySpecial = !SpecialBlockRenderers.isEmpty();
        final BlockGeometry[] geometries = new BlockGeometry[states.length];
        final int[] vertexCounts = new int[3];
        final int layerStride = sizeX * sizeZ;
        for (int index = 0; index < states.length; index++) {
            final BlockState state = states[index];
            if (state == null || state.isAir()
                    || (anySpecial && SpecialBlockRenderers.isRegistered(state.getBlock()))) {
                continue;
            }
            final int x = index % sizeX;
            final int z = (index / sizeX) % sizeZ;
            final int y = index / layerStride;
            if (SectionCulling.isFullyEnclosed(view, state,
                    originX + x, originY + y, originZ + z)) {
                if (enclosedOut != null) {
                    enclosedOut[0]++;
                }
                continue;
            }
            final BlockGeometry geometry = BlockGeometryCache.get(state);
            if (geometry.empty) {
                continue;
            }
            geometries[index] = geometry;
            for (BlockGeometry.Layer layer : BlockGeometry.LAYERS) {
                vertexCounts[layer.index()] += geometry.quads(layer).vertexCount();
            }
        }

        final SectionMesh[] meshes = new SectionMesh[3];
        for (BlockGeometry.Layer layer : BlockGeometry.LAYERS) {
            final int count = vertexCounts[layer.index()];
            if (count > 0) {
                meshes[layer.index()] = new SectionMesh(layer,
                        new float[count * 3], new float[count * 2],
                        new int[count], new byte[count], new short[count], count);
            }
        }

        final int[] cursors = new int[3];
        for (int index = 0; index < states.length; index++) {
            final BlockGeometry geometry = geometries[index];
            if (geometry == null) {
                continue;
            }
            final BlockState state = states[index];
            final int x = index % sizeX;
            final int z = (index / sizeX) % sizeZ;
            final int y = index / layerStride;
            final int worldX = originX + x;
            final int worldY = originY + y;
            final int worldZ = originZ + z;
            // AO only makes sense for full-cube geometry. Stairs, fences,
            // plants and other shaped models stay flat-shaded instead of
            // producing corner darkness in the middle of their own faces.
            final boolean aoEligible = state.isSolidRender();

            for (BlockGeometry.Layer layer : BlockGeometry.LAYERS) {
                final int layerIndex = layer.index();
                final SectionMesh mesh = meshes[layerIndex];
                if (mesh == null) {
                    continue;
                }
                final BlockGeometry.Quads quads = geometry.quads(layer);
                int cursor = cursors[layerIndex];
                for (int quad = 0; quad < quads.quadCount; quad++) {
                    if (isCulled(view, state, quads, quad, worldX, worldY, worldZ)) {
                        continue;
                    }
                    final byte face = quads.faces[quad];
                    final Direction direction = face < 0 ? null : DIRECTIONS[face];
                    final int sourcePosition = quad * 12;
                    final int sourceUv = quad * 8;
                    final int color = quads.colors[quad];
                    final byte emission = (byte) Math.min(15, quads.lightEmission[quad]);
                    final short normal = (short) mesh.internNormal(
                            quads.normals[sourcePosition],
                            quads.normals[sourcePosition + 1],
                            quads.normals[sourcePosition + 2]);
                    final int quadIndex = cursor >>> 2;
                    mesh.faceSample[quadIndex] = direction == null
                            ? Long.MIN_VALUE
                            : BlockPos.asLong(
                                    worldX + direction.getStepX(),
                                    worldY + direction.getStepY(),
                                    worldZ + direction.getStepZ());
                    for (int vertex = 0; vertex < 4; vertex++) {
                        final int source = sourcePosition + vertex * 3;
                        final int destination = cursor * 3;
                        mesh.positions[destination] = quads.positions[source] + x;
                        mesh.positions[destination + 1] = quads.positions[source + 1] + y;
                        mesh.positions[destination + 2] = quads.positions[source + 2] + z;

                        mesh.uvs[cursor * 2] = quads.uvs[sourceUv + vertex * 2];
                        mesh.uvs[cursor * 2 + 1] = quads.uvs[sourceUv + vertex * 2 + 1];

                        mesh.normalIndex[cursor] = normal;
                        mesh.colors[cursor] = aoEligible && direction != null
                                ? VoxelLighting.applyShade(color,
                                        vertexShade(view, direction, worldX, worldY, worldZ,
                                                quads.positions, sourcePosition, vertex))
                                : color;
                        mesh.lightEmission[cursor] = emission;
                        cursor++;
                    }
                }
                cursors[layerIndex] = cursor;
            }
        }
        for (BlockGeometry.Layer layer : BlockGeometry.LAYERS) {
            final SectionMesh mesh = meshes[layer.index()];
            if (mesh == null) {
                continue;
            }
            mesh.finish(cursors[layer.index()]);
            if (mesh.vertexCount() == 0) {
                meshes[layer.index()] = null;
            }
        }
        return meshes;
    }

    /**
     * Removes a quad whose outward face is fully covered by the adjacent
     * structure cell.
     *
     * <p>The stored face is the quad's own outward facing (never the direction
     * bucket it came from), and {@code -1} means the model declared the quad
     * unculled. {@link Block#shouldRenderFace} is used exactly like vanilla's
     * chunk mesher: it compares the two states' face occlusion shapes, so a
     * partial neighbour (slab, stair, fence, glass) only hides a face when it
     * actually covers it. Only cells inside this body are considered; outside
     * the snapshot reads as air and never culls.</p>
     */
    static boolean isCulled(StructureVoxelView view, BlockState state,
                            BlockGeometry.Quads quads, int quad,
                            int worldX, int worldY, int worldZ) {
        if (view == null || state == null) {
            return false;
        }
        final byte face = quads.faces[quad];
        if (face < 0) {
            return false;
        }
        final Direction direction = DIRECTIONS[face];
        final BlockState neighbour = view.stateAt(
                worldX + direction.getStepX(),
                worldY + direction.getStepY(),
                worldZ + direction.getStepZ());
        if (neighbour == null || neighbour.isAir()) {
            return false;
        }
        // Vanilla's exact rule: compare this state's outward face occlusion
        // shape with the neighbour's opposite face occlusion shape. This is
        // what makes slabs, stairs, fences and glass partial occlusion work.
        return !Block.shouldRenderFace(state, neighbour, direction);
    }

    BlockGeometry.Layer layer() {
        return layer;
    }

    int vertexCount() {
        return vertexCount;
    }

    void setFrameLight(int frameLight) {
        this.frameLight = frameLight;
    }

    /**
     * Updates the per-quad lightmap cache. Sampling happens at the world cell
     * just outside the face, not at the (usually solid) block centre. A face is
     * only re-sampled when the structure moved it into another world cell, when
     * it has no cached value yet, or on a slow 16-frame refresh. The shared
     * {@code budget[0]} caps light lookups per frame.
     */
    void updateFaceLights(Level level, Transform3d pose, double blockScale,
                          long frame, int[] budget) {
        if (level == null || pose == null || budget[0] <= 0) {
            return;
        }
        final double scale = blockScale;
        for (int quad = 0; quad < quadCount; quad++) {
            final long sample = faceSample[quad];
            if (sample == Long.MIN_VALUE) {
                continue;
            }
            final boolean sampled = lightKey[quad] != Long.MIN_VALUE;
            if (sampled && !isLightPositionCheckFrame(frame, quad)) {
                continue;
            }
            scratchLight.set(
                    (BlockPos.getX(sample) + 0.5) * scale,
                    (BlockPos.getY(sample) + 0.5) * scale,
                    (BlockPos.getZ(sample) + 0.5) * scale);
            pose.transformPoint(scratchLight, scratchLight);
            final long key = BlockPos.asLong(
                    Mth.floor(scratchLight.x),
                    Mth.floor(scratchLight.y),
                    Mth.floor(scratchLight.z));
            if (lightKey[quad] == key && !isLightRefreshFrame(frame, quad)) {
                continue;
            }
            if (budget[0] <= 0) {
                // Keep the previous value (or section fallback) until a later
                // frame has budget. The key is intentionally not committed so
                // a moved face is retried.
                continue;
            }
            budget[0]--;
            scratchLightPos.set(key);
            faceLight[quad] = BatchedStructureRenderer.sampleWorldLight(level, scratchLightPos);
            lightKey[quad] = key;
        }
    }

    static boolean isLightPositionCheckFrame(long frame, int quad) {
        // Use the same per-quad phase for both intervals so every scheduled
        // light refresh also gets a position check.
        return ((frame + quad) & LIGHT_POSITION_CHECK_MASK) == 0L;
    }

    static boolean isLightRefreshFrame(long frame, int quad) {
        return ((frame + quad) & LIGHT_REFRESH_MASK) == 0L;
    }

    SubmitNodeCollector.CustomGeometryRenderer renderer() {
        return renderer;
    }

    /**
     * Trims build-time capacity after face culling removed hidden quads.
     */
    private void finish(int actualVertexCount) {
        if (actualVertexCount == vertexCount) {
            return;
        }
        final int actualQuads = actualVertexCount / 4;
        vertexCount = actualVertexCount;
        quadCount = actualQuads;
        positions = Arrays.copyOf(positions, actualVertexCount * 3);
        uvs = Arrays.copyOf(uvs, actualVertexCount * 2);
        colors = Arrays.copyOf(colors, actualVertexCount);
        lightEmission = Arrays.copyOf(lightEmission, actualVertexCount);
        normalIndex = Arrays.copyOf(normalIndex, actualVertexCount);
        faceSample = Arrays.copyOf(faceSample, actualQuads);
        faceLight = Arrays.copyOf(faceLight, actualQuads);
        lightKey = Arrays.copyOf(lightKey, actualQuads);
    }

    /**
     * Classic voxel AO for one quad vertex. The two tangent axes of the face
     * select the side neighbours around the adjacent air cell; the diagonal is
     * the shared corner. Vertex signs come from the baked quad position, so the
     * four vertices of a cube face get the four expected corners.
     */
    private static float vertexShade(StructureVoxelView view, Direction direction,
                                     int worldX, int worldY, int worldZ,
                                     float[] positions, int sourcePosition, int vertex) {
        final Direction.Axis axis = direction.getAxis();
        final Direction.Axis u;
        final Direction.Axis v;
        switch (axis) {
            case X -> {
                u = Direction.Axis.Z;
                v = Direction.Axis.Y;
            }
            case Y -> {
                u = Direction.Axis.X;
                v = Direction.Axis.Z;
            }
            default -> {
                u = Direction.Axis.X;
                v = Direction.Axis.Y;
            }
        }
        final int offset = sourcePosition + vertex * 3;
        final int uSign = positions[offset + axisIndex(u)] > 0.5f ? 1 : -1;
        final int vSign = positions[offset + axisIndex(v)] > 0.5f ? 1 : -1;

        final int baseX = worldX + direction.getStepX();
        final int baseY = worldY + direction.getStepY();
        final int baseZ = worldZ + direction.getStepZ();
        final int ux = axisStepX(u) * uSign;
        final int uy = axisStepY(u) * uSign;
        final int uz = axisStepZ(u) * uSign;
        final int vx = axisStepX(v) * vSign;
        final int vy = axisStepY(v) * vSign;
        final int vz = axisStepZ(v) * vSign;

        final boolean side1 = view.isOccluded(baseX + ux, baseY + uy, baseZ + uz);
        final boolean side2 = view.isOccluded(baseX + vx, baseY + vy, baseZ + vz);
        final boolean corner = side1 && side2
                || view.isOccluded(baseX + ux + vx, baseY + uy + vy, baseZ + uz + vz);
        return VoxelLighting.aoShade(VoxelLighting.aoLevel(side1, side2, corner));
    }

    private static int axisIndex(Direction.Axis axis) {
        return switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
    }

    private static int axisStepX(Direction.Axis axis) {
        return axis == Direction.Axis.X ? 1 : 0;
    }

    private static int axisStepY(Direction.Axis axis) {
        return axis == Direction.Axis.Y ? 1 : 0;
    }

    private static int axisStepZ(Direction.Axis axis) {
        return axis == Direction.Axis.Z ? 1 : 0;
    }

    /**
     * Deduplicates a normal into this mesh's small palette. Most sections only
     * contain the six axis directions, so the frame path transforms a handful
     * of normals instead of one per vertex.
     */
    private int internNormal(float nx, float ny, float nz) {
        for (int i = 0; i < normalCount; i++) {
            final int offset = i * 3;
            if (normalPalette[offset] == nx
                    && normalPalette[offset + 1] == ny
                    && normalPalette[offset + 2] == nz) {
                return i;
            }
        }
        if ((normalCount + 1) * 3 > normalPalette.length) {
            final int capacity = Math.max(6, normalCount + 6);
            normalPalette = Arrays.copyOf(normalPalette, capacity * 3);
            frameNormals = Arrays.copyOf(frameNormals, capacity * 3);
        }
        final int offset = normalCount * 3;
        normalPalette[offset] = nx;
        normalPalette[offset + 1] = ny;
        normalPalette[offset + 2] = nz;
        return normalCount++;
    }

    private void render(PoseStack.Pose pose, VertexConsumer consumer) {
        final Matrix4f transform = pose.pose();
        final Matrix3f normalMatrix = pose.normal();
        final Vector3f position = scratchPosition;
        final Vector3f normal = scratchNormal;
        final int fallbackLight = frameLight;

        // Rotate the small normal palette once; vertices then only index it.
        final float[] palette = normalPalette;
        final float[] frame = frameNormals;
        for (int i = 0; i < normalCount; i++) {
            final int offset = i * 3;
            normalMatrix.transform(palette[offset], palette[offset + 1], palette[offset + 2], normal);
            frame[offset] = normal.x;
            frame[offset + 1] = normal.y;
            frame[offset + 2] = normal.z;
        }

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            transform.transformPosition(positions[vertex * 3], positions[vertex * 3 + 1],
                    positions[vertex * 3 + 2], position);

            final int normalOffset = (normalIndex[vertex] & 0xFFFF) * 3;
            final int cachedLight = faceLight[vertex >>> 2];
            final int light = cachedLight >= 0 ? cachedLight : fallbackLight;
            consumer.addVertex(position.x, position.y, position.z,
                    colors[vertex],
                    uvs[vertex * 2], uvs[vertex * 2 + 1],
                    OverlayTexture.NO_OVERLAY,
                    LightCoordsUtil.lightCoordsWithEmission(light, lightEmission[vertex] & 0xFF),
                    frame[normalOffset], frame[normalOffset + 1], frame[normalOffset + 2]);
        }
    }
}
