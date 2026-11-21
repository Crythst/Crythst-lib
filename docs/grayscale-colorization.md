# Grayscale colorization

This document describes `io.mikaple.crythst.client.texture.GrayscaleColorizer`, a
pure-Java utility that turns a grayscale mask into a tinted image with one or two
base colours. It is pixel maths only: no renderer, no `NativeImage`, and no
Minecraft classes, so it can be unit-tested directly.

For alternative, smoother and vanilla-style mappings (`SMOOTH_RAMP`,
`VANILLA_BANDS`, `MULTIPLY`, `LINEAR_LIGHT`) built on top of this compatibility
implementation, see [`grayscale-formulas.md`](grayscale-formulas.md).

## 1. The formula

For one output RGB channel `R` and a grayscale mask value `h`:

```text
H = 255
f(R, h) = (1 - |2h/H - 1|) * R + floor(2h/H) * (h - H/2)
```

The expression is evaluated independently for the red, green and blue channels of
a base colour. The source alpha is copied through unchanged; it is not an input to
the formula.

The implementation computes the weight and the lift term as `double`s. It rounds
and clamps exactly once, when converting the result back to an integer:

```java
final double ramp = (2.0 * gray) / H;
final double weight = 1.0 - Math.abs(ramp - 1.0);
final double lift = Math.floor(ramp) * (gray - HALF_H);
final double value = weight * colorChannel + lift;
return clampToByte((int) Math.round(value));
```

`Math.round(double)` is half-up for the non-negative values produced here; for
example `254.5` becomes `255`. The output is always in `0..255`. Inputs outside
`0..255` are rejected with `IllegalArgumentException` rather than being wrapped.

### 1.1 Piecewise behaviour for integer h

Let `t = 2h/255`. Then `weight = 1 - |t - 1|` and `lift = floor(t) * (h - 127.5)`.

| `h` range | `weight` | `floor(2h/255)` | Result |
| --- | --- | --- | --- |
| `0..127` | `2h/255` | `0` | `(2h/255) * R` |
| `128..254` | `2 - 2h/255` | `1` | `(2 - 2h/255) * R + (h - 127.5)` |
| `255` | `0` | `2` | `2 * 127.5 = 255` |

Endpoints and sample values for `R = 255` and `R = 0`:

| `h` | `f(255, h)` | `f(0, h)` | Note |
| --- | --- | --- | --- |
| `0` | `0` | `0` | black |
| `64` | `128` | `0` | low segment |
| `127` | `254` | `0` | just below the middle |
| `128` | `255` (254.5 rounds up) | `1` (0.5 rounds up) | just above the middle |
| `192` | `191` (190.5 rounds up) | `65` | middle segment |
| `254` | `129` (128.5 rounds up) | `127` | middle segment, near the end |
| `255` | `255` | `255` | `floor(2) = 2` forces white |

So the intended reading is: **black mask -> black, middle mask -> the base
colour, white mask -> white**. The low half darkens the base colour towards
black. The high half adds an increasingly large lift, and the final value uses
`floor(2) = 2`, which lands exactly on 255.

### 1.2 The 127/128 discontinuity

`floor(2h/255)` changes from `0` to `1` between `h = 127` and `h = 128`.
Therefore the formula has a deliberate `+0.5` jump at that boundary:

```text
f(0, 127)   = 0.0
f(0, 128)   = 0.5
f(255, 127) = 254.0
f(255, 128) = 254.5
```

There is also a large jump from `h = 254` to `h = 255`, because the floor term
becomes `2` only at the very last value. These are properties of the requested
formula. The implementation does not smooth or "fix" them, and the tests assert
the exact values.

## 2. Reading `h` from a grayscale PNG

A strict grayscale mask has `r == g == b` for every pixel; any one channel is
then a valid `h`. If an image may contain accidental colour (for example
anti-aliasing from a paint program), use the maximum channel so the mask stays
conservative:

```java
static int grayOf(int argb) {
    int r = (argb >>> 16) & 0xFF;
    int g = (argb >>> 8) & 0xFF;
    int b = argb & 0xFF;
    return Math.max(r, Math.max(g, b)); // identical to r on a strict mask
}
```

`GrayscaleColorizer.grayOf` implements exactly this. You can also use the red
channel directly when the asset pipeline guarantees a strict mask.

When loading with ImageIO, convert to `TYPE_INT_ARGB` so `getRGB` returns standard
ARGB, and do not apply a colour profile or gamma twice:

```java
BufferedImage image = ImageIO.read(path);
if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
    BufferedImage converted = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = converted.createGraphics();
    g.drawImage(image, 0, 0, null);
    g.dispose();
    image = converted;
}
int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(),
        null, 0, image.getWidth());
```

Alpha is separate from `h`. The mask value says how to tint a pixel; the alpha
says whether the pixel is visible. Keep both. Transparent pixels should normally
be excluded from mask processing or copied through untouched.

## 3. Tinting a mask

One pixel:

```java
int tinted = GrayscaleColorizer.tintArgb(0xFF808080, 0xFFCC4422);
```

A whole image:

```java
int[] tinted = GrayscaleColorizer.tint(sourcePixels, 0xFFCC4422);
```

`tint` always allocates a new array. Neither `tint` nor the outline overloads
mutate their input.

## 4. The outline pass

`tintWithOutline` uses the second colour for covered pixels that touch empty
space, and the first colour for the interior:

```java
int[] result = GrayscaleColorizer.tintWithOutline(
        sourcePixels, width, height,
        primaryColor, outlineColor,
        OutlineMask.Mode.FOUR_NEIGHBOURS);
```

The three-argument convenience overload assumes a square image and infers the
side from the array length:

```java
int[] result = GrayscaleColorizer.tintWithOutline(sourcePixels, primary, outline);
```

### 4.1 Edge detection

`OutlineMask` works on source alpha:

* A pixel is **covered** when its alpha is greater than `alphaThreshold`
  (default `0`, i.e. only fully transparent pixels are empty).
* A covered pixel is an **outline** pixel when at least one neighbour has alpha
  at or below the threshold.
* `Mode.FOUR_NEIGHBOURS` checks up/down/left/right.
* `Mode.EIGHT_NEIGHBOURS` also checks the four diagonals. This differs on shapes
  such as a plus sign, where the centre has four covered orthogonal neighbours but
  transparent diagonal corners.
* Neighbours just outside the image are treated as transparent, so the image
  border is outlined. Holes inside a shape are outlined too.
* Fully transparent pixels are never outlined.

You can reuse the mask with different palettes:

```java
boolean[] outline = OutlineMask.detect(
        sourcePixels, width, height, OutlineMask.Mode.EIGHT_NEIGHBOURS, 0);
```

### 4.2 Why detect the outline before tinting

Detect edges from the **source coverage mask**, not from the tinted result:

1. Tinting changes RGB, so colour differences are no longer a reliable mask.
2. The outline colour is a *derived* colour. Running edge detection first lets
   you change either palette without changing which pixels are outline pixels.
3. The same alpha mask can drive several outputs (normal map, emissive map,
   shadow mask) consistently.

The outline pass is still formula-driven. The second colour is not painted flatly;
its channels are passed through the same `f(R, h)`. At mid-gray it looks like the
outline colour, while dark mask areas darken it and bright areas lighten it. If a
completely flat outline is wanted, that is a separate compositing operation, not
this formula.

## 5. Palette record

`GrayscalePalette` holds the primary and outline colours:

```java
GrayscalePalette palette = GrayscalePalette.of(0xFFCC4422, 0xFF221100);
int[] result = GrayscaleColorizer.tintWithOutline(
        sourcePixels, width, height, palette, OutlineMask.Mode.FOUR_NEIGHBOURS);
```

The record validates alpha. `0xRRGGBB` literals are normalised to opaque
`0xFFRRGGBB`; fully opaque values are accepted; partially transparent palette
entries are rejected. Palette alpha is never used by the tint itself because the
source alpha is preserved.

## 6. Premultiplied vs straight alpha

The formula assumes **straight (non-premultiplied) alpha**: RGB is stored
independently of alpha. If pixels are premultiplied:

1. Divide RGB by alpha to obtain straight RGB (`0` when alpha is `0`).
2. Read `h` and tint.
3. Multiply RGB by alpha again.

Tinting premultiplied RGB directly gives darker, inconsistent edges. If the
source has already lost RGB under alpha `0`, it cannot be recovered; keep a
separate coverage mask when this matters.

## 7. Minecraft `NativeImage` note

This utility intentionally does not depend on `NativeImage`. If you adapt it,
check the channel packing and unpack to ARGB first. Minecraft's `NativeImage`
stores bytes in memory as RGBA; on little-endian machines `getPixel` commonly
returns an `0xAABBGGRR`-shaped int, not the `0xAARRGGBB` used by this package.
Do not pass that int directly to `tintArgb` without swapping red and blue.

Also convert straight/premultiplied alpha as described above. The tint pass
should produce straight ARGB, matching the input convention.
