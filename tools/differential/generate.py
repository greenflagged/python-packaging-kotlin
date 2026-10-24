"""Generate differential test cases by running Python `packaging` on random inputs.

Each case records an input and everything Python's packaging does with it. The
Kotlin test `DifferentialTest` replays every case through this library and fails on
any difference. Inputs are generated from the PEP 440 / PEP 508 grammars and then
randomly mutated, so they mix valid, near-valid and invalid strings.

Usage:
    python3 -I tools/differential/generate.py --seed 1 --count 2000 --out build/differential/cases.json

`--count` is the number of cases per category. The same seed always produces the
same cases for a given packaging version.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import warnings

# ast.literal_eval warns about invalid escapes such as "\\q"; they are part of the test input.
warnings.filterwarnings("ignore", category=SyntaxWarning)

import packaging
from packaging.markers import InvalidMarker, Marker, UndefinedComparison, UndefinedEnvironmentName
from packaging.requirements import InvalidRequirement, Requirement
from packaging.specifiers import InvalidSpecifier, Specifier, SpecifierSet
from packaging.utils import (
    InvalidName,
    InvalidSdistFilename,
    InvalidWheelFilename,
    canonicalize_name,
    canonicalize_version,
    is_normalized_name,
    parse_sdist_filename,
    parse_wheel_filename,
)
from packaging.version import InvalidVersion, Version

EXPECTED_ERRORS = (
    InvalidVersion,
    InvalidSpecifier,
    InvalidMarker,
    InvalidRequirement,
    UndefinedComparison,
    UndefinedEnvironmentName,
    InvalidName,
    InvalidWheelFilename,
    InvalidSdistFilename,
)


class InternalError(Exception):
    """Python raised something other than packaging's documented exceptions."""


def call(fn, *args, **kwargs):
    """Returns {"ok": value} or {"error": ExceptionName}; raises InternalError otherwise."""
    try:
        return {"ok": fn(*args, **kwargs)}
    except EXPECTED_ERRORS as exc:
        return {"error": type(exc).__name__}
    except Exception as exc:  # noqa: BLE001 - recorded as an internal error
        raise InternalError(f"{type(exc).__name__}: {exc}") from exc


# ----------------------------------------------------------------------------- generators

UNICODE_WS = [" ", "\t", "\n", "\r", "\x0b", "\x0c", "\x1c", "\x85", "\xa0", " ", "　", " "]
TRICKY = ["İ", "ı", "ſ", "K", "é", "ß", "Σ", "٠", "１", "\U0001d7d9", "\U0001f600"]
PUNCT = list(".-_!+*=<>~;,()[]@'\" \t\\/:#")


class Gen:
    def __init__(self, seed: int) -> None:
        self.r = random.Random(seed)

    def chance(self, p: float) -> bool:
        return self.r.random() < p

    def pick(self, seq):
        return self.r.choice(seq)

    def case(self, s: str) -> str:
        roll = self.r.random()
        if roll < 0.6:
            return s
        if roll < 0.8:
            return s.upper()
        return "".join(c.upper() if self.chance(0.5) else c for c in s)

    def number(self) -> str:
        roll = self.r.random()
        if roll < 0.55:
            return str(self.r.randint(0, 9))
        if roll < 0.75:
            return str(self.r.randint(10, 99))
        if roll < 0.85:
            return "0" * self.r.randint(1, 3) + str(self.r.randint(0, 9))
        if roll < 0.95:
            return str(self.r.randint(100, 20251231))
        return str(self.r.randint(10**18, 10**30))

    def sep(self) -> str:
        return self.pick(["", "", "", ".", "-", "_"])

    def ws(self) -> str:
        roll = self.r.random()
        if roll < 0.7:
            return ""
        if roll < 0.9:
            return " " * self.r.randint(1, 2)
        return self.pick(UNICODE_WS)

    def release(self, min_parts: int = 1) -> str:
        n = self.r.randint(min_parts, max(min_parts, 4))
        return ".".join(self.number() for _ in range(n))

    def pre(self) -> str:
        letter = self.pick(["a", "a", "b", "rc", "rc", "c", "alpha", "beta", "pre", "preview"])
        num = self.number() if self.chance(0.8) else ""
        return self.sep() + self.case(letter) + (self.sep() + num if num else "")

    def post(self) -> str:
        if self.chance(0.25):
            return "-" + self.number()
        letter = self.pick(["post", "post", "rev", "r"])
        num = self.number() if self.chance(0.8) else ""
        return self.sep() + self.case(letter) + (self.sep() + num if num else "")

    def dev(self) -> str:
        num = self.number() if self.chance(0.8) else ""
        return self.sep() + self.case("dev") + (self.sep() + num if num else "")

    def local(self) -> str:
        parts = []
        for _ in range(self.r.randint(1, 3)):
            if self.chance(0.5):
                parts.append(self.number())
            else:
                parts.append(self.case("".join(self.pick("abcxyzubuntu") for _ in range(self.r.randint(1, 5)))))
        out = parts[0]
        for p in parts[1:]:
            out += self.pick(".-_") + p
        return "+" + out

    def version(self, allow_local: bool = True, min_parts: int = 1) -> str:
        s = ""
        if self.chance(0.1):
            s += self.pick(["v", "V"])
        if self.chance(0.1):
            s += self.number() + "!"
        s += self.release(min_parts)
        if self.chance(0.35):
            s += self.pre()
        if self.chance(0.25):
            s += self.post()
        if self.chance(0.25):
            s += self.dev()
        if allow_local and self.chance(0.15):
            s += self.local()
        return s

    def mutate(self, s: str, rate: float = 0.5) -> str:
        if not self.chance(rate):
            return s
        chars = list(s)
        for _ in range(self.r.randint(1, 3)):
            op = self.r.random()
            pos = self.r.randint(0, len(chars))
            if op < 0.35 and chars:
                del chars[min(pos, len(chars) - 1)]
            elif op < 0.7:
                chars.insert(pos, self.noise())
            elif chars:
                chars[min(pos, len(chars) - 1)] = self.noise()
        return "".join(chars)

    def noise(self) -> str:
        roll = self.r.random()
        if roll < 0.4:
            return self.pick(PUNCT)
        if roll < 0.6:
            return self.pick("0123456789")
        if roll < 0.8:
            return self.pick("abcdeprvostiAEPRV")
        if roll < 0.9:
            return self.pick(UNICODE_WS)
        return self.pick(TRICKY)

    # --- specifiers

    def specifier(self) -> str:
        roll = self.r.random()
        if roll < 0.08:
            token = self.pick(["foobar", "1.0", "1.0+local", "1.0.post1", "1.0A1", "Ⅷ", "1.0)", "a;b"])
            return "===" + self.ws() + token
        op = self.pick(["==", "==", "!=", ">=", "<=", ">", "<", "~=", "~=", "=", "=>", "<>"])
        if op in ("==", "!=") and self.chance(0.3):
            v = self.release() + ".*"
        else:
            v = self.version(allow_local=op in ("==", "!="), min_parts=2 if op == "~=" else 1)
        return self.ws() + op + self.ws() + v + self.ws()

    def specifier_set(self) -> str:
        n = self.pick([0, 1, 1, 2, 2, 3, 4])
        parts = [self.specifier() for _ in range(n)]
        if self.chance(0.1):
            parts.insert(self.r.randint(0, len(parts)), self.ws())
        return ",".join(parts)

    def candidates(self, spec_text: str) -> list[str]:
        """Versions near the specifier's versions, plus random and invalid ones."""
        bases = []
        for piece in spec_text.split(","):
            stripped = piece.strip().lstrip("=!<>~ \t").rstrip(".*").strip()
            try:
                bases.append(Version(stripped))
            except InvalidVersion:
                pass
        out = []
        for base in bases[:3]:
            rel = list(base.release)
            out.append(str(base))
            out.append(base.base_version)
            out.append(base.base_version + ".0")
            bumped = rel[:-1] + [rel[-1] + 1]
            out.append(".".join(map(str, bumped)))
            if rel[-1] > 0:
                out.append(".".join(map(str, rel[:-1] + [rel[-1] - 1])))
            out.append(".".join(map(str, rel[:-1] or [0])))
            for suffix in ("a1", "rc1", ".post1", ".dev0", ".post1.dev1", "+local", ".0+1", "b2.post3"):
                out.append(base.base_version + suffix)
            out.append(str(base) + "+abc")
            if base.epoch:
                out.append(".".join(map(str, rel)))
            else:
                out.append("1!" + base.base_version)
        for _ in range(4):
            out.append(self.version())
        out.append(self.mutate(self.version(), 1.0))
        self.r.shuffle(out)
        return out[:24]

    # --- markers

    VARIABLES = [
        "python_version", "python_full_version", "os_name", "os.name", "sys_platform", "sys.platform",
        "platform_release", "platform_system", "platform_version", "platform.version", "platform_machine",
        "platform.machine", "platform_python_implementation", "platform.python_implementation",
        "python_implementation", "implementation_name", "implementation_version", "extra", "extras",
        "dependency_groups",
    ]
    VALUES = {
        "python_version": ["2.7", "3.8", "3.12", "3.13", "3", "3.14.0rc1", "3.*", "4"],
        "python_full_version": ["3.12.4", "3.8.0", "3.13.0+", "3.14.0rc1", "3.12", "3.12.*"],
        "implementation_version": ["3.12.4", "7.3.13", "3.14.0rc1"],
        "platform_release": ["6.8.0", "5.4.0-generic", "10", "NT", "23.1.0"],
        "os_name": ["posix", "nt", "java"],
        "sys_platform": ["linux", "win32", "darwin", "cygwin", "linux2"],
        "platform_system": ["Linux", "Windows", "Darwin", "Java"],
        "platform_machine": ["x86_64", "AMD64", "arm64", "aarch64", "i386"],
        "platform_version": ["#1 SMP", "10.0.19041", ""],
        "platform_python_implementation": ["CPython", "PyPy", "Jython"],
        "implementation_name": ["cpython", "pypy"],
        "extra": ["test", "Test_Extra", "socks", "foo.bar", "", "DOCS"],
        "extras": ["test", "Socks", "foo_bar"],
        "dependency_groups": ["dev", "Lint", "docs_group"],
    }
    MARKER_OPS = ["==", "==", "==", "!=", ">=", ">=", "<=", ">", "<", "<", "~=", "===", "in", "in", "not in", "not in"]
    BAD_MARKER_OPS = ["not  in", "notin", "=", "is", "=>"]

    def quoted(self, value: str) -> str:
        roll = self.r.random()
        if roll < 0.07:
            value = value + self.pick(["\\x41", "\\n", "\\\\", "\\'", '\\"', "\\N{LATIN SMALL LETTER E WITH ACUTE}",
                                       "\\u00e9", "\\x4", "\\N{NOT A NAME}", "\\q", "\\101", "\\U0001F600", "\\N{DIGIT ONE}"])
        q = self.pick(["'", '"'])
        if value.count(q) and self.chance(0.5):
            q = "'" if q == '"' else '"'
        return q + value + q

    def marker_item(self) -> str:
        var = self.pick(self.VARIABLES)
        key = var.replace(".", "_")
        if key == "python_implementation":
            key = "platform_python_implementation"
        value = self.pick(self.VALUES.get(key, ["x"]))
        op = self.pick(self.BAD_MARKER_OPS if self.chance(0.03) else self.MARKER_OPS)
        lhs, rhs = self.case_var(var), self.quoted(value)
        if self.chance(0.3):
            lhs, rhs = rhs, lhs
        if self.chance(0.03):
            rhs = self.case_var(self.pick(self.VARIABLES))
        return lhs + self.ws_marker() + op + self.ws_marker() + rhs

    def case_var(self, var: str) -> str:
        return var.upper() if self.chance(0.03) else var

    def ws_marker(self) -> str:
        return self.pick(["", " ", " ", " ", "  ", "\t"])

    def marker(self, depth: int = 0) -> str:
        n = self.r.randint(1, 3 if depth < 2 else 1)
        out = []
        for i in range(n):
            if i:
                out.append(self.ws_marker() + (self.pick(["AND", "&&", "||"]) if self.chance(0.03) else self.pick(["and", "or"])) + self.ws_marker())
            if depth < 3 and self.chance(0.25):
                out.append("(" + self.ws_marker() + self.marker(depth + 1) + self.ws_marker() + ")")
            else:
                out.append(self.marker_item())
        return "".join(out)

    # --- names and requirements

    def name(self) -> str:
        alphabet = "abcXYZ019._-"
        if self.chance(0.1):
            alphabet += "".join(TRICKY[:6]) + " "
        return "".join(self.pick(alphabet) for _ in range(self.r.randint(1, 10)))

    def project(self) -> str:
        base = self.pick(["requests", "Django", "zope.interface", "my_pkg", "a", "foo-bar", "PyYAML", "x1"])
        return base if self.chance(0.85) else self.name()

    def requirement(self) -> str:
        s = self.ws_marker() + self.project()
        if self.chance(0.3):
            names = [self.pick(["socks", "Security", "test_extra", "a.b", "x"]) for _ in range(self.r.randint(0, 3))]
            s += self.ws_marker() + "[" + self.ws_marker() + ("," + self.ws_marker()).join(names) + self.ws_marker() + "]"
        roll = self.r.random()
        if roll < 0.15:
            url = self.pick(["https://example.com/pkg-1.0.whl", "file:///tmp/x.tar.gz", "git+https://github.com/a/b@v1#egg=b",
                             "https://example.com/a;b", "https://h/p?q=1&r=2"])
            s += self.ws_marker() + "@" + self.ws_marker() + url
            if self.chance(0.5):
                s += self.pick([" ", "\t", ""]) + ";" + self.ws_marker() + self.marker()
        else:
            if roll < 0.8:
                spec = ",".join(self.specifier().strip() for _ in range(self.r.randint(1, 3)))
                if self.chance(0.25):
                    spec = "(" + spec + ")"
                s += self.ws_marker() + spec
            if self.chance(0.35):
                s += self.ws_marker() + ";" + self.ws_marker() + self.marker()
        s += self.ws_marker() if self.chance(0.9) else self.pick(["\n", "\r\n", "#comment"])
        return s

    def wheel(self) -> str:
        name = self.project().replace("-", "_")
        build = "-" + self.pick(["1", "2abc", "x", "01"]) if self.chance(0.2) else ""
        tags = self.pick(["py3-none-any", "py2.py3-none-any", "cp312-cp312-manylinux_2_17_x86_64.manylinux2014_x86_64",
                          "py3.py2-none-any", "cp312-abi3-win_amd64", "1py-none-any", "py3--any", "py3-none"])
        return self.mutate(f"{name}-{self.version()}{build}-{tags}.whl", 0.3)

    def sdist(self) -> str:
        ext = self.pick([".tar.gz", ".zip", ".tar.bz2", ""])
        return self.mutate(f"{self.project()}-{self.version()}{ext}", 0.3)


# ----------------------------------------------------------------------------- recorders

ENVIRONMENTS = [
    {
        "implementation_name": "cpython", "implementation_version": "3.12.4", "os_name": "posix",
        "platform_machine": "x86_64", "platform_release": "6.8.0-45-generic", "platform_system": "Linux",
        "platform_version": "#45-Ubuntu SMP", "python_full_version": "3.12.4",
        "platform_python_implementation": "CPython", "python_version": "3.12", "sys_platform": "linux",
    },
    {
        "implementation_name": "cpython", "implementation_version": "3.8.10", "os_name": "nt",
        "platform_machine": "AMD64", "platform_release": "10", "platform_system": "Windows",
        "platform_version": "10.0.19041", "python_full_version": "3.8.10",
        "platform_python_implementation": "CPython", "python_version": "3.8", "sys_platform": "win32",
    },
    {
        "implementation_name": "pypy", "implementation_version": "7.3.13", "os_name": "posix",
        "platform_machine": "arm64", "platform_release": "23.1.0", "platform_system": "Darwin",
        "platform_version": "Darwin Kernel Version 23.1.0", "python_full_version": "3.10.13",
        "platform_python_implementation": "PyPy", "python_version": "3.10", "sys_platform": "darwin",
    },
    {
        "implementation_name": "cpython", "implementation_version": "3.14.0rc1", "os_name": "posix",
        "platform_machine": "aarch64", "platform_release": "NT", "platform_system": "Linux",
        "platform_version": "", "python_full_version": "3.13.0+",
        "platform_python_implementation": "CPython", "python_version": "3.13", "sys_platform": "linux",
    },
]
EXTRA_OVERRIDES = [{}, {"extra": "Test_Extra"}, {"extra": "socks"}, {"extras": {"Socks", "test"}, "dependency_groups": {"dev"}}]


def plain(value):
    if isinstance(value, (set, frozenset)):
        return sorted(value)
    return value


def record_version(s: str) -> dict:
    def describe(v: Version) -> dict:
        return {
            "str": str(v), "public": v.public, "base_version": v.base_version, "epoch": str(v.epoch),
            "release": [str(x) for x in v.release], "pre": None if v.pre is None else [v.pre[0], str(v.pre[1])],
            "post": None if v.post is None else str(v.post), "dev": None if v.dev is None else str(v.dev),
            "local": v.local, "is_prerelease": v.is_prerelease, "is_postrelease": v.is_postrelease,
            "is_devrelease": v.is_devrelease, "major": str(v.major), "minor": str(v.minor), "micro": str(v.micro),
        }

    out = {"input": s, "parse": call(lambda: describe(Version(s)))}
    out["canonical"] = call(canonicalize_version, s)
    out["canonical_keep_zero"] = call(canonicalize_version, s, strip_trailing_zero=False)
    return out


def record_version_order(versions: list[str]) -> dict:
    parsed = [Version(v) for v in versions]
    matrix = []
    for a in parsed:
        matrix.append("".join("<" if a < b else ">" if a > b else "=" for b in parsed))
    return {"versions": versions, "matrix": matrix}


def record_specifier(s: str, candidates: list[str], override) -> dict:
    out = {"input": s, "override": override, "candidates": candidates}
    try:
        spec = Specifier(s, prereleases=override)
    except InvalidSpecifier:
        out["parse"] = {"error": "InvalidSpecifier"}
        return out
    out["parse"] = {"ok": {"str": str(spec), "operator": spec.operator, "version": spec.version,
                           "prereleases": spec.prereleases}}
    out["contains"] = {str(p): [call(spec.contains, c, prereleases=p)["ok"] for c in candidates] for p in (None, True, False)}
    out["filter"] = {str(p): list(spec.filter(candidates, prereleases=p)) for p in (None, True, False)}
    out["hash_key"] = hash(spec) is not None  # forces the canonical form (raises AssertionError on Python bugs)
    return out


def record_specifier_set(s: str, candidates: list[str], override) -> dict:
    out = {"input": s, "override": override, "candidates": candidates}
    try:
        ss = SpecifierSet(s, prereleases=override)
    except InvalidSpecifier:
        out["parse"] = {"error": "InvalidSpecifier"}
        return out
    out["parse"] = {"ok": {"str": str(ss), "prereleases": ss.prereleases, "size": len(list(SpecifierSet(s)))}}
    fresh = lambda: SpecifierSet(s, prereleases=override)  # noqa: E731 - avoid cache effects between calls
    out["contains"] = {
        f"{p}/{inst}": [fresh().contains(c, prereleases=p, installed=inst) for c in candidates]
        for p in (None, True, False)
        for inst in (None, True)
    }
    out["filter"] = {str(p): list(fresh().filter(candidates, prereleases=p)) for p in (None, True, False)}
    out["unsatisfiable"] = fresh().is_unsatisfiable()
    hash(ss)
    return out


def record_marker(s: str) -> dict:
    out = {"input": s}
    try:
        m = Marker(s)
    except InvalidMarker as exc:
        out["parse"] = {"error": "InvalidMarker", "message": str(exc)}
        return out
    out["parse"] = {"ok": str(m)}
    results = []
    for env in ENVIRONMENTS:
        for extra in EXTRA_OVERRIDES:
            merged = {**env, **extra}
            for context in ("metadata", "lock_file", "requirement"):
                r = call(Marker(s).evaluate, merged, context)
                results.append(r.get("ok", r.get("error")))
    out["evaluate"] = results
    return out


def record_requirement(s: str) -> dict:
    out = {"input": s}
    try:
        r = Requirement(s)
    except InvalidRequirement as exc:
        out["parse"] = {"error": "InvalidRequirement", "message": str(exc)}
        return out
    hash(r)  # forces canonical specifiers and marker serialization
    out["parse"] = {"ok": {
        "str": str(r), "name": r.name, "url": r.url, "extras": sorted(r.extras), "specifier": str(r.specifier),
        "marker": None if r.marker is None else str(r.marker),
    }}
    return out


def record_name(s: str) -> dict:
    return {
        "input": s,
        "canonical": call(canonicalize_name, s),
        "validated": call(canonicalize_name, s, validate=True),
        "is_normalized": is_normalized_name(s),
    }


def record_wheel(s: str) -> dict:
    def describe(t):
        name, version, build, tags = t
        return {"name": name, "version": str(version), "build": None if not build else [str(build[0]), build[1]],
                "tags": sorted(str(x) for x in tags)}

    return {"input": s, "parse": call(lambda: describe(parse_wheel_filename(s)))}


def record_sdist(s: str) -> dict:
    return {"input": s, "parse": call(lambda: (lambda t: {"name": t[0], "version": str(t[1])})(parse_sdist_filename(s)))}


def guarded(kind: str, fn, *args) -> dict:
    """Runs a recorder; a Python-internal error marks the case so Kotlin must reject the input."""
    try:
        return fn(*args)
    except InternalError as exc:
        return {"input": args[0], "python_internal_error": str(exc)}
    except Exception as exc:  # noqa: BLE001
        return {"input": args[0], "python_internal_error": f"{type(exc).__name__}: {exc}"}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--count", type=int, default=1000, help="cases per category")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    g = Gen(args.seed)
    n = args.count

    cases: dict[str, list] = {k: [] for k in ("version", "version_order", "specifier", "specifier_set", "marker",
                                              "requirement", "name", "wheel", "sdist")}
    for _ in range(n):
        cases["version"].append(guarded("version", record_version, g.mutate(g.ws() + g.version() + g.ws(), 0.4)))
    for _ in range(max(1, n // 20)):
        cases["version_order"].append(record_version_order([g.version() for _ in range(30)]))
    for _ in range(n):
        s = g.mutate(g.specifier(), 0.25)
        cases["specifier"].append(guarded("specifier", record_specifier, s, g.candidates(s), g.pick([None, None, True, False])))
    for _ in range(n):
        s = g.mutate(g.specifier_set(), 0.15)
        cases["specifier_set"].append(guarded("specifier_set", record_specifier_set, s, g.candidates(s), g.pick([None, None, True, False])))
    for _ in range(n):
        cases["marker"].append(guarded("marker", record_marker, g.mutate(g.marker(), 0.2)))
    for _ in range(n):
        cases["requirement"].append(guarded("requirement", record_requirement, g.mutate(g.requirement(), 0.2)))
    for _ in range(n):
        cases["name"].append(record_name(g.mutate(g.name(), 0.3)))
    for _ in range(max(1, n // 4)):
        cases["wheel"].append(guarded("wheel", record_wheel, g.wheel()))
        cases["sdist"].append(guarded("sdist", record_sdist, g.sdist()))

    doc = {
        "packaging_version": packaging.__version__,
        "python_version": sys.version.split()[0],
        "seed": args.seed,
        "count": n,
        "environments": ENVIRONMENTS,
        "extra_overrides": [{k: plain(v) for k, v in o.items()} for o in EXTRA_OVERRIDES],
        "cases": cases,
    }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, default=plain)
    total = sum(len(v) for v in cases.values())
    internal = sum(1 for v in cases.values() for c in v if "python_internal_error" in c)
    print(f"packaging {packaging.__version__}, seed {args.seed}: {total} cases ({internal} Python-internal errors) -> {args.out}")


if __name__ == "__main__":
    main()
