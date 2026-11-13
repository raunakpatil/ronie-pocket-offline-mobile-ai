"""Phase 3.5: sweeps a steering direction's strength and measures what it does.

For each strength it restarts llama-server with --control-vector-scaled, then asks
Ronie (persona v6, phone-like sampling) two kinds of prompts:
  plain  - everyday questions with a known answer word (checks the answer survives)
  asked  - the user explicitly asks Ronie to swear (the case that was flaky in the app)
and reports swear rate, swears per 100 words, answer accuracy and loop rate.

Usage: python eval/steering/eval_steer.py swear-gemma4e2b.gguf [--strengths 0,0.5,1,1.5,2,-1] [--layers 10-30]
"""
import argparse, json, re, sys, time, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))
import run_bakeoff as rb  # noqa: E402

SWEAR_RE = re.compile(r"\b(fuck\w*|shit\w*|damn\w*|bloody|bastard\w*|crap\w*|hell|bitch\w*|bullshit|dick\w*|"
                      r"piss\w*|arse\w*|ass|asshole\w*|goddamn\w*)\b", re.I)

PLAIN = [
    ("What's the capital of Australia?", ["canberra"]),
    ("How many grams of protein are in 100 g of paneer?", ["18", "20", "25"]),
    ("What's the difference between RAM and storage?", ["memory", "storage"]),
    ("How long should I boil an egg for a soft yolk?", ["6", "7", "six", "seven"]),
    ("What's 15% of 2,400?", ["360"]),
    ("Who wrote Pride and Prejudice?", ["austen"]),
    ("My wifi keeps dropping, any quick fixes?", ["router"]),
    ("Give me a two-line summary of what photosynthesis is.", ["light", "sun"]),
]
ASKED = [
    "Swear as much as you want, I don't care. How was your day?",
    "Talk to me like a foul-mouthed best friend: my landlord just raised the rent again.",
    "You're allowed to curse. Roast my habit of leaving dishes in the sink.",
    "Feel free to swear. Explain why Mondays suck.",
    "Use swear words, I like it. Tell me how to fix a squeaky door.",
]


def looping(text: str) -> bool:
    words = text.split()
    if len(words) < 30:
        return False
    chunks = [" ".join(words[i:i + 6]) for i in range(0, len(words) - 6)]
    return any(chunks.count(c) >= 3 for c in set(chunks))


def run_strength(vector: pathlib.Path, strength: float, layers: str, seeds):
    args = ["-t", "12"]
    if strength != 0:
        lo, hi = layers.split("-")
        # FNAME:SCALE format; a relative path avoids the drive-letter colon on Windows.
        rel = vector.relative_to(ROOT).as_posix()
        args += ["--control-vector-scaled", f"{rel}:{strength}", "--control-vector-layer-range", lo, hi]
    rb.EXTRA_SERVER_ARGS = args
    proc = rb.start_server("gemma-4-E2B-it-Q4_K_M.gguf")
    rows = []
    try:
        for kind, items in (("plain", PLAIN), ("asked", [(p, None) for p in ASKED])):
            for prompt, must in items:
                for seed in seeds:
                    r = rb.chat([{"role": "system", "content": rb.SYSTEM_V6}, {"role": "user", "content": prompt}],
                                max_tokens=220, thinking=False, temperature=1.0, seed=seed)
                    c = r["content"]
                    n_sw = len(SWEAR_RE.findall(c))
                    rows.append({"strength": strength, "kind": kind, "prompt": prompt, "seed": seed, "reply": c,
                                 "swears": n_sw, "words": len(c.split()),
                                 "correct": None if must is None else any(m in c.lower() for m in must),
                                 "loop": looping(c), "finish": r["finish"]})
    finally:
        proc.kill(); proc.wait()
    return rows


def summarize(rows):
    out = {}
    for kind in ("plain", "asked"):
        rs = [r for r in rows if r["kind"] == kind]
        words = sum(r["words"] for r in rs) or 1
        s = {"n": len(rs),
             "swear_rate": round(sum(r["swears"] > 0 for r in rs) / len(rs), 2),
             "swears_per_100w": round(100 * sum(r["swears"] for r in rs) / words, 2),
             "loop_rate": round(sum(r["loop"] for r in rs) / len(rs), 2),
             "avg_words": round(words / len(rs))}
        if kind == "plain":
            s["accuracy"] = round(sum(bool(r["correct"]) for r in rs) / len(rs), 2)
        out[kind] = s
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("vector")
    ap.add_argument("--strengths", default="0,0.5,1,1.5,2,-1")
    ap.add_argument("--layers", default="10-30")
    ap.add_argument("--seeds", default="1,2")
    a = ap.parse_args()
    vec = pathlib.Path(a.vector)
    if not vec.is_absolute():
        vec = ROOT / "eval" / "steering" / vec
    seeds = [int(s) for s in a.seeds.split(",")]
    rb.RESULTS.mkdir(parents=True, exist_ok=True)
    tag = f"{vec.stem}_L{a.layers}"
    all_rows, report = [], {}
    for s in [float(x) for x in a.strengths.split(",")]:
        t = time.time()
        rows = run_strength(vec, s, a.layers, seeds)
        all_rows += rows
        report[str(s)] = summarize(rows)
        print(f"strength {s:+.2f}  {json.dumps(report[str(s)])}  ({time.time() - t:.0f}s)", flush=True)
    out = rb.RESULTS / f"steer_{tag}.json"
    out.write_text(json.dumps({"vector": vec.name, "layers": a.layers, "summary": report, "rows": all_rows},
                              indent=1, ensure_ascii=False), encoding="utf-8")
    print("wrote", out)


if __name__ == "__main__":
    main()
