#!/usr/bin/env python3
"""Extract vanilla diamond equipment textures and build grayscale test assets.

This tool is intentionally dependency-free: it uses only the Python standard
library (``zipfile``, ``zlib`` and ``struct``).  It reads the mapped Minecraft
jar, decodes the palette PNGs stored there, converts RGB to a strict grayscale
mask using BT.601 luminance, and writes new RGBA8 PNGs for the test resource
set.  It also generates a few outputs of the runtime tint formula and a contact
sheet so the assets can be inspected without launching the game.

Usage:
    python3 tools/grayscale_assets.py
    python3 tools/grayscale_assets.py --jar /path/to/minecraft.jar
    python3 tools/grayscale_assets.py --check      # verify without writing
"""

from __future__ import annotations

import argparse
import binascii
import math
import os
import struct
import sys
import zipfile
import zlib

DEFAULT_JAR = (
    "/home/mikaple/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/"
    "minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar"
)
RESOURCE_ROOT = os.path.join(
    "src", "main", "resources", "assets", "crythst", "textures", "test"
)

# Source texture inside the jar -> output name in textures/test/grayscale/.
GRAYSCALE_ASSETS = {
    "assets/minecraft/textures/item/diamond_sword.png": "sword.png",
    "assets/minecraft/textures/item/diamond_spear.png": "spear.png",
    "assets/minecraft/textures/item/diamond_pickaxe.png": "pickaxe.png",
    "assets/minecraft/textures/item/diamond_axe.png": "axe.png",
    "assets/minecraft/textures/item/diamond_shovel.png": "shovel.png",
    "assets/minecraft/textures/item/diamond_hoe.png": "hoe.png",
    "assets/minecraft/textures/item/diamond_helmet.png": "helmet.png",
    "assets/minecraft/textures/item/diamond_chestplate.png": "chestplate.png",
    "assets/minecraft/textures/item/diamond_leggings.png": "leggings.png",
    "assets/minecraft/textures/item/diamond_boots.png": "boots.png",
    "assets/minecraft/textures/entity/equipment/humanoid/diamond.png": "humanoid.png",
    "assets/minecraft/textures/entity/equipment/humanoid_leggings/diamond.png": "humanoid_leggings.png",
    # Optional extras, still useful when checking armour rendering paths.
    "assets/minecraft/textures/item/diamond_horse_armor.png": "horse_armor.png",
    "assets/minecraft/textures/item/diamond_nautilus_armor.png": "nautilus_armor.png",
    "assets/minecraft/textures/entity/equipment/horse_body/diamond.png": "horse_body.png",
    "assets/minecraft/textures/entity/equipment/nautilus_body/diamond.png": "nautilus_body.png",
}

# Test colours: (name, 0xAARRGGBB).  The alpha byte is ignored by the formula.
COLORS = {
    "cyan": 0xFF00FFFF,
    "purple": 0xFF8000FF,
    "gold": 0xFFFFD700,
}
OUTLINE_PRIMARY = "cyan"
OUTLINE_SECONDARY = "magenta"
OUTLINE_SECONDARY_ARGB = 0xFFFF00FF

# Grayscale source, colour name, output name.
COLOR_OUTPUTS = [
    ("sword.png", "cyan", "sword_cyan.png"),
    ("sword.png", "purple", "sword_purple.png"),
    ("sword.png", "gold", "sword_gold.png"),
    ("chestplate.png", "cyan", "chestplate_cyan.png"),
    ("humanoid.png", "cyan", "humanoid_cyan.png"),
]

OUTLINE_OUTPUTS = [
    ("sword.png", "sword_cyan_outline_magenta.png"),
    ("humanoid.png", "humanoid_cyan_outline_magenta.png"),
]

CONTACT_SHEET = "grayscale_contact_sheet.png"

# ---------------------------------------------------------------------------
# PNG decoding (no external libraries)
# ---------------------------------------------------------------------------


class PngError(RuntimeError):
    pass


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _unfilter(raw: bytes, width: int, height: int, bits_per_pixel: int) -> bytes:
    """Reverse PNG scanline filtering.  Returns packed scanlines without filters."""
    bpp = max(1, (bits_per_pixel + 7) // 8)
    row_bytes = (width * bits_per_pixel + 7) // 8
    stride = row_bytes + 1
    expected = stride * height
    if len(raw) < expected:
        raise PngError(f"truncated IDAT: {len(raw)} < {expected}")

    out = bytearray(row_bytes * height)
    prev = bytearray(row_bytes)
    pos = 0
    for y in range(height):
        filter_type = raw[pos]
        pos += 1
        row = bytearray(raw[pos:pos + row_bytes])
        pos += row_bytes
        if filter_type == 0:
            pass
        elif filter_type == 1:  # Sub
            for i in range(bpp, row_bytes):
                row[i] = (row[i] + row[i - bpp]) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(row_bytes):
                row[i] = (row[i] + prev[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(row_bytes):
                left = row[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(row_bytes):
                left = row[i - bpp] if i >= bpp else 0
                up_left = prev[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + _paeth(left, prev[i], up_left)) & 0xFF
        else:
            raise PngError(f"unknown PNG filter type {filter_type}")
        out[y * row_bytes:(y + 1) * row_bytes] = row
        prev = row
    return bytes(out)


def _samples(row: bytes, count: int, bit_depth: int) -> list[int]:
    """Read ``count`` unsigned samples of ``bit_depth`` bits from one packed row."""
    if bit_depth == 8:
        if len(row) < count:
            raise PngError("short 8-bit row")
        return list(row[:count])
    if bit_depth == 16:
        if len(row) < count * 2:
            raise PngError("short 16-bit row")
        return [row[i * 2] << 8 | row[i * 2 + 1] for i in range(count)]
    if bit_depth not in (1, 2, 4):
        raise PngError(f"unsupported bit depth {bit_depth}")
    result = []
    mask = (1 << bit_depth) - 1
    per_byte = 8 // bit_depth
    for i in range(count):
        byte = row[i // per_byte]
        shift = 8 - bit_depth * (i % per_byte + 1)
        result.append((byte >> shift) & mask)
    return result


def decode_png(data: bytes) -> tuple[int, int, list[tuple[int, int, int, int]]]:
    """Decode a non-interlaced PNG. Returns (width, height, RGBA pixels)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise PngError("not a PNG")
    pos = 8
    width = height = bit_depth = color_type = interlace = None
    palette: list[tuple[int, int, int]] = []
    trns: bytes | None = None
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        chunk_type = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(
                ">IIBBBBB", chunk
            )
        elif chunk_type == b"PLTE":
            palette = [
                (chunk[i], chunk[i + 1], chunk[i + 2])
                for i in range(0, len(chunk), 3)
            ]
        elif chunk_type == b"tRNS":
            trns = chunk
        elif chunk_type == b"IDAT":
            idat += chunk
        elif chunk_type == b"IEND":
            break
    if width is None:
        raise PngError("missing IHDR")
    if interlace != 0:
        raise PngError("interlaced PNGs are not supported")

    raw = zlib.decompress(bytes(idat))
    bits_per_pixel = {
        0: bit_depth,
        2: 3 * bit_depth,
        3: bit_depth,
        4: 2 * bit_depth,
        6: 4 * bit_depth,
    }[color_type]
    planes = _unfilter(raw, width, height, bits_per_pixel)
    row_bytes = (width * bits_per_pixel + 7) // 8
    max_sample = (1 << bit_depth) - 1

    def to_byte(value: int) -> int:
        if bit_depth == 8:
            return value
        if bit_depth == 16:
            return (value * 255 + 32767) // 65535
        return (value * 255 + max_sample // 2) // max_sample

    pixels: list[tuple[int, int, int, int]] = []
    for y in range(height):
        row = planes[y * row_bytes:(y + 1) * row_bytes]
        if color_type == 0:
            for g in _samples(row, width, bit_depth):
                v = to_byte(g)
                pixels.append((v, v, v, 255))
        elif color_type == 2:
            values = _samples(row, width * 3, bit_depth)
            for i in range(width):
                pixels.append((
                    to_byte(values[i * 3]),
                    to_byte(values[i * 3 + 1]),
                    to_byte(values[i * 3 + 2]),
                    255,
                ))
        elif color_type == 3:
            for index in _samples(row, width, bit_depth):
                if index >= len(palette):
                    raise PngError(f"palette index {index} out of range")
                r, g, b = palette[index]
                a = trns[index] if trns is not None and index < len(trns) else 255
                pixels.append((r, g, b, a))
        elif color_type == 4:
            values = _samples(row, width * 2, bit_depth)
            for i in range(width):
                v = to_byte(values[i * 2])
                pixels.append((v, v, v, to_byte(values[i * 2 + 1])))
        elif color_type == 6:
            values = _samples(row, width * 4, bit_depth)
            for i in range(width):
                pixels.append((
                    to_byte(values[i * 4]),
                    to_byte(values[i * 4 + 1]),
                    to_byte(values[i * 4 + 2]),
                    to_byte(values[i * 4 + 3]),
                ))
        else:
            raise PngError(f"unsupported colour type {color_type}")
    return width, height, pixels


# ---------------------------------------------------------------------------
# PNG encoding (RGBA8, no interlacing, filter 0)
# ---------------------------------------------------------------------------


def _chunk(chunk_type: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + chunk_type
        + payload
        + struct.pack(">I", binascii.crc32(chunk_type + payload) & 0xFFFFFFFF)
    )


def encode_png(width: int, height: int, pixels: list[tuple[int, int, int, int]]) -> bytes:
    if len(pixels) != width * height:
        raise ValueError("pixel count does not match dimensions")
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type None
        for x in range(width):
            raw.extend(pixels[y * width + x])
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + _chunk(b"IEND", b"")
    )


# ---------------------------------------------------------------------------
# Conversion and tinting
# ---------------------------------------------------------------------------


def bt601(r: int, g: int, b: int) -> int:
    """BT.601 luma, rounded half-up, using exact integer arithmetic.

    ``0.299*R + 0.587*G + 0.114*B`` with coefficients scaled by 1000.  The
    ``+500`` implements round-half-up without floating point error.
    """
    return (299 * r + 587 * g + 114 * b + 500) // 1000


def to_grayscale(pixels):
    """Strict grayscale RGBA; keeps straight alpha and never premultiplies."""
    return [(h, h, h, a) for (r, g, b, a) in pixels for h in (bt601(r, g, b),)]


def tint_channel(color_channel: int, gray: int) -> int:
    """Port of GrayscaleColorizer.tintChannel, including Math.round semantics."""
    ramp = (2.0 * gray) / 255
    weight = 1.0 - abs(ramp - 1.0)
    lift = math.floor(ramp) * (gray - 127.5)
    value = weight * color_channel + lift
    return max(0, min(255, int(math.floor(value + 0.5))))


def tint_argb(argb: int, color: int) -> int:
    gray = max((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)
    alpha = (argb >> 24) & 0xFF
    r = tint_channel((color >> 16) & 0xFF, gray)
    g = tint_channel((color >> 8) & 0xFF, gray)
    b = tint_channel(color & 0xFF, gray)
    return (alpha << 24) | (r << 16) | (g << 8) | b


def tint_pixels(pixels, color: int):
    return [
        ((tinted >> 16) & 0xFF,
         (tinted >> 8) & 0xFF,
         tinted & 0xFF,
         (tinted >> 24) & 0xFF)
        for tinted in (
            tint_argb((a << 24) | (r << 16) | (g << 8) | b, color)
            for (r, g, b, a) in pixels
        )
    ]


def outline_mask(pixels, width: int, height: int) -> list[bool]:
    """Four-neighbour outline detection with alpha threshold 0 (OutlineMask)."""
    mask = [False] * len(pixels)
    for y in range(height):
        for x in range(width):
            i = y * width + x
            if pixels[i][3] == 0:
                continue
            for dx, dy in ((0, -1), (0, 1), (-1, 0), (1, 0)):
                nx, ny = x + dx, y + dy
                if nx < 0 or ny < 0 or nx >= width or ny >= height:
                    mask[i] = True
                    break
                if pixels[ny * width + nx][3] == 0:
                    mask[i] = True
                    break
    return mask


def tint_with_outline(pixels, width: int, height: int, primary: int, outline: int):
    mask = outline_mask(pixels, width, height)
    result = []
    for i, (r, g, b, a) in enumerate(pixels):
        argb = (a << 24) | (r << 16) | (g << 8) | b
        if a == 0:
            tinted = argb
        else:
            tinted = tint_argb(argb, outline if mask[i] else primary)
        result.append((
            (tinted >> 16) & 0xFF,
            (tinted >> 8) & 0xFF,
            tinted & 0xFF,
            (tinted >> 24) & 0xFF,
        ))
    return result


# ---------------------------------------------------------------------------
# Contact sheet (tiny 5x7 bitmap font, no dependencies)
# ---------------------------------------------------------------------------

_FONT = {
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "F": ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    "G": ("01110", "10001", "10000", "10111", "10001", "10001", "01110"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("01110", "00100", "00100", "00100", "00100", "00100", "01110"),
    "J": ("00111", "00010", "00010", "00010", "00010", "10010", "01100"),
    "K": ("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "Q": ("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "U": ("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "W": ("10001", "10001", "10001", "10101", "10101", "10101", "01010"),
    "X": ("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
    "0": ("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    "1": ("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    "2": ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    "3": ("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    "4": ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    "5": ("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    "6": ("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    "7": ("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    "8": ("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    "9": ("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    "_": ("00000", "00000", "00000", "00000", "00000", "00000", "11111"),
}


def _draw_text(pixels, sheet_width: int, x0: int, y0: int, text: str, color):
    x = x0
    for char in text.upper():
        glyph = _FONT.get(char)
        if glyph is None:  # space / unknown
            x += 6
            continue
        for gy, row in enumerate(glyph):
            for gx, bit in enumerate(row):
                if bit == "1":
                    px, py = x + gx, y0 + gy
                    if 0 <= px < sheet_width and 0 <= py < len(pixels) // sheet_width:
                        pixels[py * sheet_width + px] = color
        x += 6


def _scale_nearest(pixels, width: int, height: int, factor: int):
    return [
        pixels[(y // factor) * width + (x // factor)]
        for y in range(height * factor)
        for x in range(width * factor)
    ]


def build_contact_sheet(images: list[tuple[str, int, int, list[tuple[int, int, int, int]]]]):
    cell = 150
    image_area = 128
    columns = 4
    rows = (len(images) + columns - 1) // columns
    sheet_width = cell * columns
    sheet_height = cell * rows

    # Dark checkerboard so transparent texture pixels are visible.
    sheet = []
    for y in range(sheet_height):
        for x in range(sheet_width):
            shade = 38 if ((x // 8) + (y // 8)) % 2 == 0 else 46
            sheet.append((shade, shade, shade, 255))

    for index, (name, width, height, pixels) in enumerate(images):
        factor = max(1, min(image_area // width, image_area // height))
        scaled = _scale_nearest(pixels, width, height, factor)
        sw, sh = width * factor, height * factor
        cx = (index % columns) * cell + (cell - sw) // 2
        cy = (index // columns) * cell + 6
        for y in range(sh):
            for x in range(sw):
                p = scaled[y * sw + x]
                if p[3] == 0:
                    continue  # let the checkerboard show through
                alpha = p[3] / 255.0
                dst = sheet[(cy + y) * sheet_width + (cx + x)]
                sheet[(cy + y) * sheet_width + (cx + x)] = (
                    int(p[0] * alpha + dst[0] * (1 - alpha)),
                    int(p[1] * alpha + dst[1] * (1 - alpha)),
                    int(p[2] * alpha + dst[2] * (1 - alpha)),
                    255,
                )
        label = name[:-4] if name.endswith(".png") else name
        _draw_text(sheet, sheet_width, (index % columns) * cell + 6,
                   (index // columns) * cell + 6 + image_area + 4,
                   label[:22], (235, 235, 235, 255))
    return sheet_width, sheet_height, sheet


# ---------------------------------------------------------------------------
# Main pipeline
# ---------------------------------------------------------------------------


def load_sources(jar_path: str):
    sources = {}
    with zipfile.ZipFile(jar_path) as jar:
        for source, output in GRAYSCALE_ASSETS.items():
            width, height, pixels = decode_png(jar.read(source))
            sources[output] = (width, height, pixels)
    return sources


def stats(width: int, height: int, pixels):
    alpha_sum = sum(p[3] for p in pixels)
    non_transparent = sum(1 for p in pixels if p[3] > 0)
    gray_sum = sum(p[0] for p in pixels if p[3] > 0)
    return width, height, alpha_sum, non_transparent, gray_sum


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--check", action="store_true",
                        help="decode and report stats without writing files")
    args = parser.parse_args()

    if not os.path.exists(args.jar):
        print(f"minecraft jar not found: {args.jar}", file=sys.stderr)
        return 1

    sources = load_sources(args.jar)
    gray_dir = os.path.join(RESOURCE_ROOT, "grayscale")
    color_dir = os.path.join(RESOURCE_ROOT, "color")

    if not args.check:
        os.makedirs(gray_dir, exist_ok=True)
        os.makedirs(color_dir, exist_ok=True)

    print("Grayscale asset statistics (width, height, alphaSum, covered, graySum):")
    for name in sorted(sources):
        width, height, pixels = sources[name]
        gray = to_grayscale(pixels)
        w, h, alpha_sum, covered, gray_sum = stats(width, height, gray)
        print(f"  {name:32s} {w:3d} x {h:<3d} alphaSum={alpha_sum:8d} "
              f"covered={covered:6d} graySum={gray_sum:9d}")
        if not args.check:
            with open(os.path.join(gray_dir, name), "wb") as handle:
                handle.write(encode_png(width, height, gray))

    if not args.check:
        for source_name, color_name, output_name in COLOR_OUTPUTS:
            width, height, pixels = sources[source_name]
            tinted = tint_pixels(to_grayscale(pixels), COLORS[color_name])
            with open(os.path.join(color_dir, output_name), "wb") as handle:
                handle.write(encode_png(width, height, tinted))

        for source_name, output_name in OUTLINE_OUTPUTS:
            width, height, pixels = sources[source_name]
            tinted = tint_with_outline(
                to_grayscale(pixels), width, height,
                COLORS[OUTLINE_PRIMARY], OUTLINE_SECONDARY_ARGB,
            )
            with open(os.path.join(color_dir, output_name), "wb") as handle:
                handle.write(encode_png(width, height, tinted))

        sheet_images = [
            (name, sources[name][0], sources[name][1], to_grayscale(sources[name][2]))
            for name in sorted(sources)
        ]
        sheet_w, sheet_h, sheet = build_contact_sheet(sheet_images)
        with open(os.path.join(RESOURCE_ROOT, CONTACT_SHEET), "wb") as handle:
            handle.write(encode_png(sheet_w, sheet_h, sheet))
        print(f"\nWrote {len(sources)} grayscale masks, "
              f"{len(COLOR_OUTPUTS)} tinted images, "
              f"{len(OUTLINE_OUTPUTS)} outline images and {CONTACT_SHEET} "
              f"({sheet_w}x{sheet_h})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
