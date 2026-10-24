# python-packaging for the JVM

Python's packaging standards for Kotlin and Java: [PEP 440] versions and
specifiers, [PEP 508] requirements and environment markers, and [PEP 503] name
normalization, plus wheel and sdist filename parsing.

It is a port of [pypa/packaging] **26.3**, the library pip uses. Conformance is
the specification: the port passes pypa/packaging's own test vectors, and a
differential test runs Python `packaging` on generated inputs in CI and compares
every result.

- Zero runtime dependencies besides the Kotlin standard library.
- Java 11+ bytecode. The API is Java-friendly: static factories, immutable value
  types, and no Kotlin-only types in public signatures.
- Built for untrusted input. Every parser is a hand-written linear scanner, with
  no regular expressions and so no ReDoS. Input sizes and nesting depth are bounded.

```kotlin
dependencies {
    implementation("dev.greenflagged:python-packaging:0.1.0")
}
```

## Usage

```kotlin
import dev.greenflagged.pypackaging.*

// PEP 440 versions: parse, normalize, compare.
val v = Version.parse("1.0RC1")
v.toString()                         // "1.0rc1"
v.isPrerelease                       // true
v < Version.parse("1.0")             // true
Version.parse("1.0") == Version.parse("1.0.0")   // true

// Specifier sets.
val spec = SpecifierSet.parse(">=1.0,!=1.3.*,<2")
spec.contains("1.4")                 // true
spec.contains("1.3.1")               // false
spec.contains("1.5a1", false)        // false: pre-releases excluded explicitly
spec.filter(listOf("0.9", "1.2", "1.5", "2.0"))  // [1.2, 1.5]
SpecifierSet.parse(">2,<1").isUnsatisfiable()    // true

// PEP 508 requirements and markers.
val req = Requirement.parse("requests[socks]>=2.8.1; python_version >= '3.8'")
req.name; req.extras; req.specifier; req.marker; req.url

val linux312 = MarkerEnvironment.builder()
    .pythonVersion("3.12").pythonFullVersion("3.12.4")
    .implementationName("cpython").implementationVersion("3.12.4")
    .platformPythonImplementation("CPython")
    .osName("posix").sysPlatform("linux").platformSystem("Linux")
    .platformMachine("x86_64").platformRelease("6.8.0").platformVersion("")
    .build()
req.marker?.evaluate(linux312)       // true

// PEP 503 names and filenames.
Names.canonicalize("Foo.Bar__baz")   // "foo-bar-baz"
Filenames.parseWheel("requests-2.31.0-py3-none-any.whl").tags
```

From Java the same calls are static factories and getters:

```java
SpecifierSet set = SpecifierSet.parse(">=1.0,<2");
boolean ok = set.contains("1.5", /* prereleases */ null, /* installed */ null);
Version v = Version.builder().release(1, 2).pre(PreReleaseKind.RC, 1).build();
```

### Pre-release rules

These follow packaging 26.x, which differs from older pip versions:

- `contains(version)` with no explicit setting accepts a pre-release that falls
  inside the range, so `SpecifierSet.parse(">=1.0").contains("1.1a1")` is `true`.
  Pass `prereleases = false` to exclude pre-releases.
- `filter(versions)` keeps pre-releases only when no final release matches.
- `!=` and `==X.*` specifiers never opt in to pre-releases. A specifier whose own
  version is a pre-release (`>=1.0a1`) does opt in.

### Marker environments

Python's `Marker.evaluate()` fills in the running interpreter's values by default.
A JVM has no interpreter to describe, so a `MarkerEnvironment` must give every
variable a marker uses. A missing variable throws
`UndefinedEnvironmentNameException`. To vet dependencies across platforms,
evaluate once per target environment. `EvaluateContext.METADATA` (the default)
defaults `extra` to `""`. `LOCK_FILE` defaults `extras` and `dependency_groups`
to empty sets.

## Security model

The library is meant to read package metadata from untrusted sources.

| Risk | Mitigation |
|---|---|
| Regex backtracking (ReDoS) | No regular expressions. Each grammar is a single-pass scanner, so time is linear in input length. |
| Oversized input | `Limits`: versions up to 1 KiB, specifier sets up to 16 KiB, markers and requirements up to 64 KiB, filenames up to 4 KiB. Longer input is rejected before any parsing. |
| Deep nesting / stack overflow | Marker parentheses are limited to 100 levels. |
| Huge numbers | Components are `BigInteger`, which is exact. The length limit bounds their size. |
| Combinatorial expansion | Compressed wheel tag sets expand to at most 4,096 tags. |

`AdversarialInputTest` exercises the worst cases at each limit. Inputs within the
limits behave exactly as in Python. Report vulnerabilities as described in
[SECURITY.md](SECURITY.md).

## Conformance

Three independent checks, all run in CI:

1. **pypa/packaging's test vectors.** Every parametrized case in upstream's
   `test_version.py`, `test_specifiers.py`, `test_requirements.py`,
   `test_markers.py` and `test_utils.py` is extracted mechanically into
   `src/test/resources/pypa/` by `tools/harvest_pypa_vectors.py`, so nothing is
   transcribed by hand. The Kotlin tests in `conformance/` port each upstream test
   function's assertions. Each file's header lists the upstream tests that are
   not ported, with the reason.
2. **Differential test.** `tools/differential/generate.py` builds random valid,
   near-valid and mutated inputs from the grammars. It records everything Python
   `packaging` 26.3 does with them: parsing, normalization, ordering, `contains`
   and `filter` with each pre-release setting, unsatisfiability, marker
   evaluation across environments, and exact error messages. `DifferentialTest`
   then requires identical results. CI runs a fixed seed and a fresh seed on
   every push, and weekly.
3. **Real PyPI versions.** 7,645 version strings published on PyPI and 202,916
   ordering pairs, with answers from Python.

### Known differences from Python

Each of these is deliberate:

- **Input bounds.** Inputs over the size limits above are rejected. Python
  accepts them.
- **Inputs Python accepts but cannot use.** These are rejected at parse time:
  - A marker string value containing both `'` and `"`. Python parses it but then
    fails to convert the marker back to a string.
  - A `~=` specifier whose letters only match through Python's Unicode case
    folding, such as `~=1.0poſt1`. Python accepts it and then fails an internal
    assertion when it is used.
- **`SpecifierSet` ordering.** Iteration and `size` keep the given order and
  duplicates. Python reorders and deduplicates in place the first time a set is
  printed or compared. Equality, hashing and `toString` are the same in both.
- **Immutability.** Python's mutable `prereleases` setters become
  `withPrereleases(...)` copies.
- **Unicode.** Lowercasing, `\w`/`\b` word boundaries, whitespace and `repr()`
  use tables generated from Python's Unicode 16.0 data
  (`tools/gen_unicode_tables.py`). Results do not depend on the JVM's Unicode
  version. Two narrow exceptions use the JVM's tables:
  - `\N{name}` escapes in marker strings. Name aliases are rejected.
  - Non-ASCII wheel interpreter tags (`str.isidentifier`).
- **Not in this version.** These packaging APIs are not ported:
  - `VersionRange`, `SpecifierSet.to_range`, `is_subset`, `is_superset`,
    `is_disjoint`.
  - `packaging.tags` beyond `Tag` and `parse_tag`.
  - `packaging.metadata`, `pylock`, licenses.
  - Pickling.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). This is a derivative
work of pypa/packaging (Apache-2.0 OR BSD-2-Clause, Copyright (c) Donald Stufft
and individual contributors), whose test vectors are included with attribution.

[PEP 440]: https://peps.python.org/pep-0440/
[PEP 508]: https://peps.python.org/pep-0508/
[PEP 503]: https://peps.python.org/pep-0503/
[pypa/packaging]: https://github.com/pypa/packaging
