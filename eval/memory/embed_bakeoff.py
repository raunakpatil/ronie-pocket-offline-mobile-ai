"""Embedding-model bake-off for Ronie's memory (Phase 6), run the way the phone would run it: int8/quantized
ONNX in ONNX Runtime, single thread, with each model's own query/document prefixes and pooling.

  tools-pc\\.venv\\Scripts\\python.exe eval/memory/embed_bakeoff.py

Reports recall@1, recall@3 and MRR on eval/memory/retrieval_set.py, ms per sentence (PC, 1 thread) and
download size. Models are cached by huggingface_hub (~/.cache/huggingface).
"""
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from huggingface_hub import hf_hub_download, list_repo_files
from tokenizers import Tokenizer

sys.path.insert(0, str(Path(__file__).parent))
from retrieval_set import FACTS, QUERIES, QUERIES_MIXED

BGE_Q = "Represent this sentence for searching relevant passages: "
CANDIDATES = [
    # name, repo, pooling, query prefix, doc prefix, licence
    ("all-MiniLM-L6-v2", "Xenova/all-MiniLM-L6-v2", "mean", "", "", "Apache-2.0"),
    ("bge-small-en-v1.5", "Xenova/bge-small-en-v1.5", "cls", BGE_Q, "", "MIT"),
    ("arctic-embed-xs", "Snowflake/snowflake-arctic-embed-xs", "cls", BGE_Q, "", "Apache-2.0"),
    ("EmbeddingGemma-300m", "onnx-community/embeddinggemma-300m-ONNX", "model",
     "task: search result | query: ", "title: none | text: ", "Gemma terms"),
]


def pick_onnx(repo):
    files = list_repo_files(repo)
    for want in ("onnx/model_quantized.onnx", "onnx/model_int8.onnx", "onnx/model_q4.onnx", "onnx/model.onnx"):
        if want in files:
            extra = [f for f in files if f.startswith(want) and f != want]   # external data files
            return want, extra
    raise SystemExit(f"no onnx in {repo}")


def load(repo):
    onnx, extra = pick_onnx(repo)
    path = hf_hub_download(repo, onnx)
    for e in extra:
        hf_hub_download(repo, e)
    tok = Tokenizer.from_file(hf_hub_download(repo, "tokenizer.json"))
    size = os.path.getsize(path) + sum(os.path.getsize(hf_hub_download(repo, e)) for e in extra)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    return sess, tok, size, onnx


def embed(sess, tok, texts, pooling):
    names = [i.name for i in sess.get_inputs()]
    outs = []
    for t in texts:
        enc = tok.encode(t)
        ids = np.array([enc.ids[:512]], dtype=np.int64)
        mask = np.ones_like(ids)
        feed = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in names:
            feed["token_type_ids"] = np.zeros_like(ids)
        res = sess.run(None, feed)
        out_names = [o.name for o in sess.get_outputs()]
        if pooling == "model" and "sentence_embedding" in out_names:
            v = res[out_names.index("sentence_embedding")][0]
        else:
            h = res[0][0]                                   # last_hidden_state (tokens × dim)
            v = h[0] if pooling == "cls" else h.mean(axis=0)
        outs.append(v / (np.linalg.norm(v) + 1e-9))
    return np.stack(outs)


def main():
    rows = []
    for name, repo, pooling, qp, dp, lic in CANDIDATES:
        try:
            sess, tok, size, onnx = load(repo)
        except Exception as e:
            print(f"{name}: couldn't load ({e})")
            continue
        t0 = time.time()
        F = embed(sess, tok, [dp + f for f in FACTS], pooling)
        Q = embed(sess, tok, [qp + q for q, _ in QUERIES], pooling)
        ms = (time.time() - t0) * 1000 / (len(FACTS) + len(QUERIES))
        sims = Q @ F.T
        r1 = r3 = mrr = 0.0
        for qi, (_, gold) in enumerate(QUERIES):
            order = list(np.argsort(-sims[qi]))
            rank = min(order.index(g) for g in gold) + 1
            r1 += rank == 1
            r3 += rank <= 3
            mrr += 1 / rank
        n = len(QUERIES)
        # Hinglish messages: top-3 hit rate.
        QM = embed(sess, tok, [qp + q for q, _ in QUERIES_MIXED], pooling)
        sm = QM @ F.T
        mixed = sum(min(list(np.argsort(-sm[i])).index(g) for g in gold) < 3 for i, (_, gold) in enumerate(QUERIES_MIXED)) / len(QUERIES_MIXED)
        rows.append((name, r1 / n, r3 / n, mrr / n, ms, size / 1e6, F.shape[1], lic, onnx, mixed))
        print(f"{name:22s} R@1 {r1 / n:5.1%}  R@3 {r3 / n:5.1%}  MRR {mrr / n:.3f}  {ms:6.1f} ms/sent  "
              f"{size / 1e6:6.1f} MB  dim {F.shape[1]}  Hinglish R@3 {mixed:5.1%}", flush=True)
    out = Path(__file__).resolve().parents[1] / "results" / "memory_embed_bakeoff.json"
    out.write_text(json.dumps([dict(zip(["model", "r1", "r3", "mrr", "ms", "mb", "dim", "licence", "file", "hinglish_r3"], r)) for r in rows], indent=1))
    print("saved", out)


if __name__ == "__main__":
    main()
