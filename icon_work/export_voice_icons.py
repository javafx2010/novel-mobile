"""Crop generated voice medallions to circular transparent WebP drawables."""
from PIL import Image, ImageDraw
import os

SRC = r"F:\Create\novel-mobile\icon_work\voice_icons"
OUT = r"F:\Create\novel-mobile\app\src\main\res\drawable-nodpi"
SIZE = 256
FILES = [
    "xiaoxiao", "xiaoyi", "yunxi", "yunjian", "yunyang", "yunxia",
    "emma", "ava", "andrew", "brian", "aria", "guy",
]

os.makedirs(OUT, exist_ok=True)
for name in FILES:
    src = os.path.join(SRC, name + ".png")
    im = Image.open(src).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)
    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, SIZE - 1, SIZE - 1), fill=255)
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    dest = os.path.join(OUT, "voice_" + name + ".webp")
    out.save(dest, "WEBP", quality=88)
    print("wrote", dest)
print("DONE")
