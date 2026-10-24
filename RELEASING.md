# Releasing

A release is a pushed tag such as `v0.1.0`. `.github/workflows/release.yml` then runs two jobs:

1. **build** has no secrets. It:
   - checks that the tagged commit is on `main`;
   - builds the project and runs every test, including on a Java 11 runtime;
   - runs the differential test;
   - stages unsigned artifacts.
2. **publish** runs in the protected `maven-central` environment. It signs the
   staged files with `gpg` and uploads them to the Sonatype Central Portal. It
   runs no Gradle and checks out no code, so no build plugin or dependency runs
   while the signing key is loaded.

Nothing reaches Maven Central until someone presses **Publish** in the Portal.
Tags must look like `v1.2.3`, optionally with `-alpha.N`, `-beta.N` or `-rc.N`.
SNAPSHOT tags are rejected.

## One-time setup (repository owner)

These steps need the owner's accounts and keys. Do them once.

### 1. Sonatype Central Portal account

1. Sign in at <https://central.sonatype.com> and create an account.
2. Go to **Namespaces → Add Namespace** and enter `dev.greenflagged`.
3. The Portal shows a verification key. Add it as a DNS **TXT** record on the
   domain `greenflagged.dev` (the namespace reversed). Use the apex `@`, or the
   host the Portal names, and set the value to exactly the key shown.
4. Wait for DNS to propagate. You can check with `dig +short TXT greenflagged.dev`.
   Then press **Verify Namespace**. The namespace must show as *Verified*.
5. Go to **Account → Generate User Token**. Keep the token's username and
   password; they are used in step 3 below. This is not your login password.

### 2. GPG signing key

Central requires every artifact to be signed, and checks signatures against
public keyservers.

```bash
gpg --full-generate-key        # RSA 4096 (or ed25519), sign-capable; set a passphrase
gpg --list-secret-keys --keyid-format=long   # note the key ID, e.g. 0123456789ABCDEF
gpg --keyserver keyserver.ubuntu.com --send-keys 0123456789ABCDEF
gpg --keyserver keys.openpgp.org     --send-keys 0123456789ABCDEF  # then confirm the email it sends
gpg --armor --export-secret-keys 0123456789ABCDEF > signing-key.asc   # for the secret below; delete afterwards
```

The workflow signs with the primary key by default. To sign with a subkey
instead, set the optional secret `SIGNING_KEY_ID` to the subkey's 16-hex-digit
ID followed by `!`, e.g. `0123456789ABCDEF!`. The workflow passes it to
`gpg --local-user`. Publish the primary public key to the keyservers either way.
(For a local Gradle dry run, `SIGNING_KEY_ID` is instead Gradle's 8-hex-digit
short ID, without the `!`.)

### 3. GitHub environment and secrets

In `greenflagged/python-packaging-kotlin` → **Settings**:

1. **Environments → New environment** named `maven-central`.
   - Add **Required reviewers**: the people allowed to release.
   - Under **Deployment branches and tags**, allow only tags matching `v*`.
   - Optionally, require signed tags (`git tag -s`) in the tag ruleset below.
2. Add these **environment secrets** to `maven-central`, not as repository secrets:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | The full contents of `signing-key.asc`, including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` lines. |
   | `SIGNING_PASSWORD` | The key's passphrase. |
   | `CENTRAL_TOKEN_USERNAME` | The username of the user token from step 1.5. |
   | `CENTRAL_TOKEN_PASSWORD` | The password of the user token from step 1.5. |
   | `SIGNING_KEY_ID` | Optional. A subkey ID, to sign with that subkey instead of the primary key. |

3. Optional: set the repository **variable** `CENTRAL_PUBLISHING_TYPE` to
   `AUTOMATIC` to publish as soon as validation passes. The default is
   `USER_MANAGED`, which waits for a manual **Publish** in the Portal. Keep the
   default until the first release has been published successfully.
4. **Rules → Rulesets → New tag ruleset**: target `v*` and restrict creation to
   maintainers, so only they can trigger a release.
5. Delete `signing-key.asc`, and keep an offline backup of the key.

## Each release

For the very first release, tag a release candidate first (`v0.1.0-rc.1`). It
exercises the whole pipeline, signing and Portal validation included. Then drop
that deployment in the Portal instead of publishing it.

1. Make sure `main` is green in CI.
2. Update `CHANGELOG.md`. The version in `gradle.properties` stays at the next
   `-SNAPSHOT`, because the workflow takes the release version from the tag.
3. Tag and push:

   ```bash
   git tag -a v0.1.0 -m "v0.1.0"
   git push origin v0.1.0
   ```

4. In **Actions → Release**, approve the `maven-central` deployment.
5. The job ends with `Validated`. Then open
   <https://central.sonatype.com/publishing>, check the deployment's files, and
   press **Publish**. It takes 10–30 minutes to appear on
   <https://repo1.maven.org/maven2/dev/greenflagged/python-packaging/>.
6. Bump `gradle.properties` to the next `-SNAPSHOT` version.
7. After the first release, have the Greenflagged registry consume the published
   artifact for registry issue #482. That is the last item of #522's "Done when"
   list.

If validation fails, the job prints the Portal's reasons. Drop the failed
deployment in the Portal, fix the cause, delete and re-push the tag (it never
reached Central), and approve again.

## Dry run

Without credentials, an unsigned SNAPSHOT build shows the approximate layout.
SNAPSHOT files are timestamped and have no `.asc` signatures, and Central does
not accept SNAPSHOTs:

```bash
rm -rf build/staging-deploy
./gradlew publishMavenPublicationToStagingRepository -Pversion=0.1.0-SNAPSHOT
find build/staging-deploy -type f
```

For the exact bundle, sign a release version with any key:

1. Export `SIGNING_KEY` (an ASCII-armored private key; a throwaway key is fine)
   and `SIGNING_PASSWORD`.
2. Run the same commands with `-Pversion=0.1.0`. Signing is required for
   non-SNAPSHOT versions, so this fails without a key.
3. Apply the workflow's `zip -x '*/maven-metadata.xml*'` step to see exactly
   what would be uploaded.

The release workflow itself stages with `-Punsigned` and signs with `gpg` in its
`publish` job.

The bundle must contain, each with `.asc`, `.md5` and `.sha1`:

- the jar, `-sources.jar` and `-javadoc.jar`
- the `.pom`
- the `.module` file
