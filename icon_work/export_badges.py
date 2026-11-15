"""Export the final badge set: full-bleed medallions, square, 256px (UI clips them to a circle).

Sources: badges_v3 for the ones that already fill the frame, badges_v4 for the regenerated ones.
"""

import os

from PIL import Image

V3 = r"F:\Create\novel-mobile\icon_work\badges_v3"
V4 = r"F:\Create\novel-mobile\icon_work\badges_v4"
RES = r"F:\Create\novel-mobile\app\src\main\res\drawable-nodpi"
SIZE = 256

SOURCES = {
    "first": V3,
    "streak3": V3,
    "streak7": V3,
    "hours50": V3,
    "hours100": V3,
    "book1": V4,
    "book5": V4,
    "book10": V4,
    "year": V4,
    "streak30": V4,
    "hours10": V4,
    "goal": V4,
}


def export(src_path, dest):
    img = Image.open(src_path).convert("RGB")
    side = min(img.width, img.height)
    left = (img.width - side) // 2
    top = (img.height - side) // 2
    img = img.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.LANCZOS)
    img.save(dest, "WEBP", quality=90, method=6)
    return os.path.getsize(dest)


def main():
    os.makedirs(RES, exist_ok=True)
    for badge_id, folder in SOURCES.items():
        src = os.path.join(folder, badge_id + ".png")
        dest = os.path.join(RES, f"badge_{badge_id}.webp")
        print(f"{badge_id}: {export(src, dest)} bytes", flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
