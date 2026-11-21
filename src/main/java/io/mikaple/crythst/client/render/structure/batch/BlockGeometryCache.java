package io.mikaple.crythst.client.render.structure.batch;

import io.mikaple.crythst.Crythst;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Palette cache: one pose-independent {@link BlockGeometry} per block state.
 *
 * <p>Resolving a {@code BlockStateModel} and walking
 * {@code BlockStateModelPart.getQuads(...)} is the expensive part of block
 * rendering. The legacy per-block path does it for every block every frame
 * (through {@code submitBlockModel}), while this cache does it once per state
 * and then shares the flat quad arrays between every instance of that state in
 * every structure.</p>
 *
 * <p>Entries are cleared on a client resource reload, because the baked quads
 * contain atlas UVs that change with the texture atlas and model set. The
 * generation counter lets {@link StructureBatchCache} detect a palette swap
 * without sharing locks with the reload thread.</p>
 */
final class BlockGeometryCache {
    private static final Map<BlockState, BlockGeometry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int WHITE = 0xFFFFFFFF;

    private BlockGeometryCache() {
    }

    static BlockGeometry get(BlockState state) {
        if (state == null || state.isAir()) {
            return BlockGeometry.EMPTY;
        }
        final BlockGeometry cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        final BlockGeometry built = build(state);
        if (built == null) {
            // Model manager not ready (or a broken model). Do not poison the
            // cache; the next frame can retry.
            return BlockGeometry.EMPTY;
        }
        final BlockGeometry existing = CACHE.putIfAbsent(state, built);
        return existing == null ? built : existing;
    }

    static void clear() {
        CACHE.clear();
        GENERATION.incrementAndGet();
    }

    static long generation() {
        return GENERATION.get();
    }

    static int size() {
        return CACHE.size();
    }

    private static BlockGeometry build(BlockState state) {
        final Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        final BlockStateModelSet modelSet;
        try {
            modelSet = client.getModelManager().getBlockStateModelSet();
        } catch (RuntimeException ignored) {
            return null;
        }

        try {
            BlockStateModel model = modelSet.get(state);
            if (model == modelSet.missingModel() && state.getBlock() != Blocks.STONE) {
                // Modded or not-yet-loaded state: the snapshot already chose
                // stone as the fallback, so keep the same visual language.
                model = modelSet.get(STONE);
            }

            final List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(0L), parts);

            final QuadBuilder[] builders = new QuadBuilder[]{
                    new QuadBuilder(), new QuadBuilder(), new QuadBuilder()
            };
            final float[] quadPositions = new float[12];
            final float[] quadUvs = new float[8];
            final float[] quadNormals = new float[12];
            boolean any = false;

            for (BlockStateModelPart part : parts) {
                for (Direction direction : DIRECTIONS) {
                    any |= appendQuads(state, part.getQuads(direction), direction,
                            builders, quadPositions, quadUvs, quadNormals);
                }
                // Null-direction quads are the cross/flat models. BakedQuad
                // normally still carries a direction for them; the helper
                // derives one from the winding when it does not.
                any |= appendQuads(state, part.getQuads(null), null,
                        builders, quadPositions, quadUvs, quadNormals);
            }

            if (!any) {
                return new BlockGeometry(List.copyOf(parts), RenderTypes.cutoutMovingBlock(),
                        new BlockGeometry.Quads[]{
                                BlockGeometry.Quads.EMPTY,
                                BlockGeometry.Quads.EMPTY,
                                BlockGeometry.Quads.EMPTY
                        }, true, 0);
            }

            final BlockGeometry.Quads[] quads = new BlockGeometry.Quads[3];
            int vertexCount = 0;
            for (int i = 0; i < builders.length; i++) {
                quads[i] = builders[i].build();
                vertexCount += quads[i].vertexCount();
            }

            // The per-block fallback can only submit one RenderType for all
            // parts. Prefer translucent when present, matching vanilla's
            // "most transparent part wins" behaviour for moving blocks.
            final RenderType fallbackType = quads[BlockGeometry.Layer.TRANSLUCENT.index()].isEmpty()
                    ? RenderTypes.cutoutMovingBlock()
                    : RenderTypes.translucentMovingBlock();

            return new BlockGeometry(List.copyOf(parts), fallbackType, quads, false, vertexCount);
        } catch (RuntimeException exception) {
            Crythst.LOGGER.debug("Could not bake block geometry for " + state, exception);
            return null;
        }
    }

    private static boolean appendQuads(BlockState state, List<BakedQuad> quads, Direction direction,
                                       QuadBuilder[] builders,
                                       float[] positions, float[] uvs, float[] normals) {
        if (quads == null || quads.isEmpty()) {
            return false;
        }
        boolean appended = false;
        for (BakedQuad quad : quads) {
            final BakedQuad.MaterialInfo material = quad.materialInfo();
            final BlockGeometry.Layer layer = BlockGeometry.Layer.of(
                    material == null ? null : material.layer());

            for (int vertex = 0; vertex < 4; vertex++) {
                final Vector3fc position = quad.position(vertex);
                positions[vertex * 3] = position.x();
                positions[vertex * 3 + 1] = position.y();
                positions[vertex * 3 + 2] = position.z();

                final long packedUv = quad.packedUV(vertex);
                uvs[vertex * 2] = UVPair.unpackU(packedUv);
                uvs[vertex * 2 + 1] = UVPair.unpackV(packedUv);
            }

            final Direction normalDirection = quad.direction() == null ? direction : quad.direction();
            writeNormal(normalDirection, positions, normals);

            final int tintIndex = material == null ? -1 : material.tintIndex();
            final int emission = material == null ? 0 : Math.max(0, material.lightEmission());
            final byte face = normalDirection == null ? -1 : (byte) normalDirection.ordinal();
            builders[layer.index()].add(positions, uvs, normals,
                    tintColor(state, tintIndex), emission, face);
            appended = true;
        }
        return appended;
    }

    private static void writeNormal(Direction direction, float[] positions, float[] normals) {
        float nx;
        float ny;
        float nz;
        if (direction != null) {
            final Vector3fc unit = direction.getUnitVec3f();
            nx = unit.x();
            ny = unit.y();
            nz = unit.z();
        } else {
            // Winding-derived normal for quads without a cull direction.
            final float ax = positions[3] - positions[0];
            final float ay = positions[4] - positions[1];
            final float az = positions[5] - positions[2];
            final float bx = positions[6] - positions[0];
            final float by = positions[7] - positions[1];
            final float bz = positions[8] - positions[2];
            nx = ay * bz - az * by;
            ny = az * bx - ax * bz;
            nz = ax * by - ay * bx;
            final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1.0e-6f) {
                nx = 0.0f;
                ny = 1.0f;
                nz = 0.0f;
            } else {
                nx /= length;
                ny /= length;
                nz /= length;
            }
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            normals[vertex * 3] = nx;
            normals[vertex * 3 + 1] = ny;
            normals[vertex * 3 + 2] = nz;
        }
    }

    private static int tintColor(BlockState state, int tintIndex) {
        if (tintIndex < 0) {
            return WHITE;
        }
        try {
            final BlockColors blockColors = Minecraft.getInstance().getBlockColors();
            if (blockColors == null) {
                return WHITE;
            }
            final BlockTintSource source = blockColors.getTintSource(state, tintIndex);
            if (source == null) {
                return WHITE;
            }
            final int color = source.color(state);
            if ((color & 0xFFFFFF) == 0) {
                // Some sources return 0 when they need a world position. White
                // is safer than painting the block black.
                return WHITE;
            }
            return color | 0xFF000000;
        } catch (RuntimeException ignored) {
            return WHITE;
        }
    }

    private static final class QuadBuilder {
        private float[] positions = new float[48];
        private float[] uvs = new float[32];
        private float[] normals = new float[48];
        private int[] colors = new int[4];
        private int[] lightEmission = new int[4];
        private byte[] faces = new byte[4];
        private int quadCount;

        private void add(float[] p, float[] u, float[] n, int color, int emission, byte face) {
            if (quadCount == colors.length) {
                final int capacity = Math.max(4, colors.length * 2);
                positions = Arrays.copyOf(positions, capacity * 12);
                uvs = Arrays.copyOf(uvs, capacity * 8);
                normals = Arrays.copyOf(normals, capacity * 12);
                colors = Arrays.copyOf(colors, capacity);
                lightEmission = Arrays.copyOf(lightEmission, capacity);
                faces = Arrays.copyOf(faces, capacity);
            }
            System.arraycopy(p, 0, positions, quadCount * 12, 12);
            System.arraycopy(u, 0, uvs, quadCount * 8, 8);
            System.arraycopy(n, 0, normals, quadCount * 12, 12);
            colors[quadCount] = color;
            lightEmission[quadCount] = emission;
            faces[quadCount] = face;
            quadCount++;
        }

        private BlockGeometry.Quads build() {
            if (quadCount == 0) {
                return BlockGeometry.Quads.EMPTY;
            }
            return new BlockGeometry.Quads(
                    Arrays.copyOf(positions, quadCount * 12),
                    Arrays.copyOf(uvs, quadCount * 8),
                    Arrays.copyOf(normals, quadCount * 12),
                    Arrays.copyOf(colors, quadCount),
                    Arrays.copyOf(lightEmission, quadCount),
                    Arrays.copyOf(faces, quadCount),
                    quadCount);
        }
    }
}
