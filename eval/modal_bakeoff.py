"""Runs the Phase 2 bake-off on Modal cloud GPUs: one L4 per candidate model, in parallel.

Only the synthetic eval sets and the harness are uploaded; models are pulled from Hugging Face
inside the container and cached in a Modal volume. NEVER run private eval data (real emails) here.

Usage (from the project root):
  modal run eval/modal_bakeoff.py                     # all candidates
  modal run eval/modal_bakeoff.py --models qwen2b,qwen4b
Results are written to eval/results/ and summarised in eval/results/summary.md.
"""
import pathlib
import modal

ROOT = pathlib.Path(__file__).resolve().parent.parent
app = modal.App("ronie-bakeoff")
models_vol = modal.Volume.from_name("ronie-models", create_if_missing=True)

image = (
    modal.Image.from_registry("ghcr.io/ggml-org/llama.cpp:server-cuda", add_python="3.12")
    .entrypoint([])
    .pip_install("huggingface_hub")
    .add_local_file(ROOT / "eval" / "run_bakeoff.py", "/root/eval/run_bakeoff.py")
    .add_local_file(ROOT / "eval" / "tools.py", "/root/eval/tools.py")
    .add_local_dir(ROOT / "eval" / "sets", "/root/eval/sets",
                   ignore=lambda p: "private" in str(p))   # belt and braces: never ship private sets
)


# GPU fallbacks: L4 is often fully booked; A10G / T4 run the same quantized models fine.
@app.function(image=image, gpu=["L4", "A10G", "T4"], timeout=3 * 3600, volumes={"/models": models_vol})
def run_one(key: str, sets: list[str]) -> dict:
    import os, sys, glob
    os.environ.update({
        "RONIE_SERVER": "/app/llama-server",
        "RONIE_MODELS": "/models",
        "RONIE_RESULTS": "/tmp/results",
        "RONIE_SERVER_ARGS": "-ngl 99 -t 8",
    })
    os.makedirs("/tmp/results", exist_ok=True)
    sys.path.insert(0, "/root/eval")
    import run_bakeoff as rb
    from huggingface_hub import hf_hub_download

    cfg = rb.CANDIDATES[key]
    if not os.path.exists(f"/models/{cfg['file']}"):
        hf_hub_download(cfg["repo"], cfg["file"], local_dir="/models")
        models_vol.commit()
    rb.run_model(key, sets)
    return {os.path.basename(p): open(p, encoding="utf-8").read() for p in glob.glob("/tmp/results/*.jsonl")}


@app.local_entrypoint()
def main(models: str = "qwen08b,qwen2b,gemma4e2b,lfm25,qwen4b",
         sets: str = "tools,persona,grounded_qa,loops,summaries,triage_synthetic"):
    import sys
    out_dir = ROOT / "eval" / "results"
    out_dir.mkdir(exist_ok=True)
    keys = models.split(",")
    set_list = sets.split(",")
    for files in run_one.map(keys, kwargs={"sets": set_list}, return_exceptions=True):
        if isinstance(files, Exception):
            print("!! a model run failed:", files)
            continue
        for name, text in files.items():
            (out_dir / name).write_text(text, encoding="utf-8")
            print("saved", name)
    sys.path.insert(0, str(ROOT / "eval"))
    import run_bakeoff as rb
    rb.summarize()
