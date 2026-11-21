#!/usr/bin/env python3
"""Generate comparison PNGs for the grayscale formula research.

The script reads the already-checked-in strict grayscale masks from
``textures/test/grayscale`` (produced by ``tools/grayscale_assets.py``) and
writes outputs for the formulas in ``GrayscaleFormula``:

* legacy      -- the original user formula, exactly
* smooth      -- C1 smoothstep black -> primary -> white
* vanilla3    -- three quantized vanilla-style shade bands
* vanilla4    -- four quantized vanilla-style shade bands (default)
* multiply    -- hue-preserving multiply, no white highlight
* linear      -- piecewise-linear ramp with an additive white highlight

It also writes one comparison sheet per test colour and a transfer-curve sheet.
Everything uses only the Python standard library; PNG coding is reused from
``grayscale_assets.py``.

Usage:
    python3 tools/grayscale_formula_assets.py
"""

from __future__ import annotations

import math
import os
import sys

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(TOOLS_DIR)
sys.path.insert(0, TOOLS_DIR)

import grayscale_assets as base  # noqa: E402  (path setup must come first)

TEST_ROOT = os.path.join(
    PROJECT_ROOT, "src", "main", "resources", "assets", "crythst", "textures", "test"
)
GRAYSCALE_DIR = os.path.join(TEST_ROOT, "grayscale")
OUTPUT_DIR = os.path.join(TEST_ROOT, "color_smooth")

MASKS = ("sword.png", "chestplate.png", "humanoid.png")

COLORS = {
    "cyan": 0xFF00FFFF,
    "purple": 0xFF8000FF,
    "white": 0xFFFFFFFF,
}

# (file suffix, formula implementation, band count)
FORMULAS = (
    ("legacy", "legacy", 4),
    ("smooth", "smooth", 4),
    ("vanilla3", "vanilla", 3),
    ("vanilla4", "vanilla", 4),
    ("multiply", "multiply", 4),
    ("linear_light", "linear_light", 4),
)

TRANSFER_ROWS = (
    ("MASK", None, 4),
    ("LEGACY", "legacy", 4),
    ("SMOOTH", "smooth", 4),
    ("VANILLA3", "vanilla", 3),
    ("VANILLA4", "vanilla", 4),
    ("MULTIPLY", "multiply", 4),
    ("LINEAR", "linear_light", 4),
)

VANILLA_DARK_FACTOR = 0.5
VANILLA_HIGHLIGHT_MIX = 0.75


# ---------------------------------------------------------------------------
# Formula ports -- keep in sync with SmoothGrayscaleColorizer
# ---------------------------------------------------------------------------


def _round_byte(value: float) -> int:
    """Java Math.round for the non-negative values produced here, then clamp."""
    return max(0, min(255, int(math.floor(value + 0.5))))


def legacy_channel(color_channel: int, gray: int) -> int:
    ramp = (2.0 * gray) / 255.0
    weight = 1.0 - abs(ramp - 1.0)
    lift = math.floor(ramp) * (gray - 127.5)
    return _round_byte(weight * color_channel + lift)


def _smoothstep(u: float) -> float:
    return u * u * (3.0 - 2.0 * u)


def _srgb_to_linear(channel: int) -> float:
    c = channel / 255.0
    if c <= 0.04045:
        return c / 12.92
    return ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(linear: float) -> int:
    c = max(0.0, min(1.0, linear))
    if c <= 0.0031308:
        encoded = 12.92 * c
    else:
        encoded = 1.055 * (c ** (1.0 / 2.4)) - 0.055
    return _round_byte(encoded * 255.0)


def _blend(frm: int, to: int, s: float, linear_space: bool) -> int:
    if linear_space:
        start = _srgb_to_linear(frm)
        end = _srgb_to_linear(to)
        return _linear_to_srgb(start + (end - start) * s)
    return _round_byte(frm + (to - frm) * s)


def smooth_channel(color_channel: int, gray: int, linear_space: bool = False) -> int:
    if gray == 0:
        return 0
    if gray == 255:
        return 255
    t = gray / 255.0
    if t <= 0.5:
        return _blend(0, color_channel, _smoothstep(2.0 * t), linear_space)
    return _blend(color_channel, 255, _smoothstep(2.0 * t - 1.0), linear_space)


def linear_light_channel(color_channel: int, gray: int, linear_space: bool = False) -> int:
    if gray == 0:
        return 0
    if gray == 255:
        return 255
    t = gray / 255.0
    if t <= 0.5:
        return _blend(0, color_channel, 2.0 * t, linear_space)
    return _blend(color_channel, 255, 2.0 * t - 1.0, linear_space)


def multiply_channel(color_channel: int, gray: int) -> int:
    return _round_byte(color_channel * (gray / 255.0))


def vanilla_channel(color_channel: int, gray: int, bands: int) -> int:
    band = min(bands - 1, (gray * bands) // 256)
    if band == 0:
        return 0
    if band == bands - 1:
        return _blend(color_channel, 255, VANILLA_HIGHLIGHT_MIX, False)
    if bands == 3:
        factor = 1.0
    else:
        factor = VANILLA_DARK_FACTOR + (1.0 - VANILLA_DARK_FACTOR) * (band - 1) / (bands - 3)
    return _round_byte(color_channel * factor)


def apply_channel(formula: str | None, color_channel: int, gray: int, bands: int) -> int:
    if formula is None:  # unmodified grayscale reference
        return gray
    if formula == "legacy":
        return legacy_channel(color_channel, gray)
    if formula == "smooth":
        return smooth_channel(color_channel, gray)
    if formula == "vanilla":
        return vanilla_channel(color_channel, gray, bands)
    if formula == "multiply":
        return multiply_channel(color_channel, gray)
    if formula == "linear_light":
        return linear_light_channel(color_channel, gray)
    raise ValueError(f"unknown formula {formula!r}")


def tint_pixels(pixels, color: int, formula: str | None, bands: int = 4):
    """Tint straight-alpha RGBA pixels; alpha is copied, never premultiplied."""
    result = []
    for r, g, b, a in pixels:
        gray = max(r, g, b)
        result.append((
            apply_channel(formula, (color >> 16) & 0xFF, gray, bands),
            apply_channel(formula, (color >> 8) & 0xFF, gray, bands),
            apply_channel(formula, color & 0xFF, gray, bands),
            a,
        ))
    return result


def ramp_pixels(color: int, formula: str | None, width: int, height: int, bands: int = 4):
    """A horizontal h=0..255 ramp, sampled across ``width`` columns."""
    pixels = []
    for _y in range(height):
        for x in range(width):
            gray = round(x * 255 / (width - 1)) if width > 1 else 0
            pixels.append((
                apply_channel(formula, (color >> 16) & 0xFF, gray, bands),
                apply_channel(formula, (color >> 8) & 0xFF, gray, bands),
                apply_channel(formula, color & 0xFF, gray, bands),
                255,
            ))
    return pixels


# ---------------------------------------------------------------------------
# Sheet drawing
# ---------------------------------------------------------------------------


def _new_checkerboard(width: int, height: int):
    sheet = []
    for y in range(height):
        for x in range(width):
            shade = 38 if ((x // 8) + (y // 8)) % 2 == 0 else 46
            sheet.append((shade, shade, shade, 255))
    return sheet


def _draw_scaled(sheet, sheet_width: int, x0: int, y0: int,
                 source, width: int, height: int, max_w: int, max_h: int):
    factor = max(1, min(max_w // width, max_h // height))
    scaled = base._scale_nearest(source, width, height, factor)
    scaled_w, scaled_h = width * factor, height * factor
    for y in range(scaled_h):
        for x in range(scaled_w):
            p = scaled[y * scaled_w + x]
            if p[3] == 0:
                continue
            alpha = p[3] / 255.0
            index = (y0 + y) * sheet_width + (x0 + x)
            dst = sheet[index]
            sheet[index] = (
                int(p[0] * alpha + dst[0] * (1.0 - alpha)),
                int(p[1] * alpha + dst[1] * (1.0 - alpha)),
                int(p[2] * alpha + dst[2] * (1.0 - alpha)),
                255,
            )
    return scaled_w, scaled_h


def build_comparison_sheet(color_name: str, color: int, sources):
    """Rows are formulas, columns are masks plus a transfer ramp."""
    cell = 150
    image_area = 128
    header = 20
    columns = ("SWORD", "CHESTPLATE", "HUMANOID", "RAMP")
    rows = ("MASK",) + tuple(name.upper() for name, _, _ in FORMULAS)
    sheet_width = cell * len(columns)
    sheet_height = header + cell * len(rows)
    sheet = _new_checkerboard(sheet_width, sheet_height)

    for column, label in enumerate(columns):
        base._draw_text(sheet, sheet_width, column * cell + 6, 6,
                        label, (235, 235, 235, 255))

    for row_index, row_label in enumerate(rows):
        y0 = header + row_index * cell
        if row_index == 0:
            formula, bands = None, 4
        else:
            suffix, formula, bands = FORMULAS[row_index - 1]
            row_label = suffix.upper()
            if suffix == "linear_light":
                row_label = "LINEAR"

        for column, mask_name in enumerate(MASKS):
            width, height, source = sources[mask_name]
            if row_index == 0:
                image = source
            else:
                image = tint_pixels(source, color, formula, bands)
            factor = max(1, min(image_area // width, image_area // height))
            scaled_w = width * factor
            x0 = column * cell + (cell - scaled_w) // 2
            _draw_scaled(sheet, sheet_width, x0, y0 + 6,
                         image, width, height, image_area, image_area)

        ramp = ramp_pixels(color, formula, image_area, 64, bands)
        _draw_scaled(sheet, sheet_width, 3 * cell, y0 + 6,
                     ramp, image_area, 64, image_area, image_area)

        label = f"{row_label} {color_name.upper()}"
        base._draw_text(sheet, sheet_width, 6, y0 + 138,
                        label[:20], (235, 235, 235, 255))

    return sheet_width, sheet_height, sheet


def build_transfer_curves(color: int):
    """A tall sheet showing every transfer curve for h=0..255.

    Each mask value is rendered as two horizontal pixels so jumps are easy to
    spot without interpolation.
    """
    label_width = 110
    ramp_width = 512
    row_height = 34
    header = 20
    sheet_width = label_width + ramp_width
    sheet_height = header + row_height * len(TRANSFER_ROWS)
    sheet = _new_checkerboard(sheet_width, sheet_height)
    base._draw_text(sheet, sheet_width, 6, 6,
                    "TRANSFER CURVES", (235, 235, 235, 255))

    for row_index, (label, formula, bands) in enumerate(TRANSFER_ROWS):
        y0 = header + row_index * row_height
        base._draw_text(sheet, sheet_width, 6, y0 + 10, label,
                        (235, 235, 235, 255))
        ramp = ramp_pixels(color, formula, ramp_width, row_height - 8, bands)
        for y in range(row_height - 8):
            for x in range(ramp_width):
                sheet[(y0 + 4 + y) * sheet_width + label_width + x] = \
                    ramp[y * ramp_width + x]

    return sheet_width, sheet_height, sheet


def build_gamma_sheet():
    """Compare sRGB and linear-sRGB blending on the two continuous formulas."""
    label_width = 130
    ramp_width = 512
    row_height = 40
    header = 20
    rows = (
        ("SMOOTH SRGB", "smooth", False),
        ("SMOOTH LINEAR", "smooth", True),
        ("LINEAR SRGB", "linear_light", False),
        ("LINEAR LINEAR", "linear_light", True),
    )
    sheet_width = label_width + ramp_width
    sheet_height = header + row_height * len(rows)
    sheet = _new_checkerboard(sheet_width, sheet_height)
    base._draw_text(sheet, sheet_width, 6, 6,
                    "GAMMA C / CYAN", (235, 235, 235, 255))

    for row_index, (label, formula, linear_space) in enumerate(rows):
        y0 = header + row_index * row_height
        base._draw_text(sheet, sheet_width, 6, y0 + 12, label,
                        (235, 235, 235, 255))
        for x in range(ramp_width):
            gray = round(x * 255 / (ramp_width - 1))
            if formula == "smooth":
                red = smooth_channel(0, gray, linear_space)
                green = smooth_channel(255, gray, linear_space)
            else:
                red = linear_light_channel(0, gray, linear_space)
                green = linear_light_channel(255, gray, linear_space)
            # Cyan base: red stays at the low ramp, green/blue follow the high
            # ramp, so the curve remains a visible cyan-to-white gradient.
            pixel = (red, green, green, 255)
            for y in range(row_height - 8):
                sheet[(y0 + 4 + y) * sheet_width + label_width + x] = pixel

    return sheet_width, sheet_height, sheet


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def load_sources():
    sources = {}
    for name in MASKS:
        path = os.path.join(GRAYSCALE_DIR, name)
        with open(path, "rb") as handle:
            sources[name] = base.decode_png(handle.read())
    return sources


def write_png(path: str, width: int, height: int, pixels):
    with open(path, "wb") as handle:
        handle.write(base.encode_png(width, height, pixels))


def main() -> int:
    sources = load_sources()
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    written = 0
    for mask_name in MASKS:
        width, height, source = sources[mask_name]
        stem = os.path.splitext(mask_name)[0]
        for color_name, color in COLORS.items():
            for suffix, formula, bands in FORMULAS:
                tinted = tint_pixels(source, color, formula, bands)
                filename = f"{stem}_{color_name}_{suffix}.png"
                write_png(os.path.join(OUTPUT_DIR, filename), width, height, tinted)
                written += 1

    for color_name, color in COLORS.items():
        width, height, sheet = build_comparison_sheet(color_name, color, sources)
        write_png(os.path.join(OUTPUT_DIR, f"comparison_sheet_{color_name}.png"),
                  width, height, sheet)
        written += 1

    width, height, curves = build_transfer_curves(COLORS["cyan"])
    write_png(os.path.join(OUTPUT_DIR, "transfer_curves.png"), width, height, curves)
    written += 1

    width, height, gamma = build_gamma_sheet()
    write_png(os.path.join(OUTPUT_DIR, "gamma_comparison.png"), width, height, gamma)
    written += 1

    print(f"Wrote {written} PNGs to {OUTPUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
