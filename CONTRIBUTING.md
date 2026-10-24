# Contributing

## Build and test

JDK 21 is required to build. The Gradle wrapper downloads Gradle itself.

```bash
./gradlew test --console=plain -q                    # all unit and conformance tests; prints only failures
./gradlew test --console=plain -q --tests '*SpecifierConformanceTest*'   # one class (and its nested classes)
./gradlew testOnJava11 --console=plain -q            # the same tests on a Java 11 runtime (needs JDK 11 installed)
```

## Differential test

The differential test needs Python 3.14 and the pinned oracle.

```bash
python3 -m venv .venv
.venv/bin/pip install --require-hashes --only-binary=:all: -r tools/differential/requirements.txt
mkdir -p build/differential
.venv/bin/python -I tools/differential/generate.py --seed 1 --count 2000 --out build/differential/cases.json
./gradlew differentialTest -Pdifferential.cases=build/differential/cases.json --console=plain -q
```

On failure the report lists the first 40 mismatches, each with its input and the
Python and Kotlin results. A CI failure prints its seed, so you can rerun it
locally with `--seed`.

If you find a mismatch, fix the library rather than the generator. Then add a
case to `RegressionTest`.

## Upgrading to a new pypa/packaging release

1. Read upstream's changelog for behaviour changes in `version`, `specifiers`,
   `_ranges`, `markers`, `requirements`, `_parser`, `_tokenizer` and `utils`, and
   port them.
2. Re-harvest the vectors:

   ```bash
   git clone https://github.com/pypa/packaging /tmp/packaging && git -C /tmp/packaging checkout <tag>
   python3 -m venv /tmp/venv && /tmp/venv/bin/pip install pytest pretend packaging==<tag>
   /tmp/venv/bin/python -I tools/harvest_pypa_vectors.py /tmp/packaging src/test/resources/pypa
   ```

3. Port any new upstream test functions into `src/test/kotlin/.../conformance/`.
4. Update the pin and both hashes in `tools/differential/requirements.txt`.
5. Rerun `tools/pypi-corpus/regenerate.py`.
6. Update the version named in `README.md`, `NOTICE` and the KDoc.

## Unicode tables

`internal/PyUnicodeTables.kt` is generated. Regenerate it with
`python3 -I tools/gen_unicode_tables.py > src/main/kotlin/dev/greenflagged/pypackaging/internal/PyUnicodeTables.kt`.
CI fails if the tables differ from what CI's Python produces.

## Style

The public API is `explicitApi()`, documented with KDoc, and has to stay usable
from Java:

- `@JvmStatic` factories.
- `@JvmOverloads` for default arguments.
- No Kotlin-only types in public signatures.

`JavaApiTest` checks this.
