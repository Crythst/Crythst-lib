# Runtime entity textures

This document describes the runtime grayscale texture pipeline in
`io.mikaple.crythst.client.texture.runtime`. It turns a grayscale PNG into a
tinted `DynamicTexture`, registers it immediately with the vanilla
`TextureManager`, and exposes it as an `Identifier` or a ready-made
`RenderType` for entity rendering.

The pipeline reuses the pure-Java mask maths from
[`GrayscaleColorizer`](grayscale-colorization.md). It never triggers a resource
pack or data pack reload.

## 1. Files

| File | Role |
| --- | --- |
| `client/texture/runtime/RuntimeTextureKey.java` | Pure record: normalises colours and builds the deterministic dynamic identifier path. No Minecraft dependency. |
| `client/texture/runtime/RuntimeTextureCache.java` | Loads the grayscale PNG, tints it, creates a `DynamicTexture`, registers it, and owns its lifetime. |
| `client/texture/runtime/EntityGrayscaleTextures.java` | Entity-facing facade: `textureFor(...)` plus `armor`, `entityCutout`, `entitySolid`, `entityTranslucent`, item variants. |
| `client/texture/runtime/RuntimeArmorTextures.java` | Opt-in diamond-armor demonstration; disabled unless a JVM property is set. |
| `client/texture/runtime/mixin/EquipmentLayerRendererRuntimeTextureMixin.java` | Optional 26.3 armor hook, loaded only by `crythst-texture.mixins.json`. |
| `resources/crythst-texture.mixins.json` | Separate mixin config, registered by this repository's `fabric.mod.json`. The demo inside it stays disabled unless the JVM property is set. |
| `test/.../RuntimeTextureCacheTest.java` | Pure key/identifier and colorizer integration tests. |

The bundled masks used by the demo live under
`assets/crythst/textures/test/grayscale/`:

| Mask | Size | Use |
| --- | --- | --- |
| `humanoid_diamond.png` | 64x32 | humanoid armor layer 1 |
| `humanoid_leggings_diamond.png` | 64x32 | humanoid armor layer 2 |
| `horse_body_diamond.png` | 64x64 | horse body armor |
| `nautilus_body_diamond.png` | 128x128 | nautilus body armor |
| `*.png` | 16x16 | item masks |

## 2. Quick start

Generate an identifier and a render type for an entity layer:

```java
Identifier mask = Identifier.fromNamespaceAndPath(
        "crythst", "textures/test/grayscale/humanoid_diamond.png");

Identifier texture = EntityGrayscaleTextures.textureFor(mask, 0xFF55DDDD);
RenderType armorType = EntityGrayscaleTextures.armor(mask, 0xFF55DDDD);

// Two-colour variant: primary fill + outline derived from source alpha.
RenderType outlined = EntityGrayscaleTextures.armor(
        mask, 0xFF55DDDD, 0xFF0B6666);
```

The identifier is stable for the same `(source, primary, outline)` tuple. A
second call returns the existing identifier and does not upload another texture.

`textureFor` must run on the client/render thread. From another thread use:

```java
EntityGrayscaleTextures.textureForAsync(mask, 0xFF55DDDD)
        .thenAccept(texture -> { /* render-thread completion */ });
```

## 3. No-reload guarantee

The implementation deliberately contains no call to:

* `Minecraft.reloadResourcePacks()`
* `TextureManager.reload(...)`
* `ResourceManager` reload machinery
* `TextureManager.registerForNextReload(...)`

The only use of the client `ResourceManager` is
`getResource(sourceId)` to read the grayscale PNG. Reading a resource does not
start a reload. Registration is a direct, immediate
`TextureManager.register(id, texture)`.

This is different from a resource-pack reload:

| Operation | What it does | Effect on generated textures |
| --- | --- | --- |
| `/reload` or resource-pack change | Re-reads pack resources, models, atlases, shaders; vanilla texture reload re-runs `ReloadableTexture`s | Generated `DynamicTexture`s are **not** `ReloadableTexture`s, so vanilla skips them. They remain registered. |
| `RuntimeTextureCache.get(...)` | Reads one grayscale PNG, tints pixels, creates one GPU texture, registers it | Immediate; visible next frame |
| `RuntimeTextureCache.clear()` / `releaseAll()` | Unregisters and closes generated textures | They disappear; next `get` creates a new texture |

Because the generated textures are not reloadable, they also do not get
re-uploaded after a reload. If a caller wants regenerated output after changing
its own palette/config, it must call `release(...)` or `clear()` explicitly.

## 4. 26.3 upload details

`DynamicTexture` changed shape in 26.3:

* The `DynamicTexture(Supplier<String>, NativeImage)` constructor allocates a
  single-mip **`RGBA8_UNORM`** GPU texture and calls `upload()` **once** itself.
  Do not call `upload()` again; doing so would upload a second time.
* `NativeImage.read(InputStream)` always returns `Format.RGBA`, so reading a
  grayscale or indexed PNG is already normalised.
* `NativeImage.getPixels()` returns **ARGB** ints in 26.3 (it converts from the
  internal ABGR layout). Tinting is therefore done directly in the convention
  used by `GrayscaleColorizer`.
* `NativeImage.setPixel(x, y, argb)` converts ARGB to the internal ABGR byte
  order before the GPU upload.
* There are **no mipmaps** and no atlas stitching. The GPU texture has one mip
  level, and the dynamic texture uses the vanilla NEAREST/REPEAT sampler. Draw
  UI-scale or pixel-art armor masks rather than expecting mip-filtered detail.
* The source PNG's own format is not preserved on the GPU: 26.3's
  `DynamicTexture` always creates an RGBA8 texture, so the cache re-encodes to
  `NativeImage.Format.RGBA` explicitly.

The cache closes the decoded source image after tinting. The resulting
`DynamicTexture` keeps its own CPU-side RGBA image for the texture's lifetime.

## 5. Cache key and identifier

`RuntimeTextureKey` is a plain record:

```java
record RuntimeTextureKey(String sourceId, int primaryColor, int outlineColor)
```

* Colours are normalised through `GrayscalePalette`, so `0xRRGGBB` and
  `0xFFRRGGBB` are the same key. Partially transparent palette entries are
  rejected.
* `get(source, color)` is the same key as
  `get(source, color, color)`, so the one-colour and equal-outline calls share
  one texture.
* The dynamic id path is
  `dynamic/entity/<sha256(source + primary + outline)>` in the `crythst`
  namespace. It is deterministic across JVMs and across cache lifetimes, so a
  `clear()` followed by the same request produces the same identifier.
* Repeated calls are guarded by a `ConcurrentHashMap` plus a creation lock.
  Concurrent callers cannot register two textures for one key.

## 6. Threading and lifecycle

`RuntimeTextureCache` is safe to share, but texture creation is not thread-safe
with the GPU:

* `get(...)`, `release(...)`, and `releaseAll()` require the Minecraft
  client/render thread. They throw `IllegalStateException` otherwise.
* `getAsync(...)` checks `Minecraft.isSameThread()`; it completes immediately on
  the render thread and otherwise submits through `Minecraft.submit`.
* `clear()` is the asynchronous-safe form: it schedules `releaseAll()` on the
  client thread.

Release uses `TextureManager.release(id)`, which removes the entry and calls
`AbstractTexture.close()`. The cache does not call `DynamicTexture.close()` a
second time.

Register a shutdown hook if your mod generates many textures:

```java
ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
        EntityGrayscaleTextures.clear());
```

## 7. Diamond armor integration in 26.3

Armor rendering is render-state based. `HumanoidArmorLayer` extracts a
`HumanoidRenderState`, picks an `ArmorModelSet` model, and calls
`EquipmentLayerRenderer.renderLayers(...)` from `submit`. There is no
`bindTexture` call to override.

`ArmorModelSet` only selects the head/chest/legs/feet model geometry; it does
not contain a texture. Hooking `ArmorModelSet` therefore cannot change the
texture. `HumanoidArmorLayer.renderArmorPiece` is private and only chooses the
`EquipmentClientInfo.LayerType` before delegating. The texture lookup itself
happens in `EquipmentLayerRenderer`, which is the stable seam.

The useful seam is the pair of `EquipmentLayerRenderer.renderLayers` overloads:

```java
// 9 arguments: vanilla forwards to the 11-argument method with texture = null.
renderLayers(layerType, assetId, model, state, stack, poseStack,
        collector, light, outlineColor);

// 11 arguments: explicit texture override.
renderLayers(layerType, assetId, model, state, stack, poseStack,
        collector, light, texture, outlineColor, order);
```

A caller that already owns the submit path can pass a generated identifier
directly:

```java
Identifier generated = EntityGrayscaleTextures.textureFor(
        Identifier.fromNamespaceAndPath(
                "crythst", "textures/test/grayscale/humanoid_diamond.png"),
        0xFF55DDDD, 0xFF0B6666);

equipmentRenderer.renderLayers(
        layerType, assetId, model, state, stack, poseStack, collector,
        light, generated, outlineColor, 1);
```

When `generated` is `null`, this is exactly vanilla's lookup path. The optional
mixin in this repository uses an `@Redirect` on the forwarding call to do this
without touching models or vanilla classes.

### 7.1 Optional demo mixin

The demo is opt-in through the JVM property; the mixin itself is registered in
`fabric.mod.json` in this repository. If you copy the package into a mod that
does not list it, add the config:

```json
"mixins": [
  "crythst.mixins.json",
  "crythst-texture.mixins.json"
]
```

Then set:

```text
-Dcrythst.runtimeTextures.diamondArmor=true
```

The config uses `"required": false` and `"defaultRequire": 0`, so if the 26.3
target changes the hook logs a warning instead of crashing. With the property
unset, diamond armor is rendered by the exact vanilla path.

`RuntimeArmorTextures` then returns generated textures only for
`minecraft:diamond` humanoid armor layers:

* `HUMANOID` / `HUMANOID_BABY` -> `humanoid_diamond.png`
* `HUMANOID_LEGGINGS` -> `humanoid_leggings_diamond.png`
* everything else -> `null`, i.e. vanilla

The property is read with `Boolean.getBoolean` on each lookup, so it can be
changed before first use (or by a launcher) without code changes. The demo uses
`0xFF55DDDD` as the primary colour and `0xFF0B6666` as the outline.

For production, replace `RuntimeArmorTextures.textureFor` with your own lookup
keyed by `EquipmentClientInfo.LayerType` and
`ResourceKey<EquipmentAsset>`, or call `EntityGrayscaleTextures.armor(mask,
color)` directly from a custom layer.

## 8. Entity renderers and items

For a custom entity renderer, choose the generated render type in the submit
path:

```java
@Override
public void submit(EntityRenderState state, PoseStack poseStack,
                   SubmitNodeCollector collector, CameraRenderState camera) {
    RenderType type = EntityGrayscaleTextures.entityCutout(mask, 0xFF55DDDD);
    collector.order(0).submitModel(model, state, poseStack, type, light, ...);
}
```

Available helpers:

| Helper | Vanilla render type |
| --- | --- |
| `armor(...)` | `armorCutoutNoCull` |
| `armorGlint(...)` | `armorCutoutNoCullGlint` |
| `entityCutout(...)` | `entityCutout` (no-cull in vanilla) |
| `entityCutoutCull(...)` | `entityCutoutCull` |
| `entitySolid(...)` | `entitySolid` |
| `entityTranslucent(...)` | `entityTranslucent` |
| `entityTranslucentEmissive(...)` | `entityTranslucentEmissive` |
| `itemCutout(...)` | `itemCutout` |
| `itemTranslucent(...)` | `itemTranslucent` |

Vanilla memoises these render-type factories, so repeated calls are cheap.

**Items are different from entities.** An ordinary item model resolves
`layer0` through the item atlas (`minecraft:item/...`); model JSON cannot point
at a standalone dynamic texture. To use a generated texture for an item you must
either:

1. write a custom special-model renderer that submits geometry with
   `RenderTypes.itemCutout(dynamicId)` / `itemTranslucent(dynamicId)`, or
2. build your own atlas/sprite binding.

`EntityGrayscaleTextures.itemCutout(...)` provides the render type for the first
approach; it does not mutate the item atlas.

## 9. Memory cost

For a texture of `W x H` pixels:

* GPU: `W * H * 4` bytes (one RGBA8 mip level).
* CPU: `W * H * 4` bytes retained by `DynamicTexture` plus `W * H * 4` bytes
  transiently during ARGB tinting.
* No atlas page is allocated; each unique `(source, colours)` is its own GPU
  texture object.

Examples:

| Size | GPU per texture | Retained CPU |
| --- | --- | --- |
| 16x16 | 1 KiB | 1 KiB |
| 64x32 | 8 KiB | 8 KiB |
| 64x64 | 16 KiB | 16 KiB |
| 128x128 | 64 KiB | 64 KiB |

The cache does not evict automatically. If colors are data-driven (player dyes,
team colors, etc.), bound the palette or call `release(...)`/`clear()` when a
color is no longer used.

## 10. 26.3 limitations and caveats

* **Render thread only.** GPU upload and registration cannot happen on a worker
  thread; use `getAsync` from there.
* **No mipmaps or atlas stitching.** Dynamic textures are single-level and not
  part of any atlas. Animated textures and `.mcmeta` data are not applied.
* **No per-frame updates.** `get` generates once per key. For animated output,
  keep the `DynamicTexture` around and call `setPixels` + `upload` yourself; the
  cache API intentionally does not expose that.
* **No persistence.** Generated textures exist only in memory and are not
  written to disk or sent to clients.
* **No automatic resource reload.** Changing the source PNG on disk has no
  effect until the resource manager sees it (normally after a pack reload) and
  the cache entry is released and regenerated.
* **Identifier determinism is input-based.** The id depends only on the source
  identifier string and normalised colours, not on file contents.
* **Resource manager read may hit disk.** The first request for each source can
  do I/O on the render thread. Prewarm with `getAsync` before the first frame
  that needs the texture if a hitch matters.
* **Demo mixin is disabled by default.** The mixin config is registered, but
  `RuntimeArmorTextures` returns `null` unless
  `-Dcrythst.runtimeTextures.diamondArmor=true` is set. Without that property,
  vanilla diamond armor is untouched.

## 11. Tests

Pure tests cover key equality, colour normalisation, identifier determinism and
the colorizer integration without starting Minecraft:

```bash
./gradlew test --tests 'io.mikaple.crythst.client.texture.runtime.RuntimeTextureCacheTest'
```

The Minecraft-dependent upload path is compile-checked by `compileJava`.
