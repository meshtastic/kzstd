# Releasing

kzstd publishes `org.meshtastic:kzstd` to Maven Central with the vanniktech
maven-publish plugin, from `.github/workflows/release.yml`. JitPack
(`com.github.meshtastic:kzstd`) is a fallback channel.

## Secrets

`SIGNING_KEY` (the in-memory GPG key), `OSSRH_USERNAME` and `OSSRH_PASSWORD` (Central
Portal credentials), passed as the vanniktech `ORG_GRADLE_PROJECT_*` properties.

## Cutting a release

1. Pick `X.Y.Z` (SemVer; before 1.0 a minor may break).
2. On a branch, set `VERSION` and `VERSION_NAME` in `gradle.properties` to `X.Y.Z`, and
   run `scripts/changelog.sh cut X.Y.Z`. That moves `## [Unreleased]` under a dated
   `## [X.Y.Z]` heading and updates the compare links, touching nothing else. It refuses
   an empty Unreleased.
3. If the public API changed, `./gradlew apiDump` and commit `api/`.
4. Commit `chore(release): X.Y.Z` (signed off), open the PR and merge it.
5. `gh workflow run release.yml --repo meshtastic/kzstd -f version=X.Y.Z`. Add
   `-f dry_run=true` to run every gate without tagging or publishing; a dry run may
   start from any branch. Pushing a `vX.Y.Z` tag on `main` runs the same workflow.

## What the workflow checks, in order

1. The commit is on `main` (skipped for a dry run).
2. The version equals `VERSION` and `VERSION_NAME`, and any existing `vX.Y.Z` tag
   points at this commit.
3. `scripts/changelog.sh notes X.Y.Z` finds a non-empty section. It becomes the GitHub
   Release body verbatim.
4. Every check the `main` ruleset requires passed on this commit
   (`scripts/release-checks.sh green-ci`). That is where the Apple tests ran; this
   Linux runner cannot run them.
5. `./gradlew build`, then `publishToMavenLocal` with signing.
6. No staged POM or Gradle module depends on a `-SNAPSHOT`
   (`scripts/release-checks.sh no-snapshots`). Central rejects that only after upload.
7. If `X.Y.Z` is already on `repo1.maven.org` the publish is skipped, so a re-run is
   safe.

Then it attests every staged artifact, pushes the annotated `vX.Y.Z` tag if it is
missing, runs `publishAndReleaseToMavenCentral`, and creates or updates the GitHub
Release.

## After releasing

`repo1.maven.org` lags the Central Portal by 10 to 30 minutes. Downstream bumps wait
until `https://repo1.maven.org/maven2/org/meshtastic/kzstd-jvm/X.Y.Z/` resolves.
