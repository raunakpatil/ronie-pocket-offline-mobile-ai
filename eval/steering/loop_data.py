"""Phase 3.5 no-loops: collects Qwen3.5 thinking traces and builds loop/clean contrast pairs.

Step 1 (traces):  python eval/steering/loop_data.py traces qwen2b
   Runs the loops set with thinking ON (where Qwen loops), saves reasoning to
   eval/results/looptraces_<model>.jsonl.
Step 2 (pairs):   python eval/steering/loop_data.py pairs qwen2b
   positive = prompt + a window of the trace where it repeats itself
   negative = prompt + a clean worked solution (the same model's thinking-off answer)
   -> eval/steering/noloops-<model>.{pos,neg}.txt for llama-cvector-generator.
"""
import json, re, sys, urllib.request, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))
import run_bakeoff as rb  # noqa: E402

HERE = pathlib.Path(__file__).parent
TRACE_CAP = 1536
WINDOW = 700   # characters of trace / solution per pair


def think_chat(prompt: str, seed: int, greedy: bool = False):
    sampling = ({"temperature": 0.0, "top_k": 1} if greedy else
                {"temperature": 0.6, "top_k": 20, "top_p": 0.95, "seed": seed})   # Qwen's recommended thinking sampling
    body = {"messages": [{"role": "user", "content": prompt}], "max_tokens": TRACE_CAP,
            **sampling, "chat_template_kwargs": {"enable_thinking": True}}
    req = urllib.request.Request(f"http://127.0.0.1:{rb.PORT}/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        out = json.load(r)
    ch = out["choices"][0]
    return ch["message"].get("reasoning_content") or "", ch["message"].get("content") or "", ch.get("finish_reason")


def loop_start(text: str):
    """Index where a chunk (>= 40 chars) starts repeating 3+ times, or None."""
    for size in (40, 80, 160):
        seen = {}
        for i in range(0, max(0, len(text) - size), 10):
            chunk = text[i:i + size]
            if chunk in seen:
                first = seen[chunk]
                if text.count(chunk) >= 3:
                    return first
            else:
                seen[chunk] = i
    return None


def is_correct(content: str, want) -> bool:
    """Last 'Answer: <number>' in the reply equals the expected number."""
    found = re.findall(r"Answer:\s*₹?\s*(-?[\d,]*\.?\d+)", content)
    if not found:
        return False
    try:
        return abs(float(found[-1].replace(",", "")) - float(want)) < 1e-6
    except ValueError:
        return False


def traces(model_key: str, mode: str = "sampled"):
    items = rb.load("loops")
    proc = rb.start_server(rb.CANDIDATES[model_key]["file"])
    out = rb.RESULTS / f"looptraces_{model_key}_{mode}.jsonl"
    try:
        with out.open("w", encoding="utf-8") as f:
            for it in items:
                r, c, fin = think_chat(it["prompt"], seed=7, greedy=(mode == "greedy"))
                ls = loop_start(r)
                correct = is_correct(c, it["expect"]["answer"])
                f.write(json.dumps({"id": it["id"], "prompt": it["prompt"], "reasoning": r, "content": c,
                                    "finish": fin, "loop_start": ls, "correct": correct},
                                   ensure_ascii=False) + "\n")
                f.flush()
                print(it["id"], fin, "loop@" + str(ls), flush=True)
    finally:
        proc.kill(); proc.wait()
    print("wrote", out)


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")


def qwen_prefix(prompt: str) -> str:
    return f"<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n<think>\n"


def pairs(model_key: str, mode: str = "both"):
    modes = ("greedy", "sampled") if mode == "both" else (mode,)
    tr = [json.loads(l) for m in modes
          for l in (rb.RESULTS / f"looptraces_{model_key}_{m}.jsonl").open(encoding="utf-8")]
    clean = {json.loads(l)["id"]: json.loads(l)["content"]
             for l in (rb.RESULTS / f"{model_key}__nothink__loops.jsonl").open(encoding="utf-8")}
    pos, neg = [], []
    for t in tr:
        ls = t["loop_start"]
        sol = clean.get(t["id"], "")
        if ls is None or len(sol) < 80:
            continue
        # Up to three windows per looping trace (the loop repeats, so later windows are loop too).
        for k in range(3):
            loop_window = t["reasoning"][ls + k * WINDOW: ls + (k + 1) * WINDOW]
            if len(loop_window) < WINDOW // 2:
                break
            pos.append(esc(qwen_prefix(t["prompt"]) + loop_window))
            neg.append(esc(qwen_prefix(t["prompt"]) + sol[:WINDOW]))
    (HERE / f"noloops-{model_key}.pos.txt").write_text("\n".join(pos) + "\n", encoding="utf-8")
    (HERE / f"noloops-{model_key}.neg.txt").write_text("\n".join(neg) + "\n", encoding="utf-8")
    print(f"{model_key}: {len(pos)} pairs from {len(tr)} traces")


if __name__ == "__main__":
    {"traces": traces, "pairs": pairs}[sys.argv[1]](*sys.argv[2:])
