# Real structure block rendering

This document covers the per-block real-model renderer for Crythst physics
structures. It replaces the legacy coloured debug cuboids with actual
`BlockState` models and textures.

The **default** renderer in the current client is `BatchedStructureRenderer`,
which uses the same real block models but groups them into section meshes and
also reads multiplayer snapshots. `RealStructureBlockRenderer` remains an
alternative per-block path; it is disabled by `BatchedStructureRenderer.register()`
and is local/`ClientPhysicsView` only.

## Files

| File | Purpose |
| --- | --- |
| `src/main/java/io/mikaple/crythst/integration/minecraft/structure/StructureBlockSnapshots.java` | Server-thread block-state snapshots keyed by dimension and physics body id. |
| `src/main/java/io/mikaple/crythst/client/render/structure/RealStructureBlockRenderer.java` | Client renderer that submits real block models at the interpolated structure pose. |
| `docs/real-structure-rendering.md` | This document. |

## Registration

`RealStructureBlockRenderer.register()` is idempotent. It registers:

1. the server-tick snapshot producer through `StructureBlockSnapshots.register()`,
2. `RealStructureBlockRenderer::render` with `LevelRenderEvents.BEFORE_GIZMOS`,
3. a client resource-reload listener that clears the model-part cache,
4. `StructureBlockRenderer.setBlockRenderingEnabled(false)` so the legacy
   coloured cuboids are not drawn at the same time.

The current client entrypoint does **not** call this renderer's `register()`.
It calls `BatchedStructureRenderer.register()`, which registers the same
snapshot producer and disables this path. To use the per-block renderer instead,
register it explicitly and do not register the batched renderer:

```java
RealStructureBlockRenderer.register();
```

### F9 behaviour

The F9 key toggles `StructureBlockRenderer` (legacy coloured cuboids). The real
renderers cooperate with it:

* F9 off: whichever real renderer is enabled (batched by default) draws.
* F9 on: legacy coloured debug cuboids draw; both real renderers suspend
  themselves.
* F9 off again: the enabled real renderer resumes.

This prevents double rendering and keeps the legacy view available as a
diagnostic fallback.

## Server-side snapshots

`StructureBlockSnapshots` runs on `ServerTickEvents.END_SERVER_TICK`. For each
`ServerLevel` that has a `LevelPhysics` instance, it iterates
`physics.structures()` and copies the solid voxels into an immutable
`Snapshot`. Snapshots are namespaced by dimension and then keyed by
`structure.body().id()`, because Bullet body ids are allocated per physics world
and can repeat across dimensions.

State selection:

* `BlockVoxelVolume` -> `BlockVoxelVolume.blockState(x, y, z)`.
* `MutableVoxelVolume` -> `StructureBlockStates.get(...)` when the interaction
  layer has stored a visual state, otherwise `Blocks.STONE`.
* Non-solid voxels -> `Blocks.AIR`.

The snapshot stores its voxel bounds, a `BlockState[]`, a non-air count and a
monotonic revision. The array is private, never mutated after construction, and
read through `stateAt(x, y, z)`, so the client can read it without a lock.

Lifecycle and limits:

* Snapshots are rebuilt every `REBUILD_INTERVAL_TICKS = 4` server ticks
  (200 ms at 20 TPS). New structures are snapshotted immediately.
* Body ids not seen in a tick are removed, which also handles structure removal
  and level close.
* `SERVER_STOPPED` clears the map.
* `MAX_BLOCKS_PER_STRUCTURE = 32_768` cells (including air).
* `MAX_TOTAL_BLOCKS = 131_072` non-air blocks across all snapshots.
* `MAX_STRUCTURES = 64`.

Volumes over the per-structure cap keep the legacy cuboid fallback. The total
cap is a soft cap: existing snapshots are kept, new or enlarged replacements are
skipped.

## 26.3 rendering API

The renderer is invoked from `LevelRenderEvents.BEFORE_GIZMOS`. The event gives an
identity `PoseStack` and a `SubmitNodeCollector`; geometry is camera-relative and
must not use absolute world coordinates.

Per block state, the 26.3 path is:

1. `Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state)`
   to resolve a `BlockStateModel`.
2. `BlockStateModel.collectParts(RandomSource, List<BlockStateModelPart>)` to
   obtain model parts.
3. `SubmitNodeCollector.submitBlockModel(PoseStack, RenderType, List,
   int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor)` to
   submit the cached parts.

The renderer uses the moving-block render types that vanilla uses for falling
blocks:

* `RenderTypes.cutoutMovingBlock()` for opaque/cutout models,
* `RenderTypes.translucentMovingBlock()` when `materialFlags()` reports the
  translucent bit (`1`).

Model parts are cached per `BlockState` in a `ConcurrentHashMap`. They are not
re-baked every frame. A client resource reload clears the cache. The parts are
collected with a fixed `RandomSource` seed, so variant models (for example grass
or leaves) always use the same variant.

Pose math uses the same render convention as the batched renderer. The
structure pose already refers to the structure origin, so voxel coordinates are
**unshifted**:

```
world = structurePose.position
      + structurePose.rotation * (voxel * blockScale)
```

The block model spans `0..1`, so the `PoseStack` receives:

```java
pose.translate(worldMin - cameraPos);
pose.rotate(structureRotation);
pose.scale(blockScale, blockScale, blockScale);
```

The world position is computed in double precision first and only then converted
to camera-relative floats. This avoids precision loss for structures far from the
origin.

Lighting is one sample per structure at its pose position, using
`LightCoordsUtil.getLightCoords(client.level, pos)`; if that fails, full-bright
is used. There is no per-block or per-face ambient occlusion/lighting.

## Fallback path

If a local structure has no snapshot (oversized volume, snapshot disabled, or
the producer was never registered), the renderer draws one coloured,
depth-tested cuboid per merged collider box using
`RenderTypes.debugFilledBox()`. This is the same visual language as the old
`StructureBlockRenderer`, but it is emitted from the new file. In multiplayer
there are no `ClientPhysicsView` structures, so this path is not reached.

## Limitations

* **No multiplayer snapshots in this path.** This renderer reads
  `ClientPhysicsView`, which only exists against an integrated server. Use
  `BatchedStructureRenderer` for `ClientStructureCache` network snapshots.
* **No server-side textures or models.** Only block states are copied. The
  client resolves models from its own resource pack set. A server-only modded
  block has no client model and renders as the missing model.
* **Block entities are not rendered.** Signs, chests, banners, etc. show only
  their block model, not their block-entity renderer.
* **Fluids are not rendered.** Non-solid fluid voxels are treated as air.
* **`MutableVoxelVolume` defaults to stone.** States placed through the
  interaction layer are used when available; otherwise the voxel is stone.
* **No per-face lighting or ambient occlusion.** One light value is used for a
  whole structure and quads are not re-lit against neighbouring structure
  blocks.
* **No interior-face culling.** Every cached model quad is submitted, including
  faces hidden by adjacent structure blocks. Depth testing hides them visually,
  but they still cost GPU work.
* **No per-block random variants.** All blocks of a type use the same baked
  variant because model parts are cached per state.
* **Translucent ordering is approximate.** The moving-block render type batches
  translucent geometry, but blocks inside one structure are not sorted
  back-to-front.
* **Tint sources are not evaluated.** Untinted blocks render correctly; blocks
  that rely on biome/block tint (grass, leaves) render with the neutral tint.
* **Large structures fall back to debug cuboids** above the snapshot caps.
* **Snapshot latency.** Visual block edits appear within the rebuild interval
  (up to four server ticks).
