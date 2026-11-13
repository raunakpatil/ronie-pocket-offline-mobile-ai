"""Summarises Qwen thinking traces: how often it loops, runs out of budget, or gets the answer right.
Usage: python eval/steering/loop_stats.py qwen2b
"""
import json, sys, pathlib

RES = pathlib.Path(__file__).resolve().parents[1] / "results"
model = sys.argv[1] if len(sys.argv) > 1 else "qwen2b"
for mode in ("greedy", "sampled"):
    p = RES / f"looptraces_{model}_{mode}.jsonl"
    if not p.exists():
        continue
    rows = [json.loads(l) for l in p.open(encoding="utf-8")]
    n = len(rows)
    looped = sum(r["loop_start"] is not None for r in rows)
    capped = sum(r["finish"] == "length" for r in rows)
    correct = sum(bool(r.get("correct")) for r in rows)
    loop_and_cap = sum(r["loop_start"] is not None and r["finish"] == "length" for r in rows)
    avg_len = sum(len(r["reasoning"]) for r in rows) / n
    print(f"{model} {mode:8s} n={n}  correct {correct}/{n}  hit cap {capped}/{n}  "
          f"loop detected {looped}/{n} (of which hit cap {loop_and_cap})  avg reasoning {avg_len:.0f} chars")
