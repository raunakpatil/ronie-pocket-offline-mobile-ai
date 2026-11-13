"""Prints the X-ray receipt and the own-answer labels for a hand check.
Usage: python eval/xray/summary.py
"""
import collections, json, pathlib

D = pathlib.Path(__file__).parent
r = json.loads((D / "receipt.json").read_text(encoding="utf-8"))
print({k: v for k, v in r.items() if not isinstance(v, list)})
print("own AUC by layer:", r.get("own_auc_by_layer"))
rows = [json.loads(l) for l in (D / "data_own.jsonl").open(encoding="utf-8")]
print(collections.Counter((x["kind"], x["label"]) for x in rows))
for label in (0, 1):
    print(f"--- label {label} ({'invented' if label == 0 else 'grounded'}) samples, unanswerable questions:")
    for x in [x for x in rows if x["label"] == label and x["kind"] == "unanswerable"][:20]:
        q = x.get("question") or x["prefix"].split("<|turn>user\n", 1)[-1].split("<turn|>", 1)[0][-55:]
        print("  ", q.replace("\n", " "), "->", x["span"][:100].replace("\n", " "))
