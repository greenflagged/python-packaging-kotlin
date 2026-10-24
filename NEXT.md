# Next steps

The work for registry issue #522 is complete and committed on `main`. The tests are
green: 4,829 tests on both JDK 21 and Java 11, and about 200,000 differential cases
with 0 mismatches. The specifier and marker vectors, the differential CI and the
publishing prep are all done. What remains:

1. **Create and push the public repo.** This needs the user's approval.
   ```
   gh repo create greenflagged/python-packaging-kotlin --public --source . --remote origin --push
   ```
2. **Watch the first CI run on GitHub.** It has never run there; the workflows
   were only checked locally.
3. **Owner setup, from RELEASING.md:**
   - a Sonatype Central account, with `dev.greenflagged` verified by a DNS TXT
     record on greenflagged.dev;
   - a GPG key published to the keyservers;
   - a protected `maven-central` environment holding the four secrets;
   - a `v*` tag ruleset.
4. **Rehearse the release** with a `v0.1.0-rc.1` tag. The gpg signing and upload
   job has not been run end to end. Drop that deployment in the Portal, then
   release `v0.1.0`.
5. **Consume the published artifact in the registry** for #482. That is the last
   item of #522's "Done when" list.

Out of scope for v1 (listed in the README): `VersionRange` / `is_subset` /
`is_superset` / `is_disjoint`, and `default_environment()`.
