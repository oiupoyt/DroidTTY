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
TOP, BOTTOM = 28, H - 24  # status bar and nav bar room
MARGIN = 14  # side room, also keeps the glow off the edges
BIG_RATIO = 64 / 17  # clock size relative to text, as on the phone
TEXT_MIN, TEXT_MAX = 9, 22
REF = 100  # measuring size; VT323 widths scale linearly


def font(size):
    return ImageFont.truetype(FONT, size)


REF_FONT = font(REF)


def lead(size):
    return int(size * 1.08)


def full(l):
    return l["mark"] + l["text"] + ("_" if l["cursor"] else "")


def ref_width(l):
    pad = 4 if l["role"] == "INVERSE" else 0  # inverse bar sticks out 2px each side
    return REF_FONT.getlength(full(l)) + pad * REF / TEXT_MIN


def fit(frames):
    """One text and clock size for a whole look or preset, so frames don't jitter."""
    room_w, room_h = W - 2 * MARGIN, BOTTOM - TOP
    lines = [l for f in frames for l in f["lines"]]
    text = TEXT_MAX
    small = [ref_width(l) for l in lines if l["role"] != "BIG"]
    if small:
        text = min(text, room_w * REF / max(small))
    big_w = max((ref_width(l) for l in lines if l["role"] == "BIG"), default=0)
    if big_w:
        text = min(text, room_w * REF / (big_w * BIG_RATIO))
    # Tail frames scroll on purpose, so only the others must fit in height.
    for f in frames:
        if f["tail"]:
            continue
        n_big = sum(l["role"] == "BIG" for l in f["lines"])
        n_text = len(f["lines"]) - n_big
        units = 1.08 * (n_text + n_big * BIG_RATIO)
        if units:
            text = min(text, room_h / units)
    text = int(max(TEXT_MIN, text))
    big = int(min(text * BIG_RATIO, room_w * REF / big_w if big_w else 1e9))
    sizes = {"TEXT": text, "DIM": text, "INVERSE": text, "BIG": big}
    fonts = {r: font(v) for r, v in sizes.items()}
    # Left edge for START lines: the widest START block of any frame, centred.
    starts = [fonts[l["role"]].getlength(full(l)) for l in lines if l["align"] == "START"]
    block = max(starts, default=0)
    left = max(MARGIN, (W - block) / 2)
    return sizes, fonts, left


def render(frame, layout):
    sizes, fonts, left = layout
    img = Image.new("RGB", (W, H), BG)
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    draws = [ImageDraw.Draw(img), ImageDraw.Draw(glow)]
    lines = frame["lines"]
    total = sum(lead(sizes[l["role"]]) for l in lines)
    if frame["centered"]:
        y = TOP + max(0, (BOTTOM - TOP - total) // 2)
    elif frame["tail"]:
        y = BOTTOM - total  # boot logs keep their newest line at the bottom
    else:
        y = TOP
    for l in lines:
        h = lead(sizes[l["role"]])
        if y < TOP or y + h > BOTTOM:  # only whole lines, never across the margins
            y += h
            continue
        f = fonts[l["role"]]
        text = l["text"] + ("_" if l["cursor"] else "")
        mark = l["mark"]
        tw = draws[0].textlength(mark + text, font=f)
        x = (W - tw) / 2 if l["align"] == "CENTER" else left
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
    layout = fit(frames)
    still_frame = frames[-1] if kind == "lock" else frames[0]
    # The still is sized by its own frame, so a short final screen isn't drawn at the size
    # the longest boot frame needed; GIFs keep one layout for all frames so nothing jitters.
    still = render(still_frame, fit([still_frame]))
    still.save(os.path.join(OUT, kind, f"{slug}.png"), optimize=True)
    if len(frames) > 1:
        images = [render(f, layout).convert("P", palette=Image.ADAPTIVE, colors=32) for f in frames]
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
