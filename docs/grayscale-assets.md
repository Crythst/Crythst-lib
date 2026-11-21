# Vanilla diamond grayscale assets

This document covers the test asset set under
`src/main/resources/assets/crythst/textures/test/`. It contains vanilla
Minecraft 26.3 diamond equipment and weapon textures converted to strict
grayscale masks, several outputs of the runtime tint formula, and a contact
sheet.

The masks are the input format for the runtime texture cache used by entity and
equipment rendering: a single grayscale mask is tinted in memory for a palette
and the result is cached, instead of shipping one texture per colour. The pixel
maths lives in `io.mikaple.crythst.client.texture.GrayscaleColorizer`; see
[`grayscale-colorization.md`](grayscale-colorization.md) for the formula in
depth.

## 1. Layout

```text
src/main/resources/assets/crythst/textures/test/
├── grayscale/
│   ├── sword.png
│   ├── spear.png
│   ├── ...
│   ├── humanoid.png
│   └── humanoid_leggings.png
├── color/
│   ├── sword_cyan.png
│   ├── sword_purple.png
│   ├── sword_gold.png
│   ├── chestplate_cyan.png
│   ├── humanoid_cyan.png
│   ├── sword_cyan_outline_magenta.png
│   └── humanoid_cyan_outline_magenta.png
└── grayscale_contact_sheet.png
```

All images are 8-bit RGBA PNGs with straight (non-premultiplied) alpha. Item
masks are 16x16, humanoid armour layers are 64x32, and the optional horse and
nautilus body layers are 64x64 and 128x128.

## 2. Source textures

The sources are read from the mapped 26.3 jar at
`assets/minecraft/textures/...`:

| Source in the Minecraft jar | Grayscale output | Size |
| --- | --- | --- |
| `item/diamond_sword.png` | `grayscale/sword.png` | 16x16 |
| `item/diamond_spear.png` | `grayscale/spear.png` | 16x16 |
| `item/diamond_pickaxe.png` | `grayscale/pickaxe.png` | 16x16 |
| `item/diamond_axe.png` | `grayscale/axe.png` | 16x16 |
| `item/diamond_shovel.png` | `grayscale/shovel.png` | 16x16 |
| `item/diamond_hoe.png` | `grayscale/hoe.png` | 16x16 |
| `item/diamond_helmet.png` | `grayscale/helmet.png` | 16x16 |
| `item/diamond_chestplate.png` | `grayscale/chestplate.png` | 16x16 |
| `item/diamond_leggings.png` | `grayscale/leggings.png` | 16x16 |
| `item/diamond_boots.png` | `grayscale/boots.png` | 16x16 |
| `entity/equipment/humanoid/diamond.png` | `grayscale/humanoid.png` | 64x32 |
| `entity/equipment/humanoid_leggings/diamond.png` | `grayscale/humanoid_leggings.png` | 64x32 |
| `item/diamond_horse_armor.png` | `grayscale/horse_armor.png` | 16x16 |
| `item/diamond_nautilus_armor.png` | `grayscale/nautilus_armor.png` | 16x16 |
| `entity/equipment/horse_body/diamond.png` | `grayscale/horse_body.png` | 64x64 |
| `entity/equipment/nautilus_body/diamond.png` | `grayscale/nautilus_body.png` | 128x128 |

The last four are optional extras that exercise the non-humanoid equipment
paths.

## 3. Grayscale conversion

Each pixel keeps its straight alpha; only RGB is rewritten. The mask value `h`
is BT.601 luma:

```text
h = round(0.299 * R + 0.587 * G + 0.114 * B)
```

Rounding is half-up. The generator uses exact integer arithmetic so no floating
point rounding error can leak in:

```text
h = (299 * R + 587 * G + 114 * B + 500) / 1000   // integer division
```

Rules:

* **Strict grayscale:** every pixel, including fully transparent pixels, has
  `r == g == b == h`, so `GrayscaleColorizer.grayOf` can read any channel.
* **Alpha is preserved byte-for-byte.** The vanilla textures use binary alpha
  (0 or 255), but the conversion does not assume that.
* **No premultiplication.** RGB is stored independently of alpha. Transparent
  pixels keep their converted (hidden) RGB, which is harmless because tinting
  copies source alpha and transparent pixels stay invisible.
* No colour profile or gamma correction is applied. The PNG samples are treated
  as sRGB bytes, matching how the runtime reads a `NativeImage`.

The test asset set intentionally uses grayscale rather than a normal map: the
formula only needs one intensity channel, and a single channel is enough to
reconstruct the shading steps for every palette.

## 4. Tint formula

For an output channel `R` of a base colour and a mask value `h` in `0..255`:

```text
H = 255
f(R, h) = (1 - |2h/H - 1|) * R + floor(2h/H) * (h - H/2)
```

The evaluated channel is rounded half-up and clamped to `0..255`. Source alpha
is copied through unchanged and never participates in the formula. In short:
black mask is black, mid-gray is the base colour, and a white mask is always
white.

## 5. Two-colour outline pass

`GrayscaleColorizer.tintWithOutline` applies the same formula twice:

1. `OutlineMask` reads the **source alpha** and marks a covered pixel as an
   outline pixel when at least one four-neighbour (or eight-neighbour) pixel is
   empty. Pixels just outside the image count as empty, so the silhouette is
   outlined, and transparent pixels are never outlined.
2. Interior pixels are tinted with the primary colour; outline pixels are tinted
   with the second colour. The second colour is not painted flatly: it goes
   through `f(R, h)` too, so its own light and dark mask steps survive.
3. Fully transparent pixels are copied through unchanged.

The checked-in examples use cyan (`0x00FFFF`) as the primary and magenta
(`0xFF00FF`) as the outline:

```text
color/sword_cyan_outline_magenta.png
color/humanoid_cyan_outline_magenta.png
```

## 6. Runtime texture cache

Rendering equipment per frame must not re-tint a PNG. The runtime texture cache
is keyed by `(mask identity, palette)` and stores the already-tinted
`NativeImage`/texture handle:

* One grayscale mask per equipment slot/layer (helmet, chestplate, leggings,
  boots, horse body, nautilus body).
* A palette per material/colour variant. The primary colour is the material
  colour; the optional outline colour is used for the silhouette pass.
* The cache is populated on first use on the render thread, and the same tinted
  texture is reused for every entity wearing that combination.
* Because the mask has the vanilla alpha, the cached texture can be composited
  exactly like the vanilla equipment texture, including the `humanoid` and
  `humanoid_leggings` layer split.

`GrayscaleColorizer` deliberately has no Minecraft dependency. The client
adapter converts `NativeImage` RGBA bytes to ARGB, calls the colorizer, and
converts back; see section 7 of
[`grayscale-colorization.md`](grayscale-colorization.md) for the channel-order
pitfall.

## 7. Tested colours and expected values

The test outputs use these base colours:

| Name | ARGB |
| --- | --- |
| cyan | `0xFF00FFFF` |
| purple | `0xFF8000FF` |
| gold | `0xFFFFD700` |
| magenta (outline) | `0xFFFF00FF` |

Expected channel values at selected mask values (from
`GrayscaleColorizer.tintChannel`, also asserted in `GrayscaleAssetsTest`):

| Colour | `h = 0` | `h = 64` | `h = 128` | `h = 192` | `h = 255` |
| --- | --- | --- | --- | --- | --- |
| cyan `#00FFFF` | `#000000` | `#008080` | `#01FFFF` | `#41BFBF` | `#FFFFFF` |
| purple `#8000FF` | `#000000` | `#400080` | `#8001FF` | `#8041BF` | `#FFFFFF` |
| gold `#FFD700` | `#000000` | `#806C00` | `#FFD701` | `#BFAB41` | `#FFFFFF` |

The exact checksums in the test come from the vanilla sources: width, height,
alpha sum, covered pixel count and the sum of the BT.601 mask values over
covered pixels. That verifies the alpha channel and the conversion formula
without embedding the vanilla texture bytes.

## 8. Regenerating the assets

`tools/grayscale_assets.py` is dependency-free (standard library only) and
decodes palette, grayscale, RGB and RGBA PNGs itself:

```bash
# Write all grayscale, colour and contact-sheet images.
python3 tools/grayscale_assets.py

# Only decode and print the checksum table (no files written).
python3 tools/grayscale_assets.py --check
```

Use `--jar <path>` when the mapped jar lives somewhere else. The default path is
the 26.3 Fabric Loom cache used by this project.

Finally run the asset test:

```bash
./gradlew test --no-daemon --tests '*GrayscaleAssetsTest*'
```

`grayscale_contact_sheet.png` arranges the 16 masks alphabetically in a 4-column
grid on a dark checkerboard so transparent pixels are visible; each cell is
scaled by the largest integer factor that fits 128x128.
