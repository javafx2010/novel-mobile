import json
import os
import time
import urllib.request

SERVER = "http://127.0.0.1:8188"
OUT_RAW = r"F:\Create\novel-mobile\icon_work\badges_v4"

# 统一拟物质感：圆形金属徽章、深蓝珐琅底 + 拉丝金浮雕、柔和棚拍光。
STYLE = (
    "a perfectly round circular medal badge, photorealistic 3D render, skeuomorphic, "
    "brushed gold metal rim with a thin highlight, deep navy blue enamel center, "
    "embossed gold relief of {subject} in the middle, soft studio lighting, subtle inner "
    "shadow, premium award medallion, centered, whole circle fully visible, plain dark "
    "background, no text, no letters, no numbers, no watermark, no square, no frame"
)

BADGES = [
    ("first", "an open book with pages fanned and a four point star above it", 5101),
    ("streak3", "three small flames side by side", 5102),
    ("streak7", "a single flame with a thin calendar ring of seven marks", 5103),
    ("streak30", "a crescent moon beside a small flame", 5104),
    ("hours10", "over-ear headphones wrapped around a small clock face", 5105),
    ("hours50", "a clock face framed by two laurel branches", 5106),
    ("hours100", "a crown resting on a clock face", 5107),
    ("book1", "a closed hardcover book with a check mark", 5108),
    ("book5", "a stack of five books", 5109),
    ("book10", "a stack of books topped with a five point star", 5110),
    ("year", "a trophy cup between two laurel branches", 5111),
    ("goal", "a target bullseye with a check mark", 5112),
]

# App 图标：拟物耳机，保留耳机设计。
APP_ICON = (
    "app icon, a pair of premium over-ear headphones, photorealistic 3D render, "
    "skeuomorphic, brushed gold and deep navy blue, soft studio lighting, glossy "
    "highlights, subtle shadows, centered on a plain dark navy background, no text, "
    "no letters, no watermark, minimal"
)


def post(path, payload):
    req = urllib.request.Request(
        SERVER + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def get(path):
    with urllib.request.urlopen(SERVER + path, timeout=30) as resp:
        return json.loads(resp.read())


def workflow(subject, seed, size=1024):
    negative = (
        "text, letters, numbers, watermark, square, rectangle, frame, mockup, flat, "
        "2d, cartoon, cropped, cut off, busy, cluttered, ugly, blurry"
    )
    return {
        "1": {"class_type": "CheckpointLoaderSimple",
              "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
        "2": {"class_type": "CLIPTextEncode",
              "inputs": {"text": subject, "clip": ["1", 1]}},
        "3": {"class_type": "CLIPTextEncode",
              "inputs": {"text": negative, "clip": ["1", 1]}},
        "4": {"class_type": "EmptyLatentImage",
              "inputs": {"width": size, "height": size, "batch_size": 1}},
        "5": {"class_type": "KSampler",
              "inputs": {"seed": seed, "steps": 28, "cfg": 7.5,
                         "sampler_name": "dpmpp_2m", "scheduler": "karras",
                         "denoise": 1.0, "model": ["1", 0],
                         "positive": ["2", 0], "negative": ["3", 0],
                         "latent_image": ["4", 0]}},
        "6": {"class_type": "VAEDecode",
              "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage",
              "inputs": {"filename_prefix": "skew", "images": ["6", 0]}},
    }


def render(prompt, seed, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 10000:
        print("skip", os.path.basename(dest), flush=True)
        return
    pid = post("/prompt", {"prompt": workflow(prompt, seed), "client_id": "skew"})["prompt_id"]
    entry = None
    for _ in range(180):
        hist = get(f"/history/{pid}")
        if pid in hist:
            entry = hist[pid]
            break
        time.sleep(2)
    img = entry["outputs"]["7"]["images"][0]
    url = (f"{SERVER}/view?filename={img['filename']}&"
           f"subfolder={img.get('subfolder', '')}&type=output")
    with urllib.request.urlopen(url, timeout=60) as resp, open(dest, "wb") as f:
        f.write(resp.read())
    print("saved", os.path.basename(dest), flush=True)


os.makedirs(OUT_RAW, exist_ok=True)
for bid, subject, seed in BADGES:
    render(STYLE.format(subject=subject), seed, os.path.join(OUT_RAW, bid + ".png"))
render(APP_ICON, 5200, os.path.join(OUT_RAW, "app_icon.png"))
print("DONE", flush=True)
