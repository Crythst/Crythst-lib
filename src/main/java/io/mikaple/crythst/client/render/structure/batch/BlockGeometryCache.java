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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /**
     * A winding-derived normal must be within about 2.6 degrees of a cardinal
     * axis before it is treated as a cull face. Diagonal cross/plant quads
     * fall outside this and stay uncullable.
     */
    private static final float CARDINAL_DOT_EPSILON = 0.999f;

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
            // Several model parts (and multipart models in particular) can
            // return the same quad from more than one direction bucket.
            // Keeping one copy avoids z-fighting and double vertex cost.
            final Set<QuadSignature> seen = new HashSet<>();
            boolean any = false;

            for (BlockStateModelPart part : parts) {
                for (Direction direction : DIRECTIONS) {
                    // A quad in a direction bucket was declared cullable by the
                    // model's cullface. That bucket is intentionally *not* used
                    // as the cull direction: the quad's own facing decides which
                    // neighbour can hide it.
                    any |= appendQuads(state, part.getQuads(direction), true,
                            builders, quadPositions, quadUvs, quadNormals, seen);
                }
                // getQuads(null) is vanilla's unculled bucket. These quads are
                // emitted unconditionally and must never be neighbour-culled,
                // even when their geometric facing points at a full block:
                // a slab top/bottom face is inset into the block and stays
                // visible when the adjacent cell is a full cube.
                any |= appendQuads(state, part.getQuads(null), false,
                        builders, quadPositions, quadUvs, quadNormals, seen);
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

    private static boolean appendQuads(BlockState state, List<BakedQuad> quads, boolean cullable,
                                       QuadBuilder[] builders,
                                       float[] positions, float[] uvs, float[] normals,
                                       Set<QuadSignature> seen) {
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

            if (!seen.add(new QuadSignature(
                    Arrays.copyOf(positions, 12), Arrays.copyOf(uvs, 8),
                    quad.direction(), material, cullable))) {
                continue;
            }

            // BakedQuad.direction() is the quad's real facing. It is the
            // authoritative source for that quad; the bucket it was returned
            // from is only a "may be culled" flag. Custom model parts may still
            // hand back a null direction, in which case the winding is used
            // when it resolves to an unambiguous cardinal axis.
            final Direction facing = resolveFacing(quad.direction(), positions);
            final Direction cullFace = cullable ? facing : null;
            writeNormal(facing, positions, normals);

            final int tintIndex = material == null ? -1 : material.tintIndex();
            final int emission = material == null ? 0 : Math.max(0, material.lightEmission());
            final byte face = cullFace == null ? -1 : (byte) cullFace.ordinal();
            builders[layer.index()].add(positions, uvs, normals,
                    tintColor(state, tintIndex), emission, face);
            appended = true;
        }
        return appended;
    }

    /**
     * Resolves the cull face stored for one quad.
     *
     * @param cullable whether the quad came from a direction bucket
     *                 ({@code getQuads(direction)}) rather than vanilla's
     *                 unculled bucket ({@code getQuads(null)})
     */
    static Direction resolveCullFace(Direction quadDirection, boolean cullable, float[] positions) {
        if (!cullable) {
            return null;
        }
        return resolveFacing(quadDirection, positions);
    }

    /**
     * The facing used for shading and (when cullable) neighbour culling.
     * Prefers the baked direction; otherwise derives a cardinal axis from the
     * actual winding. Returns {@code null} for diagonal/degenerate quads.
     */
    static Direction resolveFacing(Direction quadDirection, float[] positions) {
        if (quadDirection != null) {
            return quadDirection;
        }
        final float[] normal = new float[3];
        if (!windingNormal(positions, normal, 0)) {
            return null;
        }
        for (Direction direction : DIRECTIONS) {
            final Vector3fc unit = direction.getUnitVec3f();
            final float dot = normal[0] * unit.x()
                    + normal[1] * unit.y()
                    + normal[2] * unit.z();
            if (dot >= CARDINAL_DOT_EPSILON) {
                return direction;
            }
        }
        return null;
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
        } else if (windingNormal(positions, normals, 0)) {
            // Winding-derived normal for quads without a cardinal facing.
            nx = normals[0];
            ny = normals[1];
            nz = normals[2];
        } else {
            nx = 0.0f;
            ny = 1.0f;
            nz = 0.0f;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            normals[vertex * 3] = nx;
            normals[vertex * 3 + 1] = ny;
            normals[vertex * 3 + 2] = nz;
        }
    }

    /**
     * Normalised normal from the quad's first three vertices. Returns
     * {@code false} for a degenerate quad, leaving {@code out} untouched.
     */
    private static boolean windingNormal(float[] positions, float[] out, int offset) {
        final float ax = positions[3] - positions[0];
        final float ay = positions[4] - positions[1];
        final float az = positions[5] - positions[2];
        final float bx = positions[6] - positions[0];
        final float by = positions[7] - positions[1];
        final float bz = positions[8] - positions[2];
        final float nx = ay * bz - az * by;
        final float ny = az * bx - ax * bz;
        final float nz = ax * by - ay * bx;
        final float lengthSq = nx * nx + ny * ny + nz * nz;
        if (!(lengthSq > 1.0e-12f)) {
            return false;
        }
        final float inverseLength = 1.0f / (float) Math.sqrt(lengthSq);
        out[offset] = nx * inverseLength;
        out[offset + 1] = ny * inverseLength;
        out[offset + 2] = nz * inverseLength;
        return true;
    }

    /**
     * Exact-quad signature used to drop duplicates returned by multiple
     * direction buckets. Includes texture material, so overlays with the same
     * geometry but a different sprite/tint are preserved. Cullability is part
     * of the key so an unculled copy is never replaced by a culled one.
     */
    private record QuadSignature(float[] positions, float[] uvs, Direction direction,
                                 BakedQuad.MaterialInfo material, boolean cullable) {
        @Override
        public boolean equals(Object other) {
            return other instanceof QuadSignature signature
                    && Arrays.equals(positions, signature.positions)
                    && Arrays.equals(uvs, signature.uvs)
                    && direction == signature.direction
                    && Objects.equals(material, signature.material)
                    && cullable == signature.cullable;
        }

        @Override
        public int hashCode() {
            int hash = Arrays.hashCode(positions);
            hash = 31 * hash + Arrays.hashCode(uvs);
            hash = 31 * hash + (direction == null ? 0 : direction.hashCode());
            hash = 31 * hash + Objects.hashCode(material);
            hash = 31 * hash + (cullable ? 1 : 0);
            return hash;
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
