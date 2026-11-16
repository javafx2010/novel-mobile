from PIL import Image, ImageDraw, ImageFilter
import os

RAW = r"F:\Create\novel-mobile\icon_work\badges_v4"
RES = r"F:\Create\novel-mobile\app\src\main\res\drawable-nodpi"
MIP = r"F:\Create\novel-mobile\app\src\main\res"
SIZE = 256

BADGES = [
    "first", "streak3", "streak7", "streak30", "hours10", "hours50",
    "hours100", "book1", "book5", "book10", "year", "goal",
]


def crop_circle(src, dest):
    """Force any source image into a circular badge with transparent corners."""
    im = Image.open(src).convert("RGBA")
    w, h = im.size
    side = min(w, h)
    # center-crop to square
    left = (w - side) // 2
    top = (h - side) // 2
    im = im.crop((left, top, left + side, top + side))
    # inscribed circle with margin so square frames' corners are fully removed
    inset = int(side * 0.055)
    d = side - inset * 2
    mask = Image.new("L", (side, side), 0)
    ImageDraw.Draw(mask).ellipse((inset, inset, inset + d, inset + d), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(1.0))
    im.putalpha(mask)
    # crop to the circle bbox then scale up so the medallion fills the tile
    im = im.crop((inset, inset, inset + d, inset + d))
    im = im.resize((SIZE, SIZE), Image.LANCZOS)
    im.save(dest, "WEBP", quality=92, method=6)
    return os.path.getsize(dest)


def cutout_headphones(src):
    """Remove the grey studio background (and the desk reflection), keep the headphones."""
    im = Image.open(src).convert("RGBA")
    w, h = im.size
    # source has a desk/reflection in the bottom ~20%; crop it before cutout
    im = im.crop((0, 0, w, int(h * 0.84)))
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            mx, mn = max(r, g, b), min(r, g, b)
            # grey background: low saturation + not too dark
            if mx - mn < 28 and mx > 70:
                px[x, y] = (r, g, b, 0)
            else:
                px[x, y] = (r, g, b, 255)
    # soften cutout edge
    a = im.getchannel("A").filter(ImageFilter.GaussianBlur(1.2))
    im.putalpha(a)
    return im


def export_icon(im):
    # adaptive foreground: 108dp icon, art occupies the safe ~66% center area
    densities = [(48, "mdpi"), (72, "hdpi"), (96, "xhdpi"), (144, "xxhdpi"), (192, "xxxhdpi")]
    for size, folder in densities:
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        art = im.resize((int(size * 0.68), int(size * 0.68)), Image.LANCZOS)
        canvas.paste(art, ((size - art.width) // 2, (size - art.height) // 2), art)
        out = os.path.join(MIP, "mipmap-" + folder, "ic_launcher_foreground.png")
        canvas.save(out, "PNG")
        print("icon", folder, out, flush=True)


def main():
    os.makedirs(RES, exist_ok=True)
    for bid in BADGES:
        src = os.path.join(RAW, bid + ".png")
        if not os.path.exists(src):
            print("MISSING", bid, flush=True)
            continue
        size = crop_circle(src, os.path.join(RES, "badge_" + bid + ".webp"))
        print("badge", bid, size, flush=True)
    cutout_headphones(os.path.join(RAW, "app_icon.png")).save(
        os.path.join(RAW, "app_icon_cut.png"), "PNG"
    )
    export_icon(Image.open(os.path.join(RAW, "app_icon_cut.png")).convert("RGBA"))
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
