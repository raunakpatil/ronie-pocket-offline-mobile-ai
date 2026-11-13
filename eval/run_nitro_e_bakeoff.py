import os
import sys
import time
import numpy as np
from PIL import Image
import onnxruntime as ort
from transformers import AutoTokenizer

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if CURRENT_DIR not in sys.path:
    sys.path.insert(0, CURRENT_DIR)

from bakeoff_prompts import BENCHMARK_PROMPTS

def main():
    print("=" * 60)
    print("Nitro-E 0.3B: Generating 10 Benchmark Images")
    print("=" * 60)

    out_dir = os.path.join(CURRENT_DIR, "bakeoff", "nitro_e")
    os.makedirs(out_dir, exist_ok=True)

    # Session options
    sess_opts = ort.SessionOptions()
    sess_opts.intra_op_num_threads = 4
    sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

    print("[1/3] Loading ONNX Runtime Sessions...")
    llama_path = os.path.join(CURRENT_DIR, "nitro_e", "llama_3_2_1b_encoder_fp16.onnx")
    emmdit_path = os.path.join(CURRENT_DIR, "nitro_e", "nitro_e_dist_512_fp16_native.onnx")
    dcae_path = os.path.join(CURRENT_DIR, "nitro_e", "dcae_decoder_512_fp16_native.onnx")

    t0 = time.perf_counter()
    llama_sess = ort.InferenceSession(llama_path, sess_opts, providers=["CPUExecutionProvider"])
    emmdit_sess = ort.InferenceSession(emmdit_path, sess_opts, providers=["CPUExecutionProvider"])
    dcae_sess = ort.InferenceSession(dcae_path, sess_opts, providers=["CPUExecutionProvider"])
    print(f"      Sessions loaded in {time.perf_counter() - t0:.2f} s")

    tokenizer = AutoTokenizer.from_pretrained("unsloth/Llama-3.2-1B")
    timesteps = [1000, 667, 334, 1]
    sigmas = [1.0, 0.667, 0.334, 0.001, 0.0]

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

        # 1. Text encoding
        encoded = tokenizer(
            prompt,
            padding="max_length",
            max_length=128,
            truncation=True,
            return_tensors="np"
        )
        prompt_embeds = llama_sess.run(
            ["last_hidden_state"],
            {
                "input_ids": encoded["input_ids"].astype(np.int64),
                "attention_mask": encoded["attention_mask"].astype(np.int64)
            }
        )[0]

        # 2. Denoising loop
        seed = 42 + idx
        np.random.seed(seed)
        latents = np.random.randn(1, 32, 16, 16).astype(np.float32)

        for s_idx, t in enumerate(timesteps):
            t_arr = np.array([t], dtype=np.int64)
            noise_pred = emmdit_sess.run(
                ["sample"],
                {
                    "hidden_states": latents,
                    "encoder_hidden_states": prompt_embeds,
                    "timestep": t_arr
                }
            )[0]
            dt = sigmas[s_idx + 1] - sigmas[s_idx]
            latents = latents + dt * noise_pred

        # 3. Latent decode
        img = dcae_sess.run(["image"], {"latents": latents})[0]
        img_hwc = (np.transpose(img[0], (1, 2, 0)) * 255.0).round().astype(np.uint8)

        Image.fromarray(img_hwc).save(out_file)
        gen_time = time.perf_counter() - t_gen0
        print(f"      Saved to {out_file} in {gen_time:.2f} s")

    total_time = time.perf_counter() - total_start
    print(f"\n[3/3] Nitro-E generation complete in {total_time:.2f} s")
    print("=" * 60)

if __name__ == "__main__":
    main()
