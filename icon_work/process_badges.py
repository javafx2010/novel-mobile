"""Turn the ComfyUI badge renders into Android assets.

White backgrounds are removed with a corner flood fill (so white areas *inside* the artwork
survive). Full-bleed renders (nothing to remove) are masked into a circle instead, so every badge
ends up as a round medallion with transparency around it.
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RAW = r"F:\Create\novel-mobile\icon_work\badges_v3"
RES = r"F:\Create\novel-mobile\app\src\main\res\drawable-nodpi"
SIZE = 256
MAGIC = (255, 0, 255)


def process(path, dest):
    src = Image.open(path).convert("RGB")
    w, h = src.size
    keyed = src.copy()
    seeds = [
        (0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1),
        (w // 2, 0), (w // 2, h - 1), (0, h // 2), (w - 1, h // 2),
    ]
    for seed in seeds:
        try:
            ImageDraw.floodfill(keyed, seed, MAGIC, thresh=88)
        except Exception:
            pass

    rgba = src.convert("RGBA")
    arr = np.array(rgba)
    key = np.array(keyed)
    background = (
        (key[:, :, 0] == MAGIC[0]) & (key[:, :, 1] == MAGIC[1]) & (key[:, :, 2] == MAGIC[2])
    )
    arr[:, :, 3] = np.where(background, 0, arr[:, :, 3])

    coverage = float((arr[:, :, 3] > 10).mean())
    rgba = Image.fromarray(arr, "RGBA")

    if coverage > 0.78:
        # Nothing keyed out: the artwork fills the frame, so crop it into a circle.
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, w - 1, h - 1), fill=255)
        rgba.putalpha(Image.composite(rgba.getchannel("A"), Image.new("L", (w, h), 0), mask))
        rgba = rgba.crop((int(w * 0.03), int(h * 0.03), int(w * 0.97), int(h * 0.97)))
    else:
        alpha = rgba.getchannel("A").filter(ImageFilter.GaussianBlur(1.2))
        rgba.putalpha(alpha)
        bbox = rgba.getbbox()
        if bbox:
            rgba = rgba.crop(bbox)

    side = int(max(rgba.width, rgba.height) * 1.04)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(rgba, ((side - rgba.width) // 2, (side - rgba.height) // 2), rgba)
    canvas = canvas.resize((SIZE, SIZE), Image.LANCZOS)
    canvas.save(dest, "WEBP", quality=92, method=6)
    return coverage, os.path.getsize(dest)


def main():
    os.makedirs(RES, exist_ok=True)
    for name in sorted(os.listdir(RAW)):
        if not name.endswith(".png"):
            continue
        badge_id = os.path.splitext(name)[0]
        dest = os.path.join(RES, f"badge_{badge_id}.webp")
        coverage, size = process(os.path.join(RAW, name), dest)
        print(f"{badge_id}: coverage={coverage:.2f} size={size}", flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
