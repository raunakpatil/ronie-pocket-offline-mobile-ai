"""Phase 3.5 training jobs on Modal (steering directions, loop traces, X-ray probes).

The image builds OUR llama.cpp (third_party/llama.cpp, with scripts/patches applied) with CUDA, so the
tools match the phone build: llama-server, the patched llama-cvector-generator, and probe_dump.
Models come from Hugging Face into the shared "ronie-models" volume. Only synthetic data is uploaded.

Usage (from the project root, with MODAL_PROFILE set):
  modal run eval/modal_steering.py --job traces --model qwen2b          # greedy + sampled thinking traces
  modal run eval/modal_steering.py --job xray                           # dump activations + train the probe
"""
import pathlib
import modal

ROOT = pathlib.Path(__file__).resolve().parent.parent
app = modal.App("ronie-steering")
models_vol = modal.Volume.from_name("ronie-models", create_if_missing=True)

LLAMA_SRC = ROOT / "third_party" / "llama.cpp"


def LLAMA_IGNORE(p) -> bool:
    """Skip .git, models and top-level build*/ folders (but not files like common/build-info.cpp.in)."""
    path = pathlib.Path(p)
    rel = path.relative_to(LLAMA_SRC) if path.is_absolute() else path
    parts = rel.parts
    if not parts:
        return False
    return ".git" in parts or parts[0] == "models" or (parts[0].startswith("build") and len(parts) > 1)

image = (
    modal.Image.from_registry("nvidia/cuda:12.4.1-devel-ubuntu22.04", add_python="3.12")
    .apt_install("cmake", "build-essential", "git")
    .pip_install("numpy", "huggingface_hub")
    .add_local_dir(LLAMA_SRC, "/src/llama.cpp", copy=True, ignore=LLAMA_IGNORE)
    .add_local_dir(ROOT / "tools-pc" / "probe", "/src/ronie/tools-pc/probe", copy=True)
    .run_commands(
        "mkdir -p /src/ronie/third_party && ln -s /src/llama.cpp /src/ronie/third_party/llama.cpp",
        "cmake -S /src/ronie/tools-pc/probe -B /build -DCMAKE_BUILD_TYPE=Release -DRONIE_ALL_TOOLS=ON "
        "-DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES='75;86;89' -DGGML_NATIVE=OFF -DLLAMA_CURL=OFF "
        "-DLLAMA_OPENSSL=OFF -DCMAKE_EXE_LINKER_FLAGS=-Wl,--allow-shlib-undefined",
        "cmake --build /build -j 16 --target probe_dump llama-server llama-cvector-generator",
        # llama.cpp is a subproject here, so its binaries are not collected in /build/bin: find them.
        "mkdir -p /bin-ronie && for b in probe_dump llama-server llama-cvector-generator; do "
        "cp \"$(find /build -type f -name $b -perm -u+x | head -n1)\" /bin-ronie/; done && ls -la /bin-ronie",
    )
    .add_local_file(ROOT / "eval" / "run_bakeoff.py", "/root/eval/run_bakeoff.py")
    .add_local_file(ROOT / "eval" / "tools.py", "/root/eval/tools.py")
    .add_local_dir(ROOT / "eval" / "sets", "/root/eval/sets", ignore=lambda p: "private" in str(p))
    .add_local_dir(ROOT / "eval" / "steering", "/root/eval/steering", ignore=lambda p: str(p).endswith(".gguf"))
    .add_local_dir(ROOT / "eval" / "xray", "/root/eval/xray", ignore=lambda p: str(p).endswith(".bin"))
)

GPUS = ["L4", "A10G", "T4"]
HF = {
    "gemma-4-E2B-it-Q4_K_M.gguf": "unsloth/gemma-4-E2B-it-GGUF",
    "Qwen3.5-2B-Q4_K_M.gguf": "unsloth/Qwen3.5-2B-GGUF",
    "Qwen3.5-0.8B-Q4_K_M.gguf": "unsloth/Qwen3.5-0.8B-GGUF",
    "Qwen3.5-4B-Q4_K_M.gguf": "unsloth/Qwen3.5-4B-GGUF",
}


def _env():
    import os, sys
    os.environ.update({"RONIE_SERVER": "/bin-ronie/llama-server", "RONIE_MODELS": "/models",
                       "RONIE_RESULTS": "/tmp/results", "RONIE_SERVER_ARGS": "-ngl 99 -t 8"})
    os.makedirs("/tmp/results", exist_ok=True)
    sys.path.insert(0, "/root/eval")
    sys.path.insert(0, "/root/eval/steering")


def _model(file):
    import os
    from huggingface_hub import hf_hub_download
    if not os.path.exists(f"/models/{file}"):
        hf_hub_download(HF[file], file, local_dir="/models")
        models_vol.commit()
    return f"/models/{file}"


@app.function(image=image, gpu=GPUS, timeout=2 * 3600, volumes={"/models": models_vol})
def traces(model_key: str, mode: str) -> dict:
    _env()
    import glob, os
    import run_bakeoff as rb
    import loop_data
    _model(rb.CANDIDATES[model_key]["file"])
    loop_data.traces(model_key, mode)
    return {os.path.basename(p): open(p, encoding="utf-8").read() for p in glob.glob("/tmp/results/looptraces_*")}


@app.function(image=image, gpu=GPUS, timeout=2 * 3600, volumes={"/models": models_vol})
def xray() -> dict:
    import subprocess, pathlib, shutil
    model = _model("gemma-4-E2B-it-Q4_K_M.gguf")
    work = pathlib.Path("/tmp/xray")
    work.mkdir(exist_ok=True)
    for split in ("train", "test"):
        shutil.copy(f"/root/eval/xray/data_{split}.jsonl", work)
    # Gemma's own answers to the test notes (the honest test), generated with phone-like sampling.
    _env()
    import run_bakeoff as rb
    srv = rb.start_server("gemma-4-E2B-it-Q4_K_M.gguf")
    try:
        subprocess.run(["python", "/root/eval/xray/own_answers.py", "generate", str(work)], check=True)
    finally:
        srv.kill(); srv.wait()
    # Label with a stronger judge model (the PC-side teacher), not a regex.
    _model("Qwen3.5-4B-Q4_K_M.gguf")
    srv = rb.start_server("Qwen3.5-4B-Q4_K_M.gguf")
    try:
        subprocess.run(["python", "/root/eval/xray/own_answers.py", "judge", str(work)], check=True)
    finally:
        srv.kill(); srv.wait()
    for split in ("train", "test", "own"):
        subprocess.run(["/bin-ronie/probe_dump", "-m", model, "-i", str(work / f"data_{split}.jsonl"),
                        "-o", str(work / f"{split}.bin"), "-ngl", "99", "-t", "8"], check=True)
    subprocess.run(["python", "/root/eval/xray/train_probe.py", str(work)], check=True)
    return {n: (work / n).read_text(encoding="utf-8") for n in ("probe-gemma4e2b.json", "receipt.json", "data_own.jsonl")}


@app.function(image=image, gpu=GPUS, timeout=3600, volumes={"/models": models_vol})
def cvector(model_file: str, pos: str, neg: str) -> bytes:
    """Trains a mean-difference control vector with the patched llama-cvector-generator."""
    import subprocess, pathlib
    m = _model(model_file)
    w = pathlib.Path("/tmp/cv"); w.mkdir(exist_ok=True)
    (w / "pos.txt").write_text(pos, encoding="utf-8"); (w / "neg.txt").write_text(neg, encoding="utf-8")
    subprocess.run(["/bin-ronie/llama-cvector-generator", "-m", m, "--positive-file", str(w / "pos.txt"),
                    "--negative-file", str(w / "neg.txt"), "--method", "mean", "-c", "4096", "-ngl", "99",
                    "-o", str(w / "out.gguf")], check=True, stdout=subprocess.DEVNULL)
    return (w / "out.gguf").read_bytes()


def _random_like(src: str, dst: str, seed: int = 7):
    """Writes a control vector with random directions of the same per-layer norm as src (the control)."""
    import sys
    import numpy as np
    sys.path.insert(0, "/src/llama.cpp/gguf-py")
    import gguf
    r = gguf.GGUFReader(src)
    rng = np.random.default_rng(seed)
    w = gguf.GGUFWriter(dst, "controlvector")
    w.add_string("controlvector.model_hint", "random-control")
    tensors = [t for t in r.tensors if t.name.startswith("direction.")]
    w.add_uint32("controlvector.layer_count", len(tensors))
    for t in tensors:
        v = np.array(t.data, dtype=np.float32)
        rnd = rng.standard_normal(v.shape).astype(np.float32)
        w.add_tensor(t.name, rnd * (np.linalg.norm(v) / np.linalg.norm(rnd)))
    w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()


@app.function(image=image, gpu=GPUS, timeout=3 * 3600, volumes={"/models": models_vol})
def loop_eval(model_key: str, vec: bytes, strength: float, layers: str, control: bool = False,
              mode: str = "sampled") -> dict:
    """Thinking mode on the loops set with the no-loops direction at one strength (or the random control)."""
    _env()
    import os, json
    os.chdir("/tmp")
    open("/tmp/vec.gguf", "wb").write(vec)
    path = "vec.gguf"
    if control:
        _random_like("/tmp/vec.gguf", "/tmp/rand.gguf"); path = "rand.gguf"
    import run_bakeoff as rb
    import loop_data
    lo, hi = layers.split("-")
    args = ["-ngl", "99", "-t", "8"]
    if strength != 0:
        args += ["--control-vector-scaled", f"{path}:{strength}", "--control-vector-layer-range", lo, hi]
    rb.EXTRA_SERVER_ARGS = args
    _model(rb.CANDIDATES[model_key]["file"])
    proc = rb.start_server(rb.CANDIDATES[model_key]["file"])
    rows = []
    try:
        for it in rb.load("loops"):
            r, c, fin = loop_data.think_chat(it["prompt"], seed=7, greedy=(mode == "greedy"))
            rows.append({"id": it["id"], "finish": fin, "loop": loop_data.loop_start(r) is not None,
                         "correct": loop_data.is_correct(c, it["expect"]["answer"]), "chars": len(r),
                         "reasoning_tail": r[-400:], "content": c[-300:]})
    finally:
        proc.kill(); proc.wait()
    n = len(rows)
    return {"strength": strength, "control": control, "layers": layers, "mode": mode, "n": n,
            "correct": sum(r["correct"] for r in rows), "hit_cap": sum(r["finish"] == "length" for r in rows),
            "loops": sum(r["loop"] for r in rows), "rows": rows}


@app.local_entrypoint()
def main(job: str, model: str = "qwen2b", strengths: str = "0,-0.5,-1,-2,-4", layers: str = "6-18",
         control_strength: float = -2.0):
    if job == "traces":
        out = ROOT / "eval" / "results"
        for files in traces.starmap([(model, "greedy"), (model, "sampled")], return_exceptions=True):
            if isinstance(files, Exception):
                print("!! run failed:", files); continue
            for name, text in files.items():
                (out / name).write_text(text, encoding="utf-8"); print("saved", name)
    elif job == "noloops":
        import json
        st = ROOT / "eval" / "steering"
        file = {"qwen2b": "Qwen3.5-2B-Q4_K_M.gguf", "qwen08b": "Qwen3.5-0.8B-Q4_K_M.gguf"}[model]
        vec = cvector.remote(file, (st / f"noloops-{model}.pos.txt").read_text(encoding="utf-8"),
                             (st / f"noloops-{model}.neg.txt").read_text(encoding="utf-8"))
        (st / f"noloops-{model}.gguf").write_bytes(vec)
        print("saved", f"noloops-{model}.gguf", len(vec), "bytes")
        runs = [(model, vec, float(s), layers, False) for s in strengths.split(",")]
        runs.append((model, vec, control_strength, layers, True))   # same-norm random direction
        results = []
        for r in loop_eval.starmap(runs, return_exceptions=True):
            if isinstance(r, Exception):
                print("!! run failed:", r); continue
            results.append(r)
            print(f"{'RANDOM ' if r['control'] else ''}strength {r['strength']:+.2f}: correct {r['correct']}/{r['n']}, "
                  f"hit cap {r['hit_cap']}, loops {r['loops']}")
        out = ROOT / "eval" / "results" / f"noloops_{model}_L{layers}.json"
        out.write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")
        print("wrote", out)
    elif job == "xray":
        files = xray.remote()
        for name, text in files.items():
            (ROOT / "eval" / "xray" / name).write_text(text, encoding="utf-8"); print("saved", name)
        print(files["receipt.json"])
