"""Regenerate the light-background badges as full-bleed circular medallions (uniform set)."""

import json
import os
import time
import urllib.request

SERVER = "http://127.0.0.1:8188"
OUT = r"F:\Create\novel-mobile\icon_work\badges_v4"

TEMPLATE = (
    "flat vector circular medallion badge that fills the entire square frame edge to edge, "
    "a thick gold rim touching all four sides, deep navy blue background inside the medallion, "
    "warm gold line art of {subject} centered inside, bold minimal symmetrical design, "
    "no white background, no margins, no shadow, no text, no letters, no numbers"
)

JOBS = [
    ("book1", "a closed hardcover book with a gold check mark on its cover", 6101),
    ("book5", "a neat stack of five closed books", 6102),
    ("book10", "a tall stack of books topped with a five point star", 6103),
    ("year", "a trophy cup between two laurel branches", 6104),
    ("streak30", "a crescent moon with a small flame", 6105),
    ("hours10", "a pair of over-ear headphones around a clock face", 6106),
    ("goal", "a target ring with a check mark in the center", 6107),
]

NEGATIVE = ("white background, white margin, text, letters, numbers, watermark, photo, 3d render, "
            "scene, mockup, cropped, cut off, multiple badges, collage, ugly, blurry")


def post(path, payload):
    req = urllib.request.Request(
        SERVER + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    return json.loads(urllib.request.urlopen(req, timeout=30).read())


def get(path):
    return json.loads(urllib.request.urlopen(SERVER + path, timeout=30).read())


def workflow(prompt, seed):
    return {
        "1": {"class_type": "CheckpointLoaderSimple",
              "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
        "2": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["1", 1]}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"text": NEGATIVE, "clip": ["1", 1]}},
        "4": {"class_type": "EmptyLatentImage",
              "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
        "5": {"class_type": "KSampler",
              "inputs": {"seed": seed, "steps": 26, "cfg": 7.5,
                         "sampler_name": "dpmpp_2m", "scheduler": "karras", "denoise": 1.0,
                         "model": ["1", 0], "positive": ["2", 0], "negative": ["3", 0],
                         "latent_image": ["4", 0]}},
        "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage", "inputs": {"filename_prefix": "badge6", "images": ["6", 0]}},
    }


os.makedirs(OUT, exist_ok=True)
for badge_id, subject, seed in JOBS:
    prompt = TEMPLATE.format(subject=subject)
    pid = post("/prompt", {"prompt": workflow(prompt, seed), "client_id": "b6"})["prompt_id"]
    entry = None
    for _ in range(150):
        hist = get(f"/history/{pid}")
        if pid in hist:
            entry = hist[pid]
            break
        time.sleep(2)
    image = entry["outputs"]["7"]["images"][0]
    url = f"{SERVER}/view?filename={image['filename']}&subfolder={image.get('subfolder', '')}&type=output"
    dest = os.path.join(OUT, badge_id + ".png")
    with urllib.request.urlopen(url, timeout=60) as resp, open(dest, "wb") as f:
        f.write(resp.read())
    print("saved", badge_id, flush=True)
print("DONE", flush=True)
