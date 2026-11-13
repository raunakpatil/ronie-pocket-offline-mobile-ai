import os
import sys
import time
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
from PIL import Image
from diffusers import AutoPipelineForText2Image

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if CURRENT_DIR not in sys.path:
    sys.path.insert(0, CURRENT_DIR)

from bakeoff_prompts import BENCHMARK_PROMPTS

def main():
    print("=" * 60)
    print("SD-Turbo: Generating 10 Benchmark Images")
    print("=" * 60)

    out_dir = os.path.join(CURRENT_DIR, "bakeoff", "sd_turbo")
    os.makedirs(out_dir, exist_ok=True)

    print("[1/3] Loading stabilityai/sd-turbo pipeline on CPU...")
    t0 = time.perf_counter()
    pipe = AutoPipelineForText2Image.from_pretrained(
        "stabilityai/sd-turbo",
        torch_dtype=torch.float32
    )
    pipe.to("cpu")
    print(f"      Pipeline loaded in {time.perf_counter() - t0:.2f} s")

    print(f"\n[2/3] Generating {len(BENCHMARK_PROMPTS)} benchmark images...")
    total_start = time.perf_counter()

    for item in BENCHMARK_PROMPTS:
        idx = item["id"]
        tag = item["tag"]
        prompt = item["prompt"]
        out_file = os.path.join(out_dir, f"{idx:02d}_{tag}.png")

        if os.path.exists(out_file):
            print(f"[{idx:02d}/10] {tag} already exists, skipping.")
            continue

        print(f"[{idx:02d}/10] Generating: \"{item['title']}\"...")
        t_gen0 = time.perf_counter()

        seed = 42 + idx
        generator = torch.Generator().manual_seed(seed)

        # SD-Turbo operates in 1-4 steps with guidance_scale=0.0
        # Step=2 gives crisp geometry and fast CPU latency
        image = pipe(
            prompt=prompt,
            num_inference_steps=2,
            guidance_scale=0.0,
            generator=generator,
            width=512,
            height=512
        ).images[0]

        image.save(out_file)
        gen_time = time.perf_counter() - t_gen0
        print(f"      Saved to {out_file} in {gen_time:.2f} s")

    total_time = time.perf_counter() - total_start
    print(f"\n[3/3] SD-Turbo generation complete in {total_time:.2f} s")
    print("=" * 60)

if __name__ == "__main__":
    main()
