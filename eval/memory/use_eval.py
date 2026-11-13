"""Does Ronie USE what he remembers? (Phase 6)

The phone test showed recall finds the right fact ("Sister is vegetarian") but Ronie ignores it (suggested
butter chicken). This compares where the recalled facts go in the prompt:
  tail = end of the system prompt (what the app did first)
  note = a short note attached to the user's message (closer to the question)
with and without the Sweary steering (+1.0, layers 10-30), using the app's exact prompt (persona + tool
rules + tools from eval/tools_v2/run.py).

  llama-server -m gemma-4-E2B-it-Q4_K_M.gguf --special --port 8091 [--control-vector-scaled steering.gguf:1.0 --control-vector-layer-range 10 30]
  tools-pc\\.venv\\Scripts\\python.exe eval/memory/use_eval.py --tag tone0
"""
import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval" / "tools_v2"))
import run as tools_run          # noqa: E402  (system_block, persona, tool guide)
import gemma_tools as G          # noqa: E402

FACTS = [
    "Sister Priya is vegetarian", "Allergic to peanuts", "Dog is a beagle called Bruno",
    "Plays football every Sunday morning", "Manager is called Meera", "Saving up for a trip to Japan",
    "Best friend Kabir is getting married in December", "Takes chai with cardamom and no sugar",
    "Lactose intolerant", "Rent is due on the 5th of every month",
]

# (message, facts recall would return, must contain any of, must not contain any of)
CASES = [
    ("What should I cook when my sister comes over for dinner?", [0], ["veg", "paneer", "dal", "tofu", "chickpea", "mushroom", "chole", "rajma"],
     ["chicken", "mutton", "lamb", "fish", "prawn", "beef", "pork", "egg"]),
    ("Any gift ideas for Kabir?", [6], ["wedding", "married", "marriage", "couple", "shaadi"], []),
    ("Can I have a milkshake?", [8], ["lactose", "dairy", "oat", "almond", "soy"], []),
    # Snacks / desserts pass if nothing unsafe is suggested (or the constraint is named).
    ("Suggest a snack for my evening", [1, 8], [], ["peanut", "peanuts", "milk", "cheese", "yogurt", "yoghurt", "paneer", "ice cream", "milkshake"]),
    ("What should I do this Sunday morning?", [3], ["football"], []),
    ("I need to email my boss about leave", [4], ["meera"], []),
    ("How much should I put aside each month?", [5, 9], ["japan", "rent", "trip"], []),
    ("Make me a cup of chai, how should I make it?", [7], ["cardamom", "elaichi"], ["sugar to taste", "add sugar", "teaspoon of sugar", "spoon of sugar"]),
    ("Plan a fun afternoon with my dog", [2], ["bruno", "beagle"], []),
    ("Pick a dessert for me at the restaurant", [8, 1], [], ["ice cream", "kulfi", "cheesecake", "milkshake", "cream", "peanut", "rasmalai", "kheer"]),
]


def facts_block(ids):
    return "What you know about the user:\n" + "\n".join(f"- {FACTS[i]}" for i in ids)


def note(ids):
    return "(Things I've told you before that may matter here:\n" + "\n".join(f"- {FACTS[i]}" for i in ids) + ")\n\n"


def note2(ids):
    return "(Keep in mind for this answer, from what I've told you before:\n" + "\n".join(f"- {FACTS[i]}" for i in ids) + ")\n\n"


def prompt(case, variant):
    msg, ids = case[0], case[1]
    sys_block = tools_run.system_block("tail")          # persona + rules + tools + dates
    if variant == "tail":
        sys_block = sys_block.replace("<turn|>\n", "\n" + facts_block(ids) + "<turn|>\n", 1)
        user = msg
    elif variant == "note2":
        user = note2(ids) + msg
    else:
        user = note(ids) + msg
    return sys_block + f"<|turn>user\n{user}<turn|>\n<|turn>model\n"


def complete(url, p, temp, seed):
    body = {"prompt": p, "n_predict": 260, "temperature": temp, "top_k": 64, "top_p": 0.95, "min_p": 0.0, "seed": seed, "cache_prompt": True}
    req = urllib.request.Request(url + "/completion", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read())["content"]


CONSTRAINT = re.compile(r"lactose|dairy[- ]free|allerg|vegetarian|no peanut|peanut[- ]free", re.I)
NEG = re.compile(r"(no|not|don't|dont|without|never|avoid|skip|instead of|swap)\W+(\w+\W+){0,3}$", re.I)


def judge(reply, must, banned):
    """Passes if it uses the memory: names an expected thing (when given) and suggests nothing forbidden.
    Forbidden words count only as whole words and not when negated ("don't add sugar", "no peanuts")."""
    if must and not any(re.search(r"\b" + re.escape(w), reply) for w in must):
        return False
    for w in banned:
        for m in re.finditer(r"\b" + re.escape(w) + r"\b", reply):
            if not NEG.search(reply[max(0, m.start() - 40):m.start()]):
                if not must and CONSTRAINT.search(reply):
                    break            # e.g. "lactose-free ice cream": the constraint was respected
                return False
    return True


HOSTILE = re.compile(r"\b(you (lazy |stupid |fucking )?(fuck|bastard|idiot|shit|asshole|dumbass)|get out of here|piss off)\b", re.I)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8091")
    ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--tag", default="")
    ap.add_argument("--rescore", default="", help="re-judge a saved results file instead of generating")
    ap.add_argument("--variants", default="tail,note,note2")
    a = ap.parse_args()
    if a.rescore:
        rows = [json.loads(l) for l in open(a.rescore, encoding="utf-8")]
        byq = {c[0]: c for c in CASES}
        for v in ("tail", "note"):
            rs = [r for r in rows if r["variant"] == v]
            ok = sum(judge(r["reply"], byq[r["q"]][2], byq[r["q"]][3]) for r in rs)
            print(f"{v:5s} uses memory {ok}/{len(rs)} = {100 * ok / len(rs):.0f}%   hostile {sum(r['hostile'] for r in rs)}/{len(rs)}")
        return
    out = []
    for variant in a.variants.split(","):
        ok = hostile = 0
        n = 0
        for seed in range(a.seeds):
            for c in CASES:
                reply = G.visible_text(complete(a.url, prompt(c, variant), 1.0, seed)).lower()
                good = judge(reply, c[2], c[3])
                bad_tone = bool(HOSTILE.search(reply))
                ok += good; hostile += bad_tone; n += 1
                out.append({"variant": variant, "seed": seed, "q": c[0], "ok": good, "hostile": bad_tone, "reply": reply})
        print(f"{a.tag:8s} {variant:5s} uses memory {ok}/{n} = {100 * ok / n:.0f}%   hostile {hostile}/{n}", flush=True)
    (ROOT / "eval" / "results" / f"memory_use_{a.tag or 'run'}.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in out), encoding="utf-8")


if __name__ == "__main__":
    main()
