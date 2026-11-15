from PIL import Image, ImageDraw
import os

SRC = "D:/Main/wild-english/icon_work/g2.png"
RES = "D:/Main/wild-english/app/src/main/res"
OUT = "D:/Main/wild-english/icon_work"

img = Image.open(SRC).convert("RGB")

def edge_padded(size, scale):
    S = int(round(size * scale))
    small = img.resize((S, S), Image.LANCZOS)
    canvas = Image.new("RGB", (size, size))
    off = (size - S) // 2
    canvas.paste(small, (off, off))
    sp = small.load(); cp = canvas.load()
    for y in range(size):
        sy = min(max(y - off, 0), S - 1)
        l = sp[0, sy]; r = sp[S - 1, sy]
        for x in range(off):
            cp[x, y] = l
            cp[size - 1 - x, y] = r
    top = off
    bottom = size - 1 - off
    for y in range(off):
        for x in range(size):
            cp[x, y] = cp[x, top]
            cp[x, size - 1 - y] = cp[x, bottom]
    return canvas

corner = edge_padded(64, 0.88).getpixel((4, 4))
print("corner", corner, "hex", "#%02X%02X%02X" % corner)

densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
for name, size in densities.items():
    d = os.path.join(RES, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    edge_padded(size, 0.88).save(os.path.join(d, "ic_launcher_foreground.png"))
    print("wrote", name, size)

size = 432
base = edge_padded(size, 0.88)

circle = Image.new("L", (size, size), 0)
ImageDraw.Draw(circle).ellipse((0, 0, size - 1, size - 1), fill=255)
c = Image.new("RGBA", (size, size), (0, 0, 0, 0))
c.paste(base, (0, 0), circle)
c.save(os.path.join(OUT, "preview_circle.png"))

squircle = Image.new("L", (size, size), 0)
ImageDraw.Draw(squircle).rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.28), fill=255)
s = Image.new("RGBA", (size, size), (0, 0, 0, 0))
s.paste(base, (0, 0), squircle)
s.save(os.path.join(OUT, "preview_squircle.png"))
print("ok")
