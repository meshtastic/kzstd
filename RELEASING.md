# Releasing

kzstd publishes `org.meshtastic:kzstd` to Maven Central with the Kotlin Toolchain
(`./kotlin publish mavenCentral`), from `.github/workflows/release.yml`. JitPack is no longer
a channel: consumers take the artifacts from Central.

## Secrets

`SIGNING_KEY` (the ASCII-armored GPG key, passed as `KOTLIN_TOOLCHAIN_SIGNING_KEY`), and
`OSSRH_USERNAME` and `OSSRH_PASSWORD` (the Central Portal user token, passed as
`KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME` and `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD`).

## Cutting a release

1. Pick `X.Y.Z` (SemVer; before 1.0 a minor may break).
2. On a branch, set `VERSION` and `settings.publishing.version` in `kzstd/module.yaml` to
   `X.Y.Z`, and run `scripts/changelog.sh cut X.Y.Z`. That moves `## [Unreleased]` under a dated
   `## [X.Y.Z]` heading and updates the compare links, touching nothing else. It refuses
   an empty Unreleased.
3. If the public API changed, `./kotlin run -m abitool -- dump "$PWD/build" api kzstd` and commit `api/`.
4. Commit `chore(release): X.Y.Z` (signed off), open the PR and merge it.
5. `gh workflow run release.yml --repo meshtastic/kzstd -f version=X.Y.Z`. Add
   `-f dry_run=true` to run every gate without tagging or publishing; a dry run may
   start from any branch. Pushing a `vX.Y.Z` tag on `main` runs the same workflow.

## What the workflow checks, in order

1. The commit is on `main` (skipped for a dry run).
2. The version equals `VERSION` and `settings.publishing.version` in `kzstd/module.yaml`, and
   any existing `vX.Y.Z` tag points at this commit.
3. `scripts/changelog.sh notes X.Y.Z` finds a non-empty section. It becomes the GitHub
   Release body verbatim.
4. Every check the `main` ruleset requires passed on this commit
   (`scripts/release-checks.sh green-ci`). That is where the Apple tests ran; this
   Linux runner cannot run them.
5. `./kotlin build`, the JVM, Wasm and linuxX64 tests, and the ABI check, then
   `./kotlin publish mavenLocal` with signing into `$RUNNER_TEMP/m2`.
6. No staged POM or module depends on a `-SNAPSHOT`
   (`scripts/release-checks.sh no-snapshots`). Central rejects that only after upload.
7. If `X.Y.Z` is already on `repo1.maven.org` the publish is skipped, so a re-run is
   safe.

Then it attests every staged artifact, pushes the annotated `vX.Y.Z` tag if it is
missing, runs `./kotlin publish mavenCentral`, and creates or updates the GitHub
Release.

## After releasing

The first Toolchain release uses `publishingMode: manual`, so the upload waits on the
Central Portal. Release it there by hand, then check the artifacts appear.

`repo1.maven.org` lags the Central Portal by 10 to 30 minutes. Downstream bumps wait
until `https://repo1.maven.org/maven2/org/meshtastic/kzstd-jvm/X.Y.Z/` resolves.
