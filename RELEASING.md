# Releasing

A release is a pushed tag such as `v0.1.0`. `.github/workflows/release.yml` then:

1. Builds the project and runs every test, including on a Java 11 runtime.
2. Runs the differential test.
3. Signs the artifacts.
4. Uploads them to the Sonatype Central Portal.

Nothing reaches Maven Central until someone presses **Publish** in the Portal.

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

The workflow signs with the primary key. If you want a signing subkey instead,
export only that subkey with `--export-secret-subkeys <SUBKEY_ID>!`.

### 3. GitHub environment and secrets

In `greenflagged/python-packaging-kotlin` → **Settings**:

1. **Environments → New environment** named `maven-central`.
   - Add **Required reviewers**: the people allowed to release.
   - Under **Deployment branches and tags**, allow only tags matching `v*`.
2. Add these **environment secrets** to `maven-central`, not as repository secrets:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | The full contents of `signing-key.asc`, including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` lines. |
   | `SIGNING_PASSWORD` | The key's passphrase. |
   | `CENTRAL_TOKEN_USERNAME` | The username of the user token from step 1.5. |
   | `CENTRAL_TOKEN_PASSWORD` | The password of the user token from step 1.5. |

3. Optional: set the repository **variable** `CENTRAL_PUBLISHING_TYPE` to
   `AUTOMATIC` to publish as soon as validation passes. The default is
   `USER_MANAGED`, which waits for a manual **Publish** in the Portal. Keep the
   default until the first release has been published successfully.
4. **Rules → Rulesets → New tag ruleset**: target `v*` and restrict creation to
   maintainers, so only they can trigger a release.
5. Delete `signing-key.asc`, and keep an offline backup of the key.

## Each release

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

If validation fails, the job prints the Portal's reasons. Drop the failed
deployment in the Portal, fix the cause, delete and re-push the tag (it never
reached Central), and approve again.

## Dry run (no credentials needed)

To see exactly what would be uploaded, stage an unsigned build locally:

```bash
./gradlew publishMavenPublicationToStagingRepository -Pversion=0.1.0-SNAPSHOT
find build/staging-deploy -type f
```

To dry-run signing too, export `SIGNING_KEY` (ASCII-armored private key) and
`SIGNING_PASSWORD`, then use a non-SNAPSHOT version. Signing is required for
non-SNAPSHOT versions.

The bundle must contain, each with `.asc`, `.md5` and `.sha1`:

- the jar, `-sources.jar` and `-javadoc.jar`
- the `.pom`
- the `.module` file
