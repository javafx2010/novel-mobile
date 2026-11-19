"""Generate one stylized avatar medallion per Edge TTS voice via ComfyUI."""
import json, os, time, urllib.request

SERVER = "http://127.0.0.1:8188"
OUT_RAW = r"F:\Create\novel-mobile\icon_work\voice_icons"

STYLE = (
    "flat vector avatar icon, head and shoulders portrait of {subject}, centered inside a "
    "simple circle, warm brass gold and amber line art with subtle gradients on a deep dark "
    "brown background, minimal, bold, symmetrical, clean geometric shapes, friendly, "
    "isolated, no text, no letters, no words, no watermark, no mockup, no 3d render, no frame"
)

# (file id, subject, seed)
VOICES = [
    ("xiaoxiao", "a young East Asian woman with long straight black hair, gentle smile", 4201),
    ("xiaoyi", "a young East Asian woman with a short bob haircut, cheerful expression", 4202),
    ("yunxi", "a young East Asian man with short neat hair, friendly smile", 4203),
    ("yunjian", "an East Asian man with a neat short beard, calm confident look", 4204),
    ("yunyang", "an East Asian man in his thirties with side parted hair, warm smile", 4205),
    ("yunxia", "a young East Asian man with soft wavy hair, bright expression", 4206),
    ("emma", "a young Western woman with wavy blonde hair, soft smile", 4207),
    ("ava", "a young Western woman with short auburn hair, lively expression", 4208),
    ("andrew", "a Western man with short brown hair and light stubble, friendly", 4209),
    ("brian", "a Western man with wavy dark hair, warm smile", 4210),
    ("aria", "a young Western woman with long brown hair, elegant look", 4211),
    ("guy", "a Western man with short spiky hair, cheerful", 4212),
]


def post(path, payload):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(SERVER + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def get(path):
    with urllib.request.urlopen(SERVER + path, timeout=30) as resp:
        return json.loads(resp.read())


def workflow(subject, seed):
    return {
        "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
        "2": {"class_type": "CLIPTextEncode", "inputs": {"text": STYLE.format(subject=subject), "clip": ["1", 1]}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"text": "text, letters, words, numbers, watermark, photo, photorealistic, 3d render, scene, mockup, frame, border, cropped, cut off, ugly, blurry, dark muddy colors", "clip": ["1", 1]}},
        "4": {"class_type": "EmptyLatentImage", "inputs": {"width": 768, "height": 768, "batch_size": 1}},
        "5": {"class_type": "KSampler", "inputs": {"seed": seed, "steps": 24, "cfg": 7.0, "sampler_name": "dpmpp_2m", "scheduler": "karras", "denoise": 1.0, "model": ["1", 0], "positive": ["2", 0], "negative": ["3", 0], "latent_image": ["4", 0]}},
        "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage", "inputs": {"filename_prefix": "voice", "images": ["6", 0]}},
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
    for vid, subject, seed in VOICES:
        dest = os.path.join(OUT_RAW, vid + ".png")
        if os.path.exists(dest) and os.path.getsize(dest) > 10000:
            print("skip", vid, flush=True)
            continue
        print("queue", vid, flush=True)
        entry = wait(post("/prompt", {"prompt": workflow(subject, seed), "client_id": "voices"})["prompt_id"])
        download(entry, dest)
        print("saved", vid, flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
