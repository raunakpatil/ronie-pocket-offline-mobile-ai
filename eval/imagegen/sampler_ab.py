"""Compares samplers / schedulers / prompt styles for SD-Turbo at the phone's settings (3 steps, cfg 1, TAESD decode),
on the PC with the same stable-diffusion.cpp source and the same Q4 model file. Same seed for every cell, so
differences come from the setting alone. Output: build/sd-ab/sheet_<prompt>.png (rows = configs).

  scripts\\build-sd-pc.cmd
  python eval/imagegen/sampler_ab.py
"""
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SD = ROOT / "build/sd-pc/bin/sd-cli.exe"
MODEL = ROOT / "models-local/sd_turbo_q4_0.gguf"
TAESD = ROOT / "models-local/taesd.safetensors"
OUT = ROOT / "build/sd-ab"
OUT.mkdir(parents=True, exist_ok=True)

PROMPTS = {
    "cat": "a cat astronaut in watercolour",
    "sailor": "portrait of an old sailor with a white beard, photo",
    "city": "a cyberpunk city street at night with neon signs, rain",
}
BOOST = ", intricate details, fine textures, sharp focus, rich colours, dramatic lighting, masterpiece, best quality"

# (label, sampler, scheduler, steps, boosted prompt?, full VAE for the final image?)
CONFIGS = [
    ("euler_a/discrete (phone today)", "euler_a", "discrete", 3, False, False),
    ("euler_a/discrete + boost", "euler_a", "discrete", 3, True, False),
    ("euler_a/sgm_uniform + boost", "euler_a", "sgm_uniform", 3, True, False),
    ("euler_a/simple + boost", "euler_a", "simple", 3, True, False),
    ("euler/sgm_uniform + boost", "euler", "sgm_uniform", 3, True, False),
    ("lcm/sgm_uniform + boost", "lcm", "sgm_uniform", 3, True, False),
    ("tcd/sgm_uniform + boost", "tcd", "sgm_uniform", 3, True, False),
    ("ddim_trailing/discrete + boost", "ddim_trailing", "discrete", 3, True, False),
    ("euler_a/sgm_uniform + boost, full VAE", "euler_a", "sgm_uniform", 3, True, True),
    ("euler_a/sgm_uniform + boost, 4 steps", "euler_a", "sgm_uniform", 4, True, False),
]
SEEDS = [42, 7]


def run(name, prompt, sampler, sched, steps, seed, vae):
    out = OUT / f"{name}.png"
    if out.exists():
        return out
    cmd = [str(SD), "-m", str(MODEL), "--taesd", str(TAESD), "-p", prompt, "--steps", str(steps), "--cfg-scale", "1",
           "--sampling-method", sampler, "--scheduler", sched, "-W", "512", "-H", "512", "-s", str(seed), "-o", str(out)]
    if vae:
        cmd.append("--taesd-preview-only")
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    if not out.exists():
        print("FAILED", name, r.stdout[-300:], r.stderr[-300:])
        return None
    return out


for pk, prompt in PROMPTS.items():
    rows = []
    for ci, (label, sampler, sched, steps, boost, vae) in enumerate(CONFIGS):
        cells = [run(f"{pk}_c{ci}_s{seed}", prompt + (BOOST if boost else ""), sampler, sched, steps, seed, vae) for seed in SEEDS]
        rows.append((label, cells))
        print(pk, label, "ok" if all(cells) else "MISSING", flush=True)
    sheet = Image.new("RGB", (40 + 512 * len(SEEDS), 536 * len(rows)), (20, 20, 24))
    d = ImageDraw.Draw(sheet)
    for ri, (label, cells) in enumerate(rows):
        d.text((6, ri * 536 + 4), f"{ri}: {label}", fill=(255, 255, 255))
        for si, c in enumerate(cells):
            if c:
                sheet.paste(Image.open(c).convert("RGB"), (40 + si * 512, ri * 536 + 22))
    sheet.save(OUT / f"sheet_{pk}.png")
print("done")
