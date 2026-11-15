"""Generate default cover candidates with the local ComfyUI (sd_xl_base_1.0)."""

import json
import os
import time
import urllib.request

SERVER = "http://127.0.0.1:8188"
OUT = r"F:\Create\novel-mobile\icon_work\cover_raw"
W, H = 832, 1152

PROMPT = (
    "elegant minimalist book cover illustration, portrait orientation, deep navy blue background "
    "fading into warm amber orange at the bottom, a delicate gold line drawing of an open book "
    "merging into soft sound waves, centered vertically, generous empty space, flat vector style, "
    "subtle grain, premium audiobook cover, no text, no letters, no numbers, no watermark, "
    "no border, no mockup"
)
NEGATIVE = ("text, letters, numbers, watermark, photo, 3d render, book mockup, hand, table, "
            "frame, border, busy, cluttered, ugly, blurry, low contrast")

SEEDS = [7101, 7102, 7103, 7104]


def post(path, payload):
    req = urllib.request.Request(
        SERVER + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    return json.loads(urllib.request.urlopen(req, timeout=30).read())


def get(path):
    return json.loads(urllib.request.urlopen(SERVER + path, timeout=30).read())


def workflow(seed):
    return {
        "1": {"class_type": "CheckpointLoaderSimple",
              "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
        "2": {"class_type": "CLIPTextEncode", "inputs": {"text": PROMPT, "clip": ["1", 1]}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"text": NEGATIVE, "clip": ["1", 1]}},
        "4": {"class_type": "EmptyLatentImage",
              "inputs": {"width": W, "height": H, "batch_size": 1}},
        "5": {"class_type": "KSampler",
              "inputs": {"seed": seed, "steps": 30, "cfg": 7.5,
                         "sampler_name": "dpmpp_2m", "scheduler": "karras", "denoise": 1.0,
                         "model": ["1", 0], "positive": ["2", 0], "negative": ["3", 0],
                         "latent_image": ["4", 0]}},
        "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage", "inputs": {"filename_prefix": "cover", "images": ["6", 0]}},
    }


os.makedirs(OUT, exist_ok=True)
for seed in SEEDS:
    pid = post("/prompt", {"prompt": workflow(seed), "client_id": "cover"})["prompt_id"]
    entry = None
    for _ in range(150):
        hist = get(f"/history/{pid}")
        if pid in hist:
            entry = hist[pid]
            break
        time.sleep(2)
    image = entry["outputs"]["7"]["images"][0]
    url = f"{SERVER}/view?filename={image['filename']}&subfolder={image.get('subfolder', '')}&type=output"
    dest = os.path.join(OUT, f"cover_{seed}.png")
    with urllib.request.urlopen(url, timeout=60) as resp, open(dest, "wb") as f:
        f.write(resp.read())
    print("saved", dest, flush=True)
print("DONE", flush=True)
