""".rpatch v0: a portable, verifiable "brain patch" for a GGUF model.

A .rpatch is a zip with:
  manifest.json  {format, version, name, title, description, kind, model: {file, sha256},
                  layers: [start, end], strength: {default, min, max}, direction: "direction.gguf"}
  direction.gguf llama.cpp control-vector GGUF ("direction.<layer>" F32 tensors)
  receipt.json   the eval that justifies it: before/after numbers, method, data, date

kind "steer" = added to the residual stream at the given layers, scaled by the user's strength.
(Later versions add "probe" and baked "weights" patches.)

Usage:
  python tools-pc/rpatch/rpatch.py build  --name swear --title "Clean ↔ Sweary" --direction eval/steering/swear-gemma4e2b.gguf
         --model models-local/gemma-4-E2B-it-Q4_K_M.gguf --layers 10-30 --strength 0,-1,1
         --receipt eval/results/steer_swear-gemma4e2b_L10-30.json --out patches/swear-gemma4e2b.rpatch
  python tools-pc/rpatch/rpatch.py inspect patches/swear-gemma4e2b.rpatch
  python tools-pc/rpatch/rpatch.py verify  patches/swear-gemma4e2b.rpatch models-local/gemma-4-E2B-it-Q4_K_M.gguf
"""
import argparse, datetime, hashlib, json, pathlib, zipfile

FORMAT = "rpatch"
VERSION = 0


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build(a):
    lo, hi = (int(x) for x in a.layers.split("-"))
    d, mn, mx = (float(x) for x in a.strength.split(","))
    receipt = json.loads(pathlib.Path(a.receipt).read_text(encoding="utf-8"))
    receipt.pop("rows", None)   # keep the summary; raw generations stay in the repo
    receipt.setdefault("created", datetime.date.today().isoformat())
    model = pathlib.Path(a.model)
    manifest = {
        "format": FORMAT, "version": VERSION, "name": a.name, "title": a.title,
        "description": a.description, "kind": "steer",
        "model": {"file": model.name, "sha256": a.model_sha256 or sha256(model)},
        "layers": [lo, hi], "strength": {"default": d, "min": mn, "max": mx},
        "direction": "direction.gguf",
    }
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=1, ensure_ascii=False))
        z.write(a.direction, "direction.gguf")
        z.writestr("receipt.json", json.dumps(receipt, indent=1, ensure_ascii=False))
    print("wrote", out, f"({out.stat().st_size // 1024} KB)")


def inspect(a):
    with zipfile.ZipFile(a.patch) as z:
        m = json.loads(z.read("manifest.json"))
        r = json.loads(z.read("receipt.json"))
    print(json.dumps(m, indent=1, ensure_ascii=False))
    print("receipt:", json.dumps(r.get("summary", r), indent=1, ensure_ascii=False)[:2000])


def verify(a):
    with zipfile.ZipFile(a.patch) as z:
        m = json.loads(z.read("manifest.json"))
        assert m["format"] == FORMAT and m["version"] <= VERSION, "unsupported patch format"
        assert "direction.gguf" in z.namelist() and "receipt.json" in z.namelist(), "incomplete patch"
    got = sha256(pathlib.Path(a.model))
    ok = got == m["model"]["sha256"]
    print("OK: patch matches this model" if ok else f"MISMATCH: patch is for {m['model']['sha256'][:12]}…, model is {got[:12]}…")
    raise SystemExit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    for k in ("name", "title", "direction", "model", "layers", "strength", "receipt", "out"):
        b.add_argument(f"--{k}", required=True)
    b.add_argument("--description", default="")
    b.add_argument("--model-sha256", default="", help="skip hashing a large model file")
    sub.add_parser("inspect").add_argument("patch")
    v = sub.add_parser("verify"); v.add_argument("patch"); v.add_argument("model")
    a = ap.parse_args()
    {"build": build, "inspect": inspect, "verify": verify}[a.cmd](a)


if __name__ == "__main__":
    main()
