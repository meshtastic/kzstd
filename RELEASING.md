# Releasing

kzstd publishes to Maven Central (`org.meshtastic:kzstd`) via the vanniktech
maven-publish plugin, driven by `.github/workflows/release.yml`. JitPack
(`com.github.meshtastic:kzstd`) is a fallback channel.

## One-time setup

The repository needs three GitHub Actions secrets (the vanniktech
`ORG_GRADLE_PROJECT_*` convention):

- `SIGNING_KEY` — the in-memory GPG signing key.
- `OSSRH_USERNAME` — Sonatype Central Portal username.
- `OSSRH_PASSWORD` — Sonatype Central Portal password.

## Cutting a release

1. Pick the new version `X.Y.Z` (SemVer; pre-1.0 may break in minors).
2. Update `VERSION` **and** `gradle.properties` (`VERSION_NAME`) to `X.Y.Z` — they
   must match (the release workflow verifies this and fails loudly otherwise).
3. Move the `## [Unreleased]` notes in `CHANGELOG.md` under a new `## [X.Y.Z]`
   heading and update the compare links.
4. If the public API changed, ensure `api/kzstd.api` was refreshed (`./gradlew apiDump`).
5. Commit (signed off) and open/merge the PR.
6. Trigger the release: push a `vX.Y.Z` tag, or run the **Release** workflow via
   `workflow_dispatch` (it tags for you).

The workflow then builds all 13 targets on `macos-latest`, publishes to Maven
Central, and creates a GitHub Release. It is **idempotent**: it probes
`repo1.maven.org` first and skips the publish if `X.Y.Z` is already there, so a
re-run after a partial failure is safe.

## After releasing

Maven Central → repo1 propagation typically takes 10–30 minutes. Verify
`https://repo1.maven.org/maven2/org/meshtastic/kzstd-jvm/X.Y.Z/` resolves before
downstream consumers bump.
