# Batched structure rendering

This document covers the second Crythst structure renderer, which replaces the
per-block `submitBlockModel` path with section-level vertex batches. It is
modelled after sable's "Option C" idea (`RENDER-NOTES.md` §2,
`SubLevelBatchedTerrainRenderer`): avoid one submit node per block, group whole
sections by render layer, and apply the structure transform in the batch pose.

The implementation lives entirely in a new package and does not modify
`RealStructureBlockRenderer`, `StructureBlockSnapshots`, `CrythstClient`, the
Fabric integration, mixins or config.

## Files

| File | Purpose |
| --- | --- |
| `src/main/java/io/mikaple/crythst/client/render/structure/batch/BatchedStructureRenderer.java` | Public entry point, registration, frame culling/sorting/submission, stats. |
| `.../batch/StructureBatchCache.java` | Per-body 16³ section grid, snapshot content diffing, build budget, cache lifecycle. |
| `.../batch/SectionMesh.java` | Flat section-local vertex soup for one render layer and its replay loop. |
| `.../batch/BlockGeometry.java` | Pose-independent quads for one block state, split into solid/cutout/translucent layers. |
| `.../batch/BlockGeometryCache.java` | Palette cache: `BlockState` → baked quads with atlas UVs, tint and emission. |
| `.../batch/FallbackCuboids.java` | Bounded coloured-cuboid fallback for bodies with no snapshot. |
| `.../batch/BatchStats.java` | Immutable per-frame counter record. |
| `docs/batched-structure-rendering.md` | This document. |

## Registration

`BatchedStructureRenderer.register()` is idempotent. In the current workspace
`CrythstClient.onInitializeClient()` already calls it (next to
`SpecialBlockRenderManager.register()`):

```java
BatchedStructureRenderer.register();
```

with the import
`io.mikaple.crythst.client.render.structure.batch.BatchedStructureRenderer`.
If this renderer is wired into a different client entrypoint, add the same two
lines there.

The method:

1. calls `StructureBlockSnapshots.register()` (idempotent),
2. registers `BatchedStructureRenderer::render` on
   `LevelRenderEvents.BEFORE_GIZMOS`,
3. registers a client resource-reload listener that clears body meshes and the
   block palette,
4. calls `RealStructureBlockRenderer.setEnabled(false)` and
   `StructureBlockRenderer.setBlockRenderingEnabled(false)` so the old
   per-block renderer and the legacy cuboids do not draw on top.

Registration order relative to the existing `RealStructureBlockRenderer.register()`
does not matter: the old renderer's `enabled` flag is false either way, and its
own `register()` does not re-enable it.

## Architecture

### Three cache levels

1. **Palette** (`BlockGeometryCache`): one `BlockGeometry` per `BlockState`.
   It resolves `BlockStateModelSet.get(state)`, calls
   `collectParts(RandomSource.create(0), parts)` once, and flattens
   `BakedQuad`s into three layer arrays. The arrays contain block-local
   positions in `[0,1]`, atlas UVs from `UVPair.unpackU/unpackV`, normals from
   `BakedQuad.direction()` (with a winding fallback for null directions), tint
   colour and light emission. Nothing is resolved per block per frame.
2. **Body/sections** (`StructureBatchCache`): one `Body` per physics body id,
   split into 16×16×16 sections aligned to the snapshot minimum. Each section
   owns a clipped `BlockState[]` (only the part of the cube that intersects the
   snapshot bounds), so a 1×32768×1 structure uses 2048 tiny arrays instead of
   2048 full 4096-entry arrays. Sections are independent and can be rebuilt one
   at a time.
3. **Section mesh** (`SectionMesh`): one flat vertex array per section and
   render layer. A vertex stores position (3 floats), UV (2 floats), ARGB colour
   (1 int), light emission (1 byte) and a 16-bit index into a small per-mesh
   normal palette. Positions are section-local, around the section origin. The
   palette lets the frame path rotate a handful of normals per section instead
   of one per vertex.

### Frame path

For every structure body that passes the structure AABB/frustum check (sections
are then distance- and frustum-culled individually):

1. Look up the immutable snapshot by dimension + body id.
2. Get or create the body cache; refresh it if a new snapshot object was
   published.
3. Build at most `MAX_BUILD_VERTICES_PER_FRAME` vertices of dirty sections.
4. Prepare every section: world centre, distance, frustum AABB.
5. Put sections with a solid/cutout mesh into the opaque list and sections with
   a translucent mesh into the translucent list.
6. Sort opaque sections near-to-far and translucent sections far-to-near.
7. Submit one `SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType,
   VertexConsumer)` per non-empty section/layer, up to
   `MAX_BATCHES_PER_FRAME`.

The vertex replay uses a cached `CustomGeometryRenderer` per `SectionMesh`; the
per-frame light value is written into the mesh immediately before submission.
No lambda is allocated per batch per frame.

### Transform and precision

For a section, the PoseStack is:

```text
T(position + R * (sectionOriginVoxel * blockScale - COM) - camera)
* R
* S(blockScale)
```

and the cached vertices are `(blockVoxel - sectionOriginVoxel) + quadLocal`,
i.e. within roughly `[0,16]`. This is exactly the transform the old per-block
path applied (`T(worldBlock - camera) * R * S`), but it is evaluated once per
section instead of once per block. Because only the camera-relative section
origin goes through `float`, precision stays good far from the world origin.
The same `centerOfMassOffset` and `blockScale` are used by the collider and
debug OBB, so the rendered blocks, collider and OBB stay aligned, including for
rotated structures.

### Why the old path looked wrong

`RealStructureBlockRenderer` chooses one `RenderType` per `BlockState` from the
model-level material flag: if any part is translucent, the entire block goes
through `translucentMovingBlock`, including its solid and cutout faces. That
produces incorrect blending/sorting and can make faces disappear or look
washed out. It also re-resolves/traverses model parts for every block every
frame.

This renderer splits **per quad**, using
`BakedQuad.materialInfo().layer()`: solid faces use `solidMovingBlock`, cutout
faces use `cutoutMovingBlock`, translucent faces use
`translucentMovingBlock`. UVs, normals, tint and emission are copied from the
baked quad, so no faces are dropped and no black/default quads are introduced.

## Cache invalidation

| Event | Action |
| --- | --- |
| New snapshot object every few server ticks | Per-section content comparison; only changed sections are invalidated. An unchanged snapshot object does not rebuild anything. |
| Block edited in a structure | The changed section(s) fail the content compare and are rebuilt on the next build budget slot. |
| Block scale or centre-of-mass offset changed | The whole body cache is recreated (section origins depend on both). |
| Snapshot bounds changed | The whole body cache is recreated. |
| Client resource reload (textures/models) | Reload listener clears all body meshes first, then the palette; a palette generation counter also forces a clear if a body is touched before the listener runs. |
| Level/dimension change | All body caches are cleared when the dimension key changes. |
| Structure removed / snapshot disappears | Body caches are pruned after `BODY_EXPIRY_NANOS` (2 s) without being seen. |
| Renderer disabled | `setEnabled(false)` clears the resident vertex cache. |
| Vertex cache budget | Sections that cannot fit are marked `overBudget` and drawn through the bounded per-block fallback; they are retried automatically once the cache falls below half the budget. |

Snapshots are rebuilt on the server thread every 4 ticks and the snapshot
object is replaced even when no block changed. That is why invalidation uses a
content compare rather than `Snapshot.revision()` alone: the revision changes
on every rebuild and would otherwise rebuild every structure five times per
second.

## Performance

Let `B` be non-air blocks, `S` the number of 16³ sections touched, and `L` the
number of render layers a section uses (1–3). For a typical structure:

* **Submit nodes:** `B` (old) → at most `S * L` (new). A 32,768-block
  structure occupying roughly 8–64 sections submits 8–192 nodes instead of
  32,768. That is a 100×–4000× reduction in submit-node count.
* **Model resolution:** `BlockStateModelSet.get` + `collectParts` is done once
  per distinct `BlockState` and only after a resource reload, instead of once
  per block per frame.
* **Pose work:** one `PoseStack` build per section/layer instead of one per
  block, and one `submitBlockModel` call per block replaced by a vertex replay.
* **Per-frame vertex work** remains O(vertices), as it must for any renderer,
  but each vertex is a direct array read + matrix transform + consumer write,
  and normals are rotated once per small palette. There is no model-part walk,
  no `QuadInstance`/`PoseStack.Pose.copy()` per block and no per-block node
  allocation.

The remaining per-frame costs are section culling (8 corner transforms + one
AABB test per visible section), the vertex replay, and one `ConcurrentHashMap`
lookup per block state during cache builds (not per frame).

### Difference from sable's terrain path

Sable's `SubLevelBatchedTerrainRenderer` goes one step further: it reuses the
vanilla section meshes and issues GPU draws through `ChunkSectionsToRender`, so
the CPU does not replay vertices at all. Crythst structures do not own a
`SectionRenderDispatcher` mesh, and the 26.3 public API does not expose a way to
register arbitrary structure sections as terrain chunks. This renderer therefore
uses the next-best fully supported path: cached CPU vertices replayed through
`submitCustomGeometry`. It removes the per-block model resolution and submit
nodes (the dominant costs), but it still touches every visible vertex once per
frame. A future custom GPU-buffer backend could remove that replay too.

## Stats and tuning

`BatchedStructureRenderer.stats()` returns a `BatchStats` record with:

* `cachedBodies`, `cachedSections`, `cachedBatches`, `cachedVertices`
* `submittedBatches`, `submittedVertices` (last frame)
* `culledSections`, `pendingSections`, `overBudgetSections`
* `fallbackBodies`, `fallbackBlocks`
* `modelCacheEntries`
* `buildMillisLastFrame`, `submitMillisLastFrame`

`statsText()` returns the same as one line for a HUD or command.

Runtime controls:

```java
BatchedStructureRenderer.setEnabled(false);   // clear + stop drawing
BatchedStructureRenderer.toggle();
BatchedStructureRenderer.setRenderDistance(384.0);
BatchedStructureRenderer.setVertexBudget(1_000_000L);
```

The vertex budget defaults to 1,000,000 vertices (about 27 MB at 27 bytes
per vertex, plus section state arrays and the tiny normal palettes). Sections
that do not fit use the fallback path. Lower the budget on memory-constrained
launchers; raise it if you frequently run several very large structures at
once.

## Switching between renderers

* **Batched (this renderer):** call `BatchedStructureRenderer.register()`. It
  disables the other two automatically. F9 off (default) draws batched.
* **Per-block real renderer:** call `RealStructureBlockRenderer.register()`
  without calling the batched registration, or call
  `RealStructureBlockRenderer.setEnabled(true)` and
  `BatchedStructureRenderer.setEnabled(false)`.
* **Legacy coloured cuboids:** press F9 (`StructureBlockRenderer.toggleBlockRendering()`).
  While legacy is on, both real renderers suspend themselves. F9 off returns to
  whichever real renderer is enabled.

The three paths are mutually exclusive by construction: the batched renderer
returns early when legacy block rendering is enabled, and `register()` disables
the per-block renderer.

## Limitations

* **Random model variants use a fixed seed.** Models that pick a variant from
  `RandomSource` are baked once per state with seed 0, so every instance of that
  state looks the same. The per-block renderer did the same.
* **No neighbour-face culling.** The cache reuses the baked block model quads, so
  hidden faces between two solid blocks are still submitted, exactly as in the
  per-block path. The win is node/model work, not triangle count. A future
  version could run a section mesher and drop interior faces.
* **No per-vertex ambient occlusion.** Light is sampled once per section centre
  per frame and combined with each quad's emission. Vanilla's smooth AO needs
  neighbour lookups and a moving structure has no stable world neighbours;
  the result is flat but correctly lit. This is the main visual difference from
  the vanilla chunk renderer.
* **Tint uses the client tint source without a world position.** Grass/leaves
  get their default tint; biome-specific tints are not resolved because the
  snapshot has no world position for each block. Unresolvable tint sources fall
  back to white rather than black.
* **Special model renderers, block entities, animated model parts outside the
  baked quad set and item/entity features are not reproduced.** The snapshot
  renderer only has baked block quads available.
* **Snapshots are singleplayer-first.** In multiplayer `ClientPhysicsView` is
  empty, so neither real renderer has structures to draw. If a snapshot exists
  but is unavailable for a body (oversized/empty), the bounded coloured-cuboid
  fallback is used.
* **Fallback is intentionally small.** A section that is still building or is
  over the global vertex budget can submit at most
  `MAX_FALLBACK_BLOCKS_PER_FRAME` individual blocks per frame. The fallback uses
  the model-level moving-block render type, so a mixed solid/translucent block
  can show the old layer-selection behaviour until its section cache is ready.
  On the first frame or two of a very large structure, distant pending sections
  may pop in.
* **No Sodium-specific path.** This renderer uses only the vanilla 26.3
  `SubmitNodeCollector` API, so it works with the vanilla renderer; a Sodium
  backend would need its own integration.
* **Translucent ordering is section-granular.** Sections are sorted
  far-to-near, and the moving-block translucent `RenderType` also has
  `sortOnUpload`; overlapping translucent geometry within a section is still
  handled by the vanilla sorting/upload path.
* **Resource budget.** The default 1,000,000-vertex budget is well above normal
  structures but a few simultaneous worst-case 32,768-block bodies can exceed
  it and fall back. The budget and per-frame build cap are constants in
  `StructureBatchCache`/`BatchedStructureRenderer`.

## 26.3 API notes

The implementation uses the same 26.3 primitives as sable, but not the
renderpearl terrain pipeline, because Crythst structures do not own
`SectionRenderDispatcher` meshes:

* `SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType,
  CustomGeometryRenderer)` copies the pose at submission time, so a single
  reusable `PoseStack` is safe.
* `BlockStateModel.collectParts(RandomSource, List<BlockStateModelPart>)` and
  `BakedQuad.materialInfo().layer()/tintIndex()/lightEmission()` provide the
  per-quad layer split.
* `UVPair.unpackU/unpackV` decode `BakedQuad.packedUV` into atlas UVs.
* `RenderTypes.solidMovingBlock()`, `cutoutMovingBlock()` and
  `translucentMovingBlock()` are the correct moving-block render types; the
  legacy renderer only used the last two.
* `LightCoordsUtil.lightCoordsWithEmission(base, emission)` preserves per-quad
  emission.
* `VertexConsumer.addVertex(x, y, z, argb, u, v, overlay, light, nx, ny, nz)`
  lets the replay avoid the allocating `addVertex(Pose, ...)` /
  `setNormal(Pose, ...)` defaults.

No 26.3 API blocker was found for a full vertex cache.
