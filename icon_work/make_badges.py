import json
import os
import time
import urllib.request

SERVER = "http://127.0.0.1:8188"
OUT_RAW = r"F:\Create\novel-mobile\icon_work\badges_v3"

FRAME = (
    "flat vector badge illustration: {subject}. The badge is a simple round gold rim with a "
    "deep navy blue inside and warm gold line art of the subject in the center. Minimal, bold, "
    "symmetrical, isolated on a plain pure white background with generous white margin around "
    "the badge, no drop shadow, no text, no letters, no numbers, no watermark."
)

BADGES = [
    ("first", "an open book, pages fanned, with a small four-point sparkle above it", 3101),
    ("streak3", "a single small flame with three tiny spark dots around it", 3102),
    ("streak7", "a flame in front of a thin calendar ring with seven marks", 3103),
    ("streak30", "a golden crescent moon behind a flame", 3104),
    ("hours10", "a pair of over-ear headphones around a small clock face", 3105),
    ("hours50", "a clock face framed by two laurel branches", 3106),
    ("hours100", "a small crown resting on top of a clock face", 3107),
    ("book1", "a closed hardcover book with a golden check mark on the cover", 3108),
    ("book5", "a neat stack of five closed books seen from the side", 3109),
    ("book10", "a tall stack of ten closed books topped with a five-point star", 3110),
    ("year", "a trophy cup between two laurel branches", 3111),
    ("goal", "a target ring with a check mark in the center", 3112),
]


def post(path, payload):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        SERVER + path, data=data, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def get(path):
    with urllib.request.urlopen(SERVER + path, timeout=30) as resp:
        return json.loads(resp.read())


def workflow(subject, seed):
    return {
        "1": {"class_type": "CheckpointLoaderSimple",
              "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
        "2": {"class_type": "CLIPTextEncode",
              "inputs": {"text": FRAME.format(subject=subject), "clip": ["1", 1]}},
        "3": {"class_type": "CLIPTextEncode",
              "inputs": {"text": "text, letters, numbers, watermark, photo, 3d render, "
                                 "scene, mockup, frame around the image, cropped, "
                                 "cut off, dark background, busy, ugly, blurry",
                         "clip": ["1", 1]}},
        "4": {"class_type": "EmptyLatentImage",
              "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
        "5": {"class_type": "KSampler",
              "inputs": {"seed": seed, "steps": 26, "cfg": 7.5,
                         "sampler_name": "dpmpp_2m", "scheduler": "karras",
                         "denoise": 1.0, "model": ["1", 0],
                         "positive": ["2", 0], "negative": ["3", 0],
                         "latent_image": ["4", 0]}},
        "6": {"class_type": "VAEDecode",
              "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage",
              "inputs": {"filename_prefix": "badge3", "images": ["6", 0]}},
    }


def wait(pid, timeout=300):
    start = time.time()
    while time.time() - start < timeout:
        hist = get(f"/history/{pid}")
        if pid in hist:
            return hist[pid]
        time.sleep(2)
    raise RuntimeError("timeout " + pid)


def download(entry, dest):
    images = entry["outputs"]["7"]["images"]
    url = f"{SERVER}/view?filename={images[0]['filename']}&subfolder={images[0].get('subfolder', '')}&type=output"
    with urllib.request.urlopen(url, timeout=60) as resp:
        with open(dest, "wb") as f:
            f.write(resp.read())


def main():
    os.makedirs(OUT_RAW, exist_ok=True)
    for badge_id, subject, seed in BADGES:
        dest = os.path.join(OUT_RAW, badge_id + ".png")
        if os.path.exists(dest) and os.path.getsize(dest) > 10000:
            print("skip", badge_id, flush=True)
            continue
        print("queue", badge_id, flush=True)
        entry = wait(post("/prompt", {"prompt": workflow(subject, seed), "client_id": "badges3"})["prompt_id"])
        download(entry, dest)
        print("saved", badge_id, flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
