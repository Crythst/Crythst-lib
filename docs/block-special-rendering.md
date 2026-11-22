# Block special rendering without `BlockEntityRenderer#submit`

This document describes `io.mikaple.crythst.client.render.block`, a small
client-only API for drawing a block with a custom model/shader while keeping
the block itself invisible to the chunk mesher.

The design is based on EnderTech's Enderite block renderer, but replaces the
block-entity path with a client-tick position scanner plus one level-render
submission pass. A block does **not** need a `BlockEntity`, a
`BlockEntityType`, or a `BlockEntityRenderer` to use it.

## Files

| File | Purpose |
| --- | --- |
| `SpecialBlockRenderer.java` | Functional callback for one block submission. |
| `SpecialBlockRenderContext.java` | Immutable per-block data (pos, state, level, partial tick, camera position, distance). |
| `SpecialBlockRenderers.java` | `Block -> SpecialBlockRenderer` registry. |
| `SpecialBlockRenderManager.java` | Client-tick scanner, cache pruning, camera-relative `BEFORE_GIZMOS` submission. |
| `SpecialBlockLight.java` | Light sampling with the EnderTech solid-block neighbour correction. |
| `ItemModelBlockRenderer.java` | Example renderer: a block drawn through its own item model. |

## Registration

Register the manager once, from the client initializer:

```java
public final class ExampleClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Installs the client-tick scanner and the level render hook.
        SpecialBlockRenderManager.register();

        // Pick one:
        ItemModelBlockRenderer.register(MyBlocks.STAR_BLOCK);
        // or, equivalently:
        // SpecialBlockRenderers.register(MyBlocks.STAR_BLOCK,
        //         new ItemModelBlockRenderer(MyBlocks.STAR_BLOCK));
    }
}
```

For the Crythst mod, the call belongs in
`io.mikaple.crythst.integration.fabric.client.CrythstClient#onInitializeClient`:

```java
SpecialBlockRenderManager.register();
ItemModelBlockRenderer.register(MyBlocks.STAR_BLOCK);
```

`SpecialBlockRenderManager.register()` is idempotent. Registering a renderer
after the manager is also fine; the next scan picks it up.

## The invisible/particle-only block model

The normal chunk mesh must not draw the block, otherwise the special model and
the vanilla cube overlap (z-fighting, double brightness, wrong transparency).

1. Make the block's render shape invisible:

```java
public final class StarBlock extends Block {
    public StarBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
```

2. Give the blockstate a **particle-only** model: zero elements and a particle
   texture. The particle texture is still used by break particles and by the
   moving-block fallback where applicable.

`assets/<namespace>/blockstates/star_block.json`:

```json
{
  "variants": {
    "": { "model": "<namespace>:block/star_block" }
  }
}
```

`assets/<namespace>/models/block/star_block.json`:

```json
{
  "textures": {
    "particle": "<namespace>:block/star_block"
  },
  "elements": []
}
```

3. The **item** model is separate and can still be a normal cube or a custom
   special model. `ItemModelBlockRenderer` resolves `new ItemStack(block)`'s
   item model, so the item form is what appears in the world.

This is the same split EnderTech uses: `createParticleOnlyBlock` for the block
model, and a normal/special cube item model for the item form.

## Why this avoids `BlockEntityRenderer#submit`

A `BlockEntityRenderer` is driven by block-entity extraction and has its own
view-distance/culling rules. With an invisible block model, that can make a
block disappear when the BER stops being extracted. In EnderTech the workaround
was a very large `getViewDistance()`.

This API instead:

- stores no block entity and registers no BER;
- scans only loaded chunks around the player on the client tick;
- keeps an immutable `BlockPos -> BlockState` cache and looks the renderer up
  from the registry during submission;
- submits every cached block from one `LevelRenderEvents.BEFORE_GIZMOS`
  listener using the level's `SubmitNodeCollector`.

There is exactly one hook and one submission pass per frame, independent of
block-entity bookkeeping. The block can be a plain `Block`.

## Example renderer: `ItemModelBlockRenderer`

`ItemModelBlockRenderer` reproduces EnderTech's Enderite block concept:

1. Resolve the block's item model with
   `ItemModelResolver#updateForNonLiving(..., ItemDisplayContext.NONE, player)`.
2. Translate `+0.5` on all axes. The item path centres the model around the
   origin; the manager's pose is already at the block's minimum corner, so the
   result occupies exactly the block cell.
3. Call `ItemStackRenderState#submit(poseStack, collector, light, overlay, 0)`.

The render state is re-resolved every frame, like a vanilla item frame, so it
stays correct after resource reloads. The `ItemStack` is created lazily on the
render thread, not in a static initializer: early resource-reload code can run
before item data components are bound, and a static `new ItemStack(...)` then
fails permanently with "Components not bound yet".

`ItemModelBlockRenderer.register(block)` is a convenience that creates the
renderer and registers it in one call.

### Custom renderers

Implement `SpecialBlockRenderer` directly for anything that is not an item
model:

```java
SpecialBlockRenderers.register(MyBlocks.HOLOGRAM,
        (context, poseStack, collector, lightCoords, overlayCoords) -> {
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            // submit quads/geometry through collector here
            poseStack.popPose();
        });
```

The pose is already camera-relative and translated to the block corner. Do not
subtract the camera again.

## Combining with a custom shader

There are two paths.

### 1. Reuse the item special-model pipeline (recommended)

If the block item's item model uses a `minecraft:special` model renderer, then
`ItemModelBlockRenderer` automatically uses it. This is the intended wiring
point for the star shader renderer in
`io.mikaple.crythst.client.render.item.StarItemModelRenderer`:

- The item model JSON points at a `minecraft:special` model whose renderer is
  the star special model.
- The same model is used for GUI, hand, item frames, dropped items, and the
  placed block. No extra block renderer code is needed.
- The star renderer submits the real model first, then a depth-tested star
  overlay with the same geometry. Because `ItemModelBlockRenderer` calls
  `ItemStackRenderState#submit`, the block gets exactly the same two-pass
  depth behaviour as an item in the hand. The overlay reads the depth written
  by the real block model, so stars cannot leak through the block, through
  adjacent blocks, or through terrain that is closer to the camera.
- `ItemModelBlockRenderer` translates `+0.5` on all axes before submitting.
  Both star passes receive that same pose, so their depth values match and no
  per-block depth adjustment is necessary.

The block API intentionally does not reference that class, so it compiles
before the item renderer exists. Once the item renderer is registered, the
existing `ItemModelBlockRenderer.register(block)` wiring picks it up on the
next resource load.

If you need block-specific geometry, use a separate item model for the block
item or submit custom quads directly.

### 2. Submit your own geometry with a custom `RenderType`

From a `SpecialBlockRenderer`, use the same collector APIs vanilla uses:

- `SubmitNodeCollector#submitItem(...)` for item quads (`ItemQuads`), which is
  what `StarItemModelRenderer` does;
- `SubmitNodeCollector#submitCustomGeometry(...)` for arbitrary vertex data;
- `SubmitNodeCollector#submitBlockModel(...)` if you already have baked
  `BlockStateModelPart`s and a `RenderType`.

Keep the shader's light/overlay inputs consistent with the values passed to
`SpecialBlockRenderer#submit`. The manager supplies packed world light and
`OverlayTexture.NO_OVERLAY`.

## Limitations

- **Radius:** blocks are found within `SCAN_RADIUS` (64 blocks) of the player.
  Increase the constant if needed. The scan never loads chunks; it only sees
  chunks already loaded by the client.
- **Refresh rate:** a full scan runs every `SCAN_INTERVAL_TICKS` (20 ticks,
  about one second). Cached entries are pruned every tick, so unloading,
  breaking, or replacing a block disappears immediately. Newly placed blocks
  appear on the next full scan.
- **No per-face culling:** every tracked block submits its full model. There is
  no neighbour occlusion or per-face visibility test. Dense fields of special
  blocks cost linearly in submissions.
- **No frustum culling yet:** the camera render state contains a cull frustum,
  but this implementation deliberately keeps the first version simple. Add a
  `CameraRenderState#cullFrustum.isVisible(AABB)` check in `render` if needed.
- **Lighting is approximate:** block light comes from
  `LightCoordsUtil#getLightCoords(level, pos)`. For solid-render blocks the
  helper takes the maximum of the block's own light and its six neighbours,
  because an opaque full cube stores zero at its own position. This is not
  per-face or smooth lighting. If exact vanilla face lighting matters, bake a
  block model and submit `BlockStateModelPart`s with per-face light instead.
- **Static positions only:** the tracker keys on real `BlockPos`. It does not
  follow pistons, moving blocks, or a physics/sublevel rigid transform. For a
  moving structure, submit from that structure's own renderer.
- **Star depth is item-path depth:** a star block inherits the item renderer's
  two-pass design (real model pass + depth-tested, non-depth-writing overlay).
  RenderPearl has no stencil, so the real model's depth buffer is the
  silhouette mask. This is correct for standard cutout/translucent item
  render types. A custom special model that uses a render type which does not
  write depth loses that mask; see the limitations in
  `docs/item-special-rendering.md`.
- **Improved transparency:** translucent star quads need the item renderer's
  OIT pipeline set. `ItemModelBlockRenderer` itself does not add OIT support;
  it relies on the special model's render types being OIT-complete.
- **Upper bound:** `MAX_TRACKED_BLOCKS` (8192) prevents unbounded growth. Once
  reached, further blocks are ignored until the cache shrinks.
- **No block-entity data:** the context exposes only the block state. If a
  renderer needs block-entity data, it can look it up itself, but that
  reintroduces a block-entity dependency and is not the intended path.
- **Client-only:** the scanner and `SubmitNodeCollector` code must only run on
  the client. The package is not referenced by common/server code.

## Threading and lifecycle

- The scanner runs from `ClientTickEvents.END_CLIENT_TICK`.
- The cache is a `ConcurrentHashMap` of immutable keys and values. The render
  thread iterates it without locking; a concurrent scan cannot corrupt the
  iteration.
- The cache is cleared automatically when `Minecraft#level` changes (including
  world/dimension changes). `SpecialBlockRenderManager.clear()` is also public
  for diagnostics.
- `ItemModelBlockRenderer`'s `ItemStackRenderState` is render-thread state.
  Do not call `submit` from another thread.
- The manager skips submission when there is no level, no cached blocks, or no
  registered renderers.

## 26.3 API notes

- `LightCoordsUtil` is `net.minecraft.util.LightCoordsUtil` (common jar).
  `getLightCoords(BlockAndLightGetter, BlockPos)` and `max(int, int)` are used
  here. `BlockState#isSolidRender()` is the no-argument form in 26.3.
- `ItemModelResolver#updateForNonLiving` requires an `Entity`; a `LocalPlayer`
  is used. `updateForTopItem` is the alternative if you have a `Level`,
  `ItemOwner`, and seed.
- `ItemStackRenderState#submit` has the 26.3 five-argument signature
  `(PoseStack, SubmitNodeCollector, int light, int overlay, int outlineColor)`.
- `ClientChunkCache#getChunkNow(int, int)` returns a nullable `LevelChunk` and
  never forces a load. `ChunkAccess#findBlocks` uses
  `LevelChunkSection#maybeHas` to skip sections whose palette cannot contain a
  registered block.
- `LevelRenderContext#poseStack()` is nullable in the Fabric API, but Fabric's
  `LevelRendererMixin` installs the identity `PoseStack` created at the start of
  `submitFeatures` before `BEFORE_GIZMOS` runs. The collector is the level's
  `SubmitNodeStorage`.
- Fabric injects `BEFORE_GIZMOS` in the middle of `submitFeatures`, before gizmo
  finalisation. `COLLECT_SUBMITS` is the documented generic hook for adding
  submit nodes (injected at the return of `submitFeatures`). Both use the same
  collector and can submit item/block geometry; if a future Fabric version
  changes `BEFORE_GIZMOS`, switch the registration in
  `SpecialBlockRenderManager` to `COLLECT_SUBMITS` without changing renderers.
- Camera-relative submission uses
  `LevelRenderState#cameraRenderState.pos` (the frame's render camera), with
  `Minecraft#gameRenderer.mainCamera().position()` only as a fallback. This is
  the same convention as vanilla entity submission: the event pose stack is
  identity and geometry is translated by `blockPos - cameraPos`.
