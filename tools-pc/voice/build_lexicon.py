"""Converts misaki's US English dictionaries (Apache-2.0, hexgrad/misaki) to compact TSV for the app.

Each line: word<TAB>phonemes            (one pronunciation)
       or  word<TAB>TAG=ph|TAG=ph|...   (several, keyed by part-of-speech; DEFAULT always present;
                                         an empty value means "no pronunciation for this tag")
Usage: python tools-pc/voice/build_lexicon.py <misaki_package_dir> app/src/main/assets/voice
"""
import json, pathlib, sys


def convert(src: pathlib.Path, dst: pathlib.Path):
    d = json.loads(src.read_text(encoding="utf-8"))
    n = 0
    with dst.open("w", encoding="utf-8", newline="\n") as f:
        for word, v in sorted(d.items()):
            if "\t" in word or "\n" in word:
                continue
            if isinstance(v, str):
                f.write(f"{word}\t{v}\n")
            else:
                parts = "|".join(f"{tag}={'' if ph is None else ph}" for tag, ph in v.items())
                f.write(f"{word}\t{parts}\n")
            n += 1
    print(f"{dst.name}: {n} words, {dst.stat().st_size // 1024} KB")


if __name__ == "__main__":
    pkg, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for name in ("us_gold", "us_silver"):
        convert(pkg / "data" / f"{name}.json", out / f"{name}.tsv")
    lic = pkg.parent / next(p.name for p in pkg.parent.iterdir() if p.name.endswith(".dist-info")) / "licenses" / "LICENSE"
    (out / "MISAKI_NOTICE.txt").write_text(
        "Pronunciation dictionaries from misaki (https://github.com/hexgrad/misaki), Apache License 2.0.\n"
        "Converted to TSV by tools-pc/voice/build_lexicon.py.\n", encoding="utf-8")
