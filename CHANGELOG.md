# Changelog

## 0.1.0 (unreleased)

First release. A port of pypa/packaging 26.3:

- PEP 440 versions (`Version`): parsing, normalization and ordering.
- PEP 440 specifiers and specifier sets (`Specifier`, `SpecifierSet`): all
  operators, wildcards, local versions, the 26.x pre-release rules, and
  `isUnsatisfiable`.
- PEP 508 requirements (`Requirement`), including extras and URLs.
- PEP 508 environment markers (`Marker`, `MarkerEnvironment`), including
  `extras` and `dependency_groups` for lock files.
- PEP 503 name normalization (`Names`).
- Wheel and sdist filename parsing (`Filenames`, `Tag`).
