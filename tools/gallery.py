#!/usr/bin/env python3
"""Draws DroidTTY's always-on looks and lock presets for docs/GALLERY.md.

Reads app/build/gallery/frames.json (written by the GalleryDump unit test from
the app's own frame code) and renders each look and preset with the bundled
VT323 font in the green P1 colours: a PNG of one frame, plus a looping GIF for
anything that moves. The CRT shader isn't reproduced; a light scanline overlay
stands in for it.

    python3 tools/gallery.py [frames.json] [out-dir]
"""
import json
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "app/build/gallery/frames.json")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "docs/gallery")
FONT = os.path.join(ROOT, "app/src/main/res/font/vt323_regular.ttf")

# The green P1 preset (data/Theme.kt): background, text, dim, accent.
BG = (2, 14, 5)
FG = (22, 217, 108)
DIM = (18, 184, 92)
ACCENT = (26, 255, 128)

W, H = 360, 760  # phone-shaped, roughly 9:19
SIZE = {"TEXT": 17, "DIM": 17, "INVERSE": 17, "BIG": 64}
MARGIN = 16


def font(size):
    return ImageFont.truetype(FONT, size)


def line_height(role):
    return int(SIZE[role] * 1.08)


def render(frame):
    img = Image.new("RGB", (W, H), BG)
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    draws = [ImageDraw.Draw(img), ImageDraw.Draw(glow)]
    lines = frame["lines"]
    total = sum(line_height(l["role"]) for l in lines)
    top, bottom = 34, H - 28
    if frame["centered"]:
        y = top + max(0, (bottom - top - total) // 2)
    elif frame["tail"]:
        y = bottom - total  # boot logs keep their newest line at the bottom
    else:
        y = top
    for l in lines:
        h = line_height(l["role"])
        if y + h < top - 4 or y > bottom:
            y += h
            continue
        f = font(SIZE[l["role"]])
        text = l["text"] + ("_" if l["cursor"] else "")
        mark = l["mark"]
        tw = draws[0].textlength(mark + text, font=f)
        x = (W - tw) / 2 if l["align"] == "CENTER" else MARGIN
        color = DIM if l["role"] == "DIM" else FG
        for d in draws:
            if l["role"] == "INVERSE":
                d.rectangle([x - 2, y, x + tw + 2, y + h], fill=FG)
            if mark:
                d.text((x, y), mark, font=f, fill=ACCENT)
            d.text((x + d.textlength(mark, font=f), y), text, font=f, fill=BG if l["role"] == "INVERSE" else color)
        y += h
    # Phosphor glow and scanlines, standing in for the CRT shader.
    bloom = glow.filter(ImageFilter.GaussianBlur(5))
    img = ImageChops.add(img, ImageChops.multiply(bloom, Image.new("RGB", (W, H), (110, 110, 110))))
    scan = ImageDraw.Draw(img, "RGBA")
    for sy in range(0, H, 3):
        scan.line([(0, sy), (W, sy)], fill=(0, 0, 0, 46))
    return img


def write(kind, name, item):
    slug = name.lower()
    frames = item["frames"]
    os.makedirs(os.path.join(OUT, kind), exist_ok=True)
    still = render(frames[-1] if kind == "lock" else frames[0])
    still.save(os.path.join(OUT, kind, f"{slug}.png"), optimize=True)
    if len(frames) > 1:
        images = [render(f).convert("P", palette=Image.ADAPTIVE, colors=32) for f in frames]
        images[0].save(
            os.path.join(OUT, kind, f"{slug}.gif"),
            save_all=True, append_images=images[1:], duration=item["ms"], loop=0, optimize=True, disposal=1,
        )


def main():
    data = json.load(open(SRC))
    for name, item in data["looks"].items():
        write("aod", name, item)
    for name, item in data["presets"].items():
        write("lock", name, item)
    print(f"gallery: {len(data['looks'])} looks, {len(data['presets'])} presets -> {OUT}")


if __name__ == "__main__":
    main()
