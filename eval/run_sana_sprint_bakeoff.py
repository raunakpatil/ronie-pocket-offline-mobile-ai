import os
import sys
import time
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
from PIL import Image
from diffusers import SanaSprintPipeline

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if CURRENT_DIR not in sys.path:
    sys.path.insert(0, CURRENT_DIR)

from bakeoff_prompts import BENCHMARK_PROMPTS

def main():
    print("=" * 60)
    print("SANA-Sprint 0.6B: Generating 10 Benchmark Images")
    print("=" * 60)

    out_dir = os.path.join(CURRENT_DIR, "bakeoff", "sana_sprint")
    os.makedirs(out_dir, exist_ok=True)

    print("[1/3] Loading Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers on CPU...")
    t0 = time.perf_counter()
    pipe = SanaSprintPipeline.from_pretrained(
        "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
        torch_dtype=torch.float32
    )
    # Fix DC-AE epsilon stability if needed
    if hasattr(pipe, 'vae'):
        for m in pipe.vae.modules():
            if hasattr(m, 'eps') and m.eps < 1e-6:
                m.eps = 1e-5

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

        # SANA-Sprint native 2-step SCM inference
        image = pipe(
            prompt=prompt,
            num_inference_steps=2,
            guidance_scale=4.5,
            generator=generator,
            width=1024,
            height=1024,
            clean_caption=False
        ).images[0]

        image.save(out_file)
        gen_time = time.perf_counter() - t_gen0
        print(f"      Saved to {out_file} in {gen_time:.2f} s")

    total_time = time.perf_counter() - total_start
    print(f"\n[3/3] SANA-Sprint generation complete in {total_time:.2f} s")
    print("=" * 60)

if __name__ == "__main__":
    main()
