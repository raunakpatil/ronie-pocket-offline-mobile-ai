"""Evaluates Ronie's vision understanding across 80 multimodal test cases.

Usage:
  llama-server -m models-local/gemma-4-E2B-it-Q4_K_M.gguf --mmproj models-local/gemma-mmproj-ggml/mmproj-gemma-4-E2B-it-Q8_0.gguf --special --port 8092 -c 4096
  python eval/vision/run.py [--port 8092] [--tag base]
"""
import argparse
import base64
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

import importlib.util

ROOT = Path(__file__).resolve().parents[2]
VISION_DIR = Path(__file__).resolve().parent

def load_mod(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

vision_cases_mod = load_mod("vision_cases", VISION_DIR / "cases.py")
CASES = vision_cases_mod.CASES
DATA_DIR = vision_cases_mod.DATA_DIR

tool_cases_mod = load_mod("tool_cases", ROOT / "eval/tools_v2/cases.py")
TODAY = tool_cases_mod.TODAY

sys.path.insert(0, str(ROOT / "eval/tools_v2"))
import gemma_tools as G

TOOLS = json.load(open(ROOT / "app/src/main/assets/tools/builtin.json", encoding="utf-8"))["tools"]


def persona():
    src = (ROOT / "app/src/main/java/com/raunakpatil/roniepocket/Persona.kt").read_text(encoding="utf-8")
    return src.split('return """', 1)[1].split('"""', 1)[0].strip()


def tool_guide():
    p = ROOT / "app/src/main/java/com/raunakpatil/roniepocket/tools/ToolPrompt.kt"
    if not p.exists():
        return ""
    src = p.read_text(encoding="utf-8")
    return src.split('GUIDE = """', 1)[1].split('"""', 1)[0].strip()


def date_lines(today):
    import datetime as dt
    d = dt.datetime.strptime(today, "%A, %d %B %Y").date()
    nxt = ", ".join(f"{(d + dt.timedelta(i)).strftime('%a')} {(d + dt.timedelta(i)).isoformat()}" for i in range(1, 8))
    return f"Today is {today} ({d.isoformat()}). Coming days: {nxt}."


def system_prompt():
    body = persona()
    stable = body.split("Today is ")[0].rstrip()
    guide = tool_guide()
    decls = "".join(G.declaration(t) for t in TOOLS)
    date_line = date_lines(TODAY)
    return stable + ("\n" + guide if guide else "") + decls + "\n" + date_line


def parse_expense_block(text):
    # Match ```expense { ... } ``` or ```json { ... } ```
    m = re.search(r"```(?:expense|json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(1))
        except Exception:
            pass
    # Match call:expense { ... } or call:expense\n{ ... }
    m_call = re.search(r"call:expense\s*(\{.*?\})", text, re.DOTALL)
    if m_call:
        raw = m_call.group(1).replace('<|\\\"|>', '"').replace('<|\"|>', '"')
        cleaned = re.sub(r'([a-zA-Z0-9_]+)\s*:', r'"\1":', raw)
        try:
            return json.loads(cleaned)
        except Exception:
            try:
                return json.loads("{" + cleaned + "}")
            except Exception:
                pass
            res = {}
            m_merch = re.search(r'"merchant"\s*:\s*"([^"]+)"', cleaned)
            if m_merch:
                res["merchant"] = m_merch.group(1)
            m_tot = re.search(r'"total"\s*:\s*([0-9.]+)', cleaned)
            if m_tot:
                res["total"] = float(m_tot.group(1))
            if res:
                return res
    # Try finding any top-level JSON object with merchant / total
    m2 = re.search(r"(\{[^{}]*\"merchant\"[^{}]*\})", text, re.DOTALL)
    if m2:
        try:
            return json.loads(m2.group(1))
        except Exception:
            pass
    return None


def parse_tool_call(text):
    # Match <|tool_call>call:NAME{...}<tool_call|>
    m = re.search(r"<\|tool_call>call:([a-zA-Z0-9_]+)\{(.*?)\}(?:<tool_call\|>)?", text, re.DOTALL)
    if not m:
        # Fallback to loose call
        m = re.search(r"call:([a-zA-Z0-9_]+)\{(.*?)\}", text, re.DOTALL)
    if not m:
        return None, {}
    name = m.group(1)
    raw_args = m.group(2).replace('<|\\"|>', '"').replace('<|"|>', '"')
    # Try parsing pseudo-JSON or regex extraction
    args = {}
    for kv in re.finditer(r'([a-zA-Z0-9_]+)\s*:\s*([^,}]+)', raw_args):
        k = kv.group(1).strip()
        v = kv.group(2).strip().strip('"').strip("'")
        args[k] = v
    return name, args


def evaluate_output(c, text):
    cat = c["category"]
    low = text.lower()

    if cat == "receipt":
        exp = c["expected_expense"]
        parsed = parse_expense_block(text)
        if not parsed:
            return False, "no valid expense code block found in output", None
        
        # Check merchant
        merch_got = str(parsed.get("merchant", "")).lower()
        merch_want = exp["merchant"].lower()
        if merch_want not in merch_got and merch_got not in merch_want:
            return False, f"merchant mismatch: wanted {exp['merchant']!r}, got {parsed.get('merchant')!r}", parsed
        
        # Check total
        total_got = None
        try:
            total_got = float(parsed.get("total", 0.0))
        except (ValueError, TypeError):
            pass
        if total_got is None or abs(total_got - exp["total"]) > 0.05:
            return False, f"total mismatch: wanted {exp['total']}, got {parsed.get('total')}", parsed
        
        return True, "", parsed

    if cat in ("document", "screenshot", "photo", "honesty"):
        # Check must_contain
        must = c.get("must_contain", [])
        for group in must:
            found = False
            for cand in group:
                if cand.lower() in low:
                    found = True
                    break
            if not found:
                return False, f"missing expected content from group: {group}", None
        
        # Check must_not_contain
        not_must = c.get("must_not_contain", [])
        for forbidden in not_must:
            if forbidden.lower() in low:
                return False, f"output contains forbidden text: {forbidden!r}", None
        
        return True, "", None

    if cat == "image_tool":
        tool_name, args = parse_tool_call(text)
        want_tool = c["expected_tool"]
        allowed_tools = [want_tool] if isinstance(want_tool, str) else want_tool
        if tool_name not in allowed_tools:
            return False, f"expected tool in {allowed_tools!r}, got {tool_name!r}", args
        
        # Check tool args
        for arg_k, arg_v in c.get("tool_args", {}).items():
            got_v = str(args.get(arg_k, ""))
            if isinstance(arg_v, list):
                if not any(cand.lower() in got_v.lower() or cand.lower() in low for cand in arg_v):
                    return False, f"argument {arg_k} did not match any of {arg_v}; got {got_v!r}", args
            elif isinstance(arg_v, (int, float)):
                try:
                    if int(got_v) != int(arg_v):
                        return False, f"argument {arg_k} expected {arg_v}, got {got_v!r}", args
                except ValueError:
                    return False, f"argument {arg_k} expected numeric {arg_v}, got {got_v!r}", args
            else:
                if str(arg_v).lower() not in got_v.lower():
                    return False, f"argument {arg_k} expected {arg_v!r}, got {got_v!r}", args
        return True, "", args

    return False, "unknown category", None


def run_case(c, base_url, sys_prompt):
    img_path = DATA_DIR / c["image"]
    with open(img_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("utf-8")

    payload = {
        "messages": [
            {"role": "system", "content": sys_prompt},
            {"role": "user", "content": [
                {"type": "text", "text": c["prompt"]},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}
            ]}
        ],
        "chat_template_kwargs": {"enable_thinking": False},
        "temperature": 0.0,
        "max_tokens": 512,
    }

    t0 = time.time()
    req = urllib.request.Request(
        f"{base_url}/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            res = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        return {
            "id": c["id"],
            "category": c["category"],
            "ok": False,
            "why": f"request error: {e}",
            "text": "",
            "elapsed_s": round(time.time() - t0, 2)
        }

    elapsed = round(time.time() - t0, 2)
    msg = res["choices"][0]["message"]
    text = msg.get("content", "").strip()
    usage = res.get("usage", {})
    timings = res.get("timings", {})

    ok, why, extracted = evaluate_output(c, text)
    return {
        "id": c["id"],
        "category": c["category"],
        "ok": ok,
        "why": why,
        "prompt": c["prompt"],
        "text": text,
        "extracted": extracted,
        "prompt_tokens": usage.get("prompt_tokens", 0),
        "completion_tokens": usage.get("completion_tokens", 0),
        "prompt_ms": timings.get("prompt_ms", 0),
        "predicted_per_second": round(timings.get("predicted_per_second", 0), 1),
        "elapsed_s": elapsed,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8092")
    p.add_argument("--tag", default="base")
    p.add_argument("--limit", type=int, default=0)
    args = p.parse_args()

    out_dir = ROOT / "eval/results"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"vision_{args.tag}.jsonl"

    sys_p = system_prompt()
    cases = CASES[:args.limit] if args.limit > 0 else CASES

    print(f"Running vision eval on {len(cases)} cases against {args.url} (tag={args.tag})...")
    results = []
    t_start = time.time()

    for idx, c in enumerate(cases, 1):
        res = run_case(c, args.url, sys_p)
        results.append(res)
        status = "OK " if res["ok"] else "FAIL"
        print(f"[{idx:02d}/{len(cases):02d}] {status} {c['id']} ({c['category']}): {c['prompt'][:40]!r}" +
              (f" -> {res['why']}" if not res["ok"] else f" ({res['elapsed_s']}s)"))

    with open(out_file, "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    total = len(results)
    ok_count = sum(1 for r in results if r["ok"])
    total_acc = 100.0 * ok_count / total if total else 0.0

    by_cat = {}
    for r in results:
        by_cat.setdefault(r["category"], []).append(r["ok"])

    print("\n" + "=" * 60)
    print(f"VISION EVAL RESULTS: {ok_count}/{total} = {total_acc:.1f}% ({time.time() - t_start:.1f} s)")
    print("=" * 60)
    for cat, vals in sorted(by_cat.items()):
        cat_ok = sum(vals)
        cat_total = len(vals)
        print(f"  {cat:15s} {cat_ok:2d}/{cat_total:2d} = {100.0 * cat_ok / cat_total:.1f}%")
    print("=" * 60)
    print(f"Saved results to {out_file}")

    if total_acc < 85.0:
        print(f"WARNING: Overall accuracy {total_acc:.1f}% is below target 85.0%")


if __name__ == "__main__":
    main()
