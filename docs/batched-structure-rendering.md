# Batched structure rendering

This document covers the second Crythst structure renderer, which replaces the
per-block `submitBlockModel` path with section-level vertex batches. It is
modelled after sable's "Option C" idea (`RENDER-NOTES.md` §2,
`SubLevelBatchedTerrainRenderer`): avoid one submit node per block, group whole
sections by render layer, and apply the structure transform in the batch pose.

It is the renderer registered by the current client entrypoint. It shares the
server snapshot producer with `RealStructureBlockRenderer` and also renders
multiplayer structures from `ClientStructureCache`.

## Files

| File | Purpose |
| --- | --- |
| `src/main/java/io/mikaple/crythst/client/render/structure/batch/BatchedStructureRenderer.java` | Public entry point, registration, frame culling/sorting/submission, stats. |
| `.../batch/StructureBatchCache.java` | Per-body 16³ section grid, snapshot content diffing, build budget, cache lifecycle. |
| `.../batch/SectionMesh.java` | Flat section-local vertex soup for one render layer and its replay loop. |
| `.../batch/SectionCulling.java` | Pure conservative section AABB, enclosed-block predicate, and occlusion on/off switch. |
| `.../batch/SectionOcclusion.java` | Pure section-graph BFS used for optional occlusion culling. |
| `.../batch/SpecialBlockCell.java` | Section-local reference to a block drawn by a `SpecialBlockRenderer`. |
| `.../batch/BlockGeometry.java` | Pose-independent quads for one block state, split into solid/cutout/translucent layers. |
| `.../batch/BlockGeometryCache.java` | Palette cache: `BlockState` → baked quads with atlas UVs, tint and emission. |
| `.../batch/FallbackCuboids.java` | Bounded coloured-cuboid fallback for bodies with no snapshot. |
| `.../batch/BatchStats.java` | Immutable per-frame counter record. |
| `.../batch/StructurePoseMonitor.java` | Rate-limited frame-to-frame pose-delta diagnostics. |
| `.../batch/StructureVoxelView.java` | Read-only cross-section occupancy/state view used by meshing. |
| `.../physics/tick/PoseTimeline.java` | Timestamped, one-tick-delayed pose sampler shared by local and network paths. |
| `.../light/StructureLightOccluder.java` | Immutable bitset of solid voxels; public occlusion API. |
| `.../light/VoxelLighting.java` | Pure face-culling and vertex-AO rules, shared with tests. |
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
   with baked vertex AO (1 int), light emission (1 byte) and a 16-bit index into
   a small per-mesh normal palette. Positions are section-local, around the
   section origin. The palette lets the frame path rotate a handful of normals
   per section instead of one per vertex. Per quad the mesh also stores the
   adjacent voxel cell and a cached lightmap value.

### Frame path

For every structure body that passes the structure AABB/frustum check (sections
are then distance- and frustum-culled individually):

1. Look up the immutable snapshot by dimension + body id.
2. Get or create the body cache; refresh it if a new snapshot object was
   published.
3. Build at most `MAX_BUILD_VERTICES_PER_FRAME` vertices of dirty sections.
4. Prepare every section: world centre, distance, and a conservative frustum
   AABB from all eight transformed corners plus a one-block model margin.
5. Optionally run the per-body section occlusion BFS (only when the camera is
   inside the body), then put surviving sections with a solid/cutout mesh into
   the opaque list and sections with a translucent mesh into the translucent
   list. A section whose normal mesh is still pending is never rejected by the
   BFS; it stays on the bounded per-block fallback path.
6. Sort opaque sections near-to-far and translucent sections far-to-near.
7. Submit one `SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType,
   VertexConsumer)` per non-empty section/layer, up to
   `MAX_BATCHES_PER_FRAME`.

The vertex replay uses a cached `CustomGeometryRenderer` per `SectionMesh`; the
per-frame fallback light value is written into the mesh immediately before
submission. No lambda is allocated per batch per frame.

### Section culling

`SectionCulling` deliberately separates the three culling stages so each one
can be tested without a level or a frustum:

* **Bounds/frustum.** `sectionBounds(pose, min, max, margin)` transforms all
  eight section corners and returns their AABB. A rigid transform maps the
  section box to the convex hull of those corners, so the AABB cannot clip
  visible geometry; the test is conservative, never aggressive. The margin is
  one full block in structure-local metres (`blockScale` world units), which
  covers fences, plants, modded models and special renderers that overhang
  their voxel. Body-level frustum culling uses the snapshot's rendered world
  bounds (not the collider AABB, which can miss non-collidable edge blocks)
  plus the same conservative margin. The old fixed one-block margin on the
  collider AABB was too small for scaled-up structures and could pop a body out
  at oblique angles.
* **Enclosed blocks.** During meshing, a full solid block
  (`BlockState.isSolidRender()`) whose six neighbours are also full solid
  blocks contributes no quads. `isSolidRender()` is stricter than
  `canOcclude()`: stairs, slabs, glass and other shaped states never count, so
  an exposed top or side face is never skipped. The neighbour lookup uses a
  cached per-body whole-cube occupancy mask; the existing `canOcclude()` mask
  remains the source for AO.
* **Section visibility BFS.** When the camera is inside the body's voxel
  bounds, a BFS starts at the camera's section and crosses only section faces
  that are not completely opaque (`SectionOcclusion`). A solid wall therefore
  stops propagation while a hole or an empty section keeps the neighbour
  visible. If the camera is outside the body, or occlusion is disabled, the
  result is "all sections visible" and only distance/frustum culling applies.
  The BFS is computed lazily, at most once per body per frame.

Occlusion culling can be disabled for A/B with
`-Dcrythst.render.occlusionCulling=false` or at runtime through
`BatchedStructureRenderer.setOcclusionCullingEnabled(false)`. The current
counters are visible in `BatchStats` and in the text appended to
`/crythst timing` (`poseDiagnosticsText()`), including sections rejected by
distance, frustum and occlusion plus the number of enclosed blocks skipped.

### Internal faces, AO and face light

Structures are meshed like small voxel chunks, not like independent blocks:

* **Face culling.** While building a section, every quad with a cull direction
  tests the adjacent voxel in the same body (`Block.shouldRenderFace`). The
  lookup crosses section borders, so a wall spanning two sections loses the
  hidden seam faces. Cells outside the snapshot are air, so the outer shell is
  never culled. `Block.shouldRenderFace` handles full cubes, slabs, glass and
  modded shape occlusion instead of only checking `isSolid()`. A whole block
  whose six neighbours are full cubes is skipped before any of its quads are
  considered (see section culling above).
* **Vertex AO.** For full-cube states (`isSolidRender()`), each quad vertex asks
  the occupancy mask about the two edge neighbours and the diagonal corner
  around the adjacent air cell. The classic rule gives AO levels 0–3; the
  resulting shade (`0.55 / 0.70 / 0.85 / 1.00`) is multiplied into the vertex
  ARGB colour at build time. It costs nothing per frame and darkens corners
  even when the section light is uniform. Shaped models stay flat-shaded rather
  than getting incorrect mid-face darkness.
* **Face light sampling.** Each quad remembers the packed voxel of the adjacent
  cell. During submission, the section pose and block scale map that cell to a
  world position, and `LightCoordsUtil.getLightCoords(level, pos)` samples the
  lightmap there — not at the solid block centre. Emission is still applied
  with `lightCoordsWithEmission`. Samples are cached per quad and re-used while
  the face stays in the same world cell; a slow refresh every 16 frames picks
  up light changes. If the cell is unloaded, no budget remains, or sampling
  fails, the mesh falls back to the section light value.
* **Occupancy API.** `Body` owns an immutable `StructureLightOccluder` built
  from `BlockState.canOcclude()`. It is the same mask used for culling and AO
  and is exposed through
  `BatchedStructureRenderer.lightOccluder(dimension, bodyId)`:
  ```java
  StructureLightOccluder mask =
          BatchedStructureRenderer.lightOccluder(level.dimension(), bodyId);
  boolean solid = mask.isSolid(voxelX, voxelY, voxelZ);
  ```
  Coordinates are structure-local voxels (before the pose/block scale), and
  out-of-bounds cells read as air. Minecraft's global light engine is not
  modified.

Rebuilds stay cheap and correct at section borders: a changed section marks all
sections within one voxel of its bounds dirty, because culling and AO read one
cell across a border. A body with more than 16 simultaneously changed sections
invalidates all of its sections instead of doing a quadratic neighbourhood walk.

### Pose interpolation and precision

The section transform is sampled from a timestamped `PoseTimeline`, not from
Minecraft's client partial tick. Server physics poses are published on the
server tick boundary, while the client alpha describes the client tick; the two
loops are not phase-locked, so alpha sampling made moving structures jump
whenever a server tick landed mid-client-tick. The timeline samples
`now - one smoothed publish interval` and clamps rather than extrapolating.
Teleports, reindexing and splits reset it. Multiplayer poses go through the same
timeline: `ClientStructureCache.applyPose` records a timestamp and the renderer
calls `ClientStructureCache#interpolatedPose(bodyId, frameNanos)`.

### Transform and precision

For a section, the PoseStack is:

```text
T(position + R * (sectionOriginVoxel * blockScale) - camera)
* R
* S(blockScale)
```

and the cached vertices are `(blockVoxel - sectionOriginVoxel) + quadLocal`,
i.e. within roughly `[0,16]`. This is the same world-space point the collision
shape produces. `position` is the structure pose (body pose minus `R*COM`), so
no centre-of-mass term belongs in the local coordinates. Mixing the structure
pose with COM-subtracted local coordinates shifts rendering by `-R*COM`
relative to collision. The old per-block path effectively used
`T(worldBlock - camera) * R * S` per block; this renderer evaluates the same
convention once per section. Because only the camera-relative section origin
goes through `float`, precision stays good far from the world origin. The
`blockScale` matches the collider and debug OBB, so rendered blocks, collision
and bounds stay aligned, including for rotated structures.

`/crythst align` prints this relationship for the active structures: body pose,
structure pose, COM, `R*COM`, and a sample voxel centre transformed by both the
collision convention and the render convention. `conventionErr` and `offset`
should both be zero.

### Special block renderers

Blocks registered through `SpecialBlockRenderers.register(block, renderer)` are
handled by the batch renderer too.

* **Detection and caching:** while a section's state array is copied (creation
  or snapshot refresh), every non-air state whose block is registered is added
  to a bounded `SpecialBlockCell` list (max 512 per section). The normal
  `BlockGeometry` is not added to the section mesh for those states, and the
  per-block fallback skips them, so a special block is never drawn twice.
  `SectionMesh.build` and `Section.countVertices` also skip registered blocks,
  keeping vertex counts and the global budget correct.
* **Registry invalidation:** `StructureBatchCache.beginFrame` compares
  `SpecialBlockRenderers.size()` with the last frame. Registering a renderer for
  a previously normal block changes the size, clears the body caches, and
  rebuilds sections so the block's normal quads disappear. Replacing a renderer
  for an already-special block needs no rebuild; the submit path reads the
  current renderer. Block-state changes are already caught by the per-section
  content compare.
* **Submission:** after normal section batches and fallback blocks, visible
  sections with special cells are sorted near-to-far and each cell is submitted
  with `SpecialBlockRenderer.submit(...)`. The pose is
  `T(worldCenter - camera) * R * S(blockScale) * T(-0.5,-0.5,-0.5)`, so a
  renderer following the documented `+0.5` convention lands its unit cube at
  the rotated block centre. At most 1,024 special renderer calls run per frame.
* **Context:** `SpecialBlockRenderContext.pos()` is a representative world cell
  (used for level/light lookups and `blockCenter()` compatibility), while the
  exact rotated centre is passed through the additive
  `SpecialBlockRenderer.submit(context, worldCenter, ...)` overload. Existing
  renderers get the old method via the default implementation and still receive
  the exact pose. Structure-aware renderers can override the overload.
* **Lighting:** `SpecialBlockLight.getLightCoords(level, representativePos,
  state)` samples the nearest world cell, including the six-neighbour maximum
  for solid-render blocks. If sampling fails, the frame falls back to the
  normal per-position light lookup.

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
| Block edited in a structure | The changed section(s) fail the content compare and are rebuilt on the next build budget slot. Sections within one voxel of a changed section are also invalidated so border culling/AO stays correct; more than 16 changed sections invalidates the whole body. The occupancy masks and the cached per-section face-opacity flags are rebuilt from the refreshed section states. |
| Block scale changed | The whole body cache is recreated. Centre-of-mass changes only move the per-frame structure pose; section geometry is unshifted and does not need a rebuild. |
| Snapshot bounds changed | The whole body cache is recreated. |
| Client resource reload (textures/models) | Reload listener clears all body meshes first, then the palette; a palette generation counter also forces a clear if a body is touched before the listener runs. |
| Level/dimension change | All body caches are cleared when the dimension key changes. |
| Structure removed / snapshot disappears | Body caches are pruned after `BODY_EXPIRY_NANOS` (2 s) without being seen. |
| Renderer disabled | `setEnabled(false)` clears the resident vertex cache. |
| Special renderer registered/replaced | `SpecialBlockRenderers.size()` change clears body caches so normal quads are removed and special cells are recollected. Replacing an already-special renderer needs no rebuild. |
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

Structure lighting adds bounded work:

* Face culling and AO run only on section rebuilds. A rebuild walks each quad
  once and performs at most three occupancy lookups per cube-face vertex.
* Face light sampling is capped at `MAX_FACE_LIGHT_SAMPLES_PER_FRAME`
  (16,384) successful/attempted level lookups per frame. Quads cache the world
  cell they sampled, so a stationary or slowly moving structure re-samples only
  a small fraction of faces per frame. Nearest sections are submitted first and
  therefore get the budget first.
* No per-vertex level lookups are made in the frame path; the replay loop only
  reads the per-quad light array.

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
* `cachedSpecialCells`, `submittedSpecialBlocks`
* `modelCacheEntries`
* `buildMillisLastFrame`, `submitMillisLastFrame`

`statsText()` returns the same as one line for a HUD or command.
`poseDiagnosticsText()` returns the rate-limited maximum frame-to-frame
structure pose delta over the last two seconds (see
`docs/physics-timing.md` for the `/crythst timing` snippet).

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
* **Special blocks are capped.** A section retains at most 512 special cells and
  the renderer invokes at most 1,024 special renderers per frame. Very dense
  special-block structures (for example a wall of item-model blocks) will draw
  only the nearest cells first. Special renderers are also invoked once per
  visible cell per frame, so they should keep their own per-frame work small.
* **`SpecialBlockRenderContext.blockCenter()` is representative for structures.**
  It is the nearest world cell used for light/level lookups. The exact rotated
  centre is supplied by the additive
  `SpecialBlockRenderer.submit(context, worldCenter, ...)` overload; existing
  renderers that rely only on the pose are already exact.
* **AO is voxel-grid AO, not smooth interpolated AO.** It is computed from the
  structure's own occupancy grid for full-cube states. Shaped models (stairs,
  fences, plants) stay flat-shaded, and AO does not bleed onto neighbouring
  world terrain.
* **No sky-light propagation inside structures.** Faces sample the world
  lightmap at the adjacent cell, but the structure does not write shadow
  columns into Minecraft's light engine. An interior room therefore inherits
  the world light of its adjacent cells; it is not recomputed as a closed
  cavity. Adding that would require a structure-local light propagation pass
  and a way to feed it back into the vanilla engine.
* **Face-light cache is approximate over time.** A cached face light is reused
  while the face stays in one world cell, refreshed at most every 16 frames and
  capped by `MAX_FACE_LIGHT_SAMPLES_PER_FRAME`. Rapid world light changes can
  lag by a fraction of a second; when the per-frame budget is exhausted the
  previous value or the section fallback is used.
* **Tint uses the client tint source without a world position.** Grass/leaves
  get their default tint; biome-specific tints are not resolved because the
  snapshot has no world position for each block. Unresolvable tint sources fall
  back to white rather than black.
* **Special model renderers, block entities, animated model parts outside the
  baked quad set and item/entity features are not reproduced.** The snapshot
  renderer only has baked block quads available.
* **Singleplayer and multiplayer use different pose sources.** In singleplayer
  `ClientPhysicsView` supplies live local `PhysicsStructure` poses; in
  multiplayer `ClientStructureCache` supplies synced snapshots. Both are now
  sampled through `PoseTimeline`, with one smoothed publish interval of delay.
  If a snapshot exists but is unavailable for a body (oversized/empty), the
  bounded coloured-cuboid fallback is used.
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
