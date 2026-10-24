"""Re-derive the reference answers for the real-PyPI version corpus with the pinned packaging.

The corpus (version strings published on PyPI, plus ordering pairs drawn from them)
is produced by tools/pypi-corpus/collect.py. This script recomputes every answer
with the packaging version installed, so the corpus test uses the same oracle as
everything else.

Usage: python3 -I tools/pypi-corpus/regenerate.py <corpus.json> src/test/resources/pypi-corpus.json.gz
"""

import gzip
import json
import sys

import packaging
from packaging.version import InvalidVersion, Version


def main() -> None:
    src, out = sys.argv[1], sys.argv[2]
    corpus = json.load(open(src, encoding="utf-8"))
    rows = []
    for row in corpus["rows"]:
        try:
            v = Version(row["v"])
            rows.append({"v": row["v"], "ok": True, "norm": str(v), "pre": v.is_prerelease})
        except InvalidVersion:
            rows.append({"v": row["v"], "ok": False})
    pairs = []
    for a, b, _ in corpus["pairs"]:
        va, vb = Version(a), Version(b)
        pairs.append([a, b, (va > vb) - (va < vb)])
    changed = sum(1 for old, new in zip(corpus["pairs"], pairs) if old[2] != new[2])
    doc = {
        "source": "Public PyPI version strings, collected by tools/pypi-corpus/collect.py",
        "packaging_version": packaging.__version__,
        "rows": rows,
        "pairs": pairs,
    }
    with gzip.open(out, "wt", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(rows)} versions, {len(pairs)} pairs, {changed} pair answers differ from the input corpus -> {out}")


if __name__ == "__main__":
    main()
