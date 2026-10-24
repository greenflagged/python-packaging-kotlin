"""Collect the real-PyPI version corpus used by PypiCorpusTest.

Fetches every release version string of about 50 widely used PyPI projects from
PyPI's JSON API, adds hand-picked edge cases, and draws random ordering pairs.
Answers come from the installed packaging, which tools/pypi-corpus/regenerate.py
then recomputes with the pinned version.

Usage: python3 -I tools/pypi-corpus/collect.py corpus.json
       python3 -I tools/pypi-corpus/regenerate.py corpus.json src/test/resources/pypi-corpus.json.gz
"""

import json
import random
import sys
import urllib.request

from packaging.version import InvalidVersion, Version

PROJECTS = (
    "setuptools pip numpy pytz torch tensorflow django requests six urllib3 boto3 botocore pandas scipy "
    "cryptography pyyaml protobuf grpcio openai anthropic langchain langchain-core pydantic fastapi pytest "
    "tox black mypy ruff certifi idna charset-normalizer click jinja2 markupsafe attrs packaging wheel "
    "transformers huggingface-hub tokenizers sqlalchemy psycopg2 pillow matplotlib lxml docutils sphinx "
    "twisted zope.interface pywin32 tzdata"
).split()

EDGE = [
    "1.0", "1.0.0", "1!0.1", "1.0a1", "1.0alpha1", "1.0.a.1", "1.0b2", "1.0c1", "1.0rc1", "1.0pre1",
    "1.0preview1", "1.0.post1", "1.0-1", "1.0r1", "1.0rev1", "1.0.dev1", "1.0dev", "1.0a1.dev1",
    "1.0a1.post1", "1.0a1.post1.dev1", "1.0.post1.dev1", "1.0+abc", "1.0+abc.5", "1.0+5", "1.0+abc.def",
    "1.0+ABC-1_2", "V1.0", " 1.0 ", "1.0.0.0.0", "2023.10.11", "20231011123456", "99999999999",
    "1.0+1.abc", "1.0+abc.1", "1.0.post", "1.0-post", "1.0_post1", "1.0a", "1.0.dev", "1.0-dev2",
    "0.0.0", "1.01", "1.1", "1.10", "1.9", "1.0+local.7", "1.0+local.10", "1.0+0", "1.0+00",
    "1.0.0+1", "1!1.0", "0!1.0", "1.0rc", "1.0c",
]


def main() -> None:
    out = sys.argv[1]
    versions = set(EDGE)
    for project in PROJECTS:
        try:
            with urllib.request.urlopen(f"https://pypi.org/pypi/{project}/json", timeout=60) as resp:
                versions.update(json.load(resp)["releases"].keys())
        except Exception as exc:  # noqa: BLE001 - a missing project only shrinks the corpus
            print("skip", project, exc, file=sys.stderr)
    rows = []
    for v in sorted(versions):
        try:
            parsed = Version(v)
            rows.append({"v": v, "ok": True, "norm": str(parsed), "pre": parsed.is_prerelease})
        except InvalidVersion:
            rows.append({"v": v, "ok": False})
    valid = [r["v"] for r in rows if r["ok"]]
    rng = random.Random(474)
    pairs = [tuple(rng.sample(valid, 2)) for _ in range(200_000)]
    edge_valid = [e for e in EDGE if any(r["v"] == e and r["ok"] for r in rows)]
    pairs += [(a, b) for a in edge_valid for b in edge_valid]
    answers = [[a, b, (Version(a) > Version(b)) - (Version(a) < Version(b))] for a, b in pairs]
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"rows": rows, "pairs": answers}, fh)
    print(len(rows), "versions,", len(valid), "valid;", len(answers), "pairs")


if __name__ == "__main__":
    main()
