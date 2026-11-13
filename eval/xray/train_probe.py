"""X-ray v0: trains a linear "grounded vs invented" probe on Gemma activations and reports it honestly.

Inputs: <dir>/{train,test}.bin from probe_dump (float32[n][n_layer][n_embd]) + data_*.jsonl labels.
Picks the best layer on a validation split of train (split by note), then reports held-out test AUC
overall, on fact questions only, and on unanswerable questions, plus a shuffled-label control.
Writes <dir>/probe-gemma4e2b.json (layer, weights with scaling folded in, bias, receipt).

Usage: python eval/xray/train_probe.py [dir]
"""
import json, pathlib, sys
import numpy as np

rng = np.random.default_rng(7)


def load(d, split):
    meta = json.loads((d / f"{split}.bin.json").read_text())
    X = np.fromfile(d / f"{split}.bin", dtype=np.float32).reshape(meta["count"], meta["n_layer"], meta["n_embd"])
    rows = [json.loads(l) for l in (d / f"data_{split}.jsonl").open(encoding="utf-8")]
    assert len(rows) == len(X), (len(rows), len(X))
    return X, rows


def auc(scores, y):
    order = np.argsort(scores)
    ranks = np.empty(len(scores)); ranks[order] = np.arange(1, len(scores) + 1)
    pos = y == 1
    n1, n0 = pos.sum(), (~pos).sum()
    return float((ranks[pos].sum() - n1 * (n1 + 1) / 2) / (n1 * n0)) if n1 and n0 else float("nan")


def fit(X, y, l2=1e-2, steps=400, lr=0.1):
    mu, sd = X.mean(0), X.std(0) + 1e-6
    Z = (X - mu) / sd
    w, b = np.zeros(Z.shape[1]), 0.0
    for _ in range(steps):
        p = 1 / (1 + np.exp(-np.clip(Z @ w + b, -30, 30)))
        g = p - y
        w -= lr * (Z.T @ g / len(y) + l2 * w)
        b -= lr * g.mean()
    return w / sd, b - float((mu / sd) @ w)   # scaling folded in: score = x @ w + b


def main(d: pathlib.Path):
    Xtr, rtr = load(d, "train")
    Xte, rte = load(d, "test")
    ytr = np.array([r["label"] for r in rtr], float)
    yte = np.array([r["label"] for r in rte], float)
    notes = sorted({r["note_id"] for r in rtr})
    val_notes = set(rng.choice(notes, len(notes) // 5, replace=False).tolist())
    is_val = np.array([r["note_id"] in val_notes for r in rtr])

    by_layer = []
    for L in range(Xtr.shape[1]):
        w, b = fit(Xtr[~is_val, L], ytr[~is_val])
        by_layer.append(auc(Xtr[is_val, L] @ w + b, ytr[is_val]))
        print(f"layer {L:2d}  val AUC {by_layer[-1]:.3f}", flush=True)
    best = int(np.nanargmax(by_layer))

    w, b = fit(Xtr[:, best], ytr)
    s = Xte[:, best] @ w + b
    kinds = np.array([r["kind"] for r in rte])
    report = {
        "layer": best,
        "val_auc": round(by_layer[best], 3),
        "test_auc": round(auc(s, yte), 3),
        "test_auc_facts": round(auc(s[kinds == "fact"], yte[kinds == "fact"]), 3),
        "test_auc_unanswerable": round(auc(s[kinds == "unanswerable"], yte[kinds == "unanswerable"]), 3),
        "test_accuracy": round(float(((s > 0) == (yte == 1)).mean()), 3),
        "n_train": len(ytr), "n_test": len(yte),
    }
    ws, bs = fit(Xtr[:, best], rng.permutation(ytr))   # control: shuffled labels
    report["control_shuffled_auc"] = round(auc(Xte[:, best] @ ws + bs, yte), 3)
    wr = rng.standard_normal(Xtr.shape[2])             # control: a random direction
    report["control_random_direction_auc"] = round(auc(Xte[:, best] @ wr, yte), 3)

    # The honest test: Gemma's own answers (data_own.jsonl), labelled grounded/invented automatically.
    # Layer chosen on half of the notes, AUC reported on the other half.
    if (d / "own.bin.json").exists():
        Xo, ro = load(d, "own")
        yo = np.array([r["label"] for r in ro], float)
        half = np.array([r["note_id"] % 2 == 0 for r in ro])
        per_layer = []
        for L in range(Xtr.shape[1]):
            wL, bL = fit(Xtr[:, L], ytr)
            per_layer.append((wL, bL, auc(Xo[half, L] @ wL + bL, yo[half])))
        best = int(np.nanargmax([p[2] for p in per_layer]))
        w, b = per_layer[best][0], per_layer[best][1]
        so = Xo[:, best] @ w + b
        ko = np.array([r["kind"] for r in ro])
        report.update({
            "layer": best,
            "own_n": len(yo), "own_invented": int((yo == 0).sum()),
            "own_auc_selection_half": round(per_layer[best][2], 3),
            "own_auc_report_half": round(auc(so[~half], yo[~half]), 3),
            "own_auc_all": round(auc(so, yo), 3),
            "own_auc_facts": round(auc(so[ko == "fact"], yo[ko == "fact"]), 3),
            "own_auc_unanswerable": round(auc(so[ko == "unanswerable"], yo[ko == "unanswerable"]), 3),
            "own_auc_by_layer": [round(p[2], 3) for p in per_layer],
            "synthetic_test_auc_at_layer": round(auc(Xte[:, best] @ w + b, yte), 3),
        })
    print(json.dumps(report, indent=1))

    probe = {"model": "gemma-4-E2B-it-Q4_K_M", "kind": "grounded-vs-invented", "version": 0,
             "layer": best, "bias": float(b), "weights": [float(x) for x in w], "receipt": report}
    # NaN (e.g. an AUC with no negatives) isn't valid JSON for Android's parser: write null instead.
    clean = lambda o: None if isinstance(o, float) and o != o else (
        {k: clean(v) for k, v in o.items()} if isinstance(o, dict) else [clean(v) for v in o] if isinstance(o, list) else o)
    probe, report = clean(probe), clean(report)
    by_layer = clean(by_layer)
    (d / "probe-gemma4e2b.json").write_text(json.dumps(probe), encoding="utf-8")
    (d / "receipt.json").write_text(json.dumps({"layers_val_auc": [round(x, 3) for x in by_layer], **report},
                                               indent=1), encoding="utf-8")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).parent)
