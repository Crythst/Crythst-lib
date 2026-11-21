package io.mikaple.crythst.client.render.structure.batch;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.mikaple.crythst.client.render.block.SpecialBlockRenderers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * One section's cached, section-local vertex soup for a single render layer.
 *
 * <p>There is no model, quad or sprite resolution left in the frame path: the
 * arrays already contain atlas UVs, tint colours, normals and emission. A frame
 * only transforms the vertices by the section pose and writes them to the
 * {@link VertexConsumer}. The positions are local to the 16x16x16 section, so
 * float32 precision is excellent even when the structure is far from the
 * camera.</p>
 *
 * <p>The {@link SubmitNodeCollector.CustomGeometryRenderer} is created once per
 * mesh and reuses this instance's {@code frameLight} field, so the per-frame
 * path does not allocate a lambda per batch. The mesh is submitted at most once
 * per frame on the render thread, which is why mutating the field immediately
 * before {@code submitCustomGeometry} is safe.</p>
 */
final class SectionMesh {
    private final Vector3f scratchPosition = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();

    private final BlockGeometry.Layer layer;
    private final float[] positions;
    private final float[] uvs;
    private final int[] colors;
    private final byte[] lightEmission;
    private final short[] normalIndex;
    private final int vertexCount;
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
    }

    /**
     * Builds all non-empty layer meshes for one section.
     *
     * <p>The input state array uses {@code (y * sizeZ + z) * sizeX + x} ordering
     * and only covers the part of the 16^3 section that intersects the snapshot
     * bounds. Thin sections therefore do not pay for a full 4096-entry array.</p>
     */
    static SectionMesh[] build(BlockState[] states, int sizeX, int sizeY, int sizeZ) {
        final boolean anySpecial = !SpecialBlockRenderers.isEmpty();
        final BlockGeometry[] geometries = new BlockGeometry[states.length];
        final int[] vertexCounts = new int[3];
        for (int index = 0; index < states.length; index++) {
            final BlockState state = states[index];
            if (state == null || state.isAir()
                    || (anySpecial && SpecialBlockRenderers.isRegistered(state.getBlock()))) {
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
        final int layerStride = sizeX * sizeZ;
        for (int index = 0; index < states.length; index++) {
            final BlockGeometry geometry = geometries[index];
            if (geometry == null) {
                continue;
            }
            final int x = index % sizeX;
            final int z = (index / sizeX) % sizeZ;
            final int y = index / layerStride;

            for (BlockGeometry.Layer layer : BlockGeometry.LAYERS) {
                final int layerIndex = layer.index();
                final SectionMesh mesh = meshes[layerIndex];
                if (mesh == null) {
                    continue;
                }
                final BlockGeometry.Quads quads = geometry.quads(layer);
                int cursor = cursors[layerIndex];
                for (int quad = 0; quad < quads.quadCount; quad++) {
                    final int sourcePosition = quad * 12;
                    final int sourceUv = quad * 8;
                    final int color = quads.colors[quad];
                    final byte emission = (byte) Math.min(15, quads.lightEmission[quad]);
                    final short normal = (short) mesh.internNormal(
                            quads.normals[sourcePosition],
                            quads.normals[sourcePosition + 1],
                            quads.normals[sourcePosition + 2]);
                    for (int vertex = 0; vertex < 4; vertex++) {
                        final int source = sourcePosition + vertex * 3;
                        final int destination = cursor * 3;
                        mesh.positions[destination] = quads.positions[source] + x;
                        mesh.positions[destination + 1] = quads.positions[source + 1] + y;
                        mesh.positions[destination + 2] = quads.positions[source + 2] + z;

                        mesh.uvs[cursor * 2] = quads.uvs[sourceUv + vertex * 2];
                        mesh.uvs[cursor * 2 + 1] = quads.uvs[sourceUv + vertex * 2 + 1];

                        mesh.normalIndex[cursor] = normal;
                        mesh.colors[cursor] = color;
                        mesh.lightEmission[cursor] = emission;
                        cursor++;
                    }
                }
                cursors[layerIndex] = cursor;
            }
        }
        return meshes;
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

    SubmitNodeCollector.CustomGeometryRenderer renderer() {
        return renderer;
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
        final int light = frameLight;

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
            consumer.addVertex(position.x, position.y, position.z,
                    colors[vertex],
                    uvs[vertex * 2], uvs[vertex * 2 + 1],
                    OverlayTexture.NO_OVERLAY,
                    LightCoordsUtil.lightCoordsWithEmission(light, lightEmission[vertex] & 0xFF),
                    frame[normalOffset], frame[normalOffset + 1], frame[normalOffset + 2]);
        }
    }
}
