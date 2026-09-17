# Contributing to kzstd

Welcome. This guide covers environment setup, running the tests, and submitting a
change. See [`AGENTS.md`](AGENTS.md) for the architecture and design invariants.

## Code of Conduct

This project follows the [Meshtastic Code of Conduct](CODE_OF_CONDUCT.md). Be
excellent to one another.

## Developer Certificate of Origin (DCO)

We use the [Developer Certificate of Origin](https://developercertificate.org/),
not a CLA. Sign off every commit with `-s`:

```bash
git commit -s -m "Your message"
```

This appends a `Signed-off-by:` trailer. Configure it once so you don't forget:

```bash
git config format.signOff true
```

The org-wide [DCO App](https://github.com/apps/dco) blocks PRs whose commits aren't
signed off. To fix retroactively: `git rebase --signoff HEAD~N && git push --force-with-lease`.

## Environment

- **JDK 21** (Temurin recommended). The build pins the Kotlin toolchain to 21.
- Xcode 15+ is only needed to run the iOS/tvOS/macOS targets (macOS host).
- No submodules.

## Build & test

| Task | Command |
|---|---|
| Full check (all 13 targets, tests, API check) | `./gradlew build` |
| JVM tests (incl. the zstd-jni interop oracle) | `./gradlew jvmTest` |
| API surface check | `./gradlew apiCheck` |
| API surface dump (after an intended change) | `./gradlew apiDump` |
| Reformat Kotlin (Spotless/ktlint) | `./gradlew spotlessApply` |
| Formatting + static-analysis gate | `./gradlew spotlessCheck detekt` |
| Regenerate the trained test dictionary | `python3 scripts/train_test_dict.py` |

Formatting (Spotless/ktlint) and static analysis (detekt) are wired into the build
and gated in CI. ktlint reads `.editorconfig`, so that file remains the single
source of Kotlin style. Run `./gradlew spotlessApply` to auto-format before
committing. Pre-existing findings in the RFC 8878 engine (lifted verbatim from
TAKPacket-SDK) are recorded in `config/detekt/baseline.xml`; the gate blocks *new*
issues. Regenerate the baseline after intentionally clearing engine findings with
`./gradlew detektBaseline`.

## Public API changes

The public API is captured in `api/kzstd.api` by the binary-compatibility-validator
and `explicitApi()`. After an **intentional** public-API change, run
`./gradlew apiDump` and commit the regenerated `api/kzstd.api` in the same PR.
`./gradlew apiCheck` (part of `build`) fails on unintended drift. Never edit the
`.api` file by hand.

## Changelog

[`CHANGELOG.md`](CHANGELOG.md) is hand-written in
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form. The JetBrains
[gradle-changelog-plugin](https://github.com/JetBrains/gradle-changelog-plugin) parses
and renders it and never generates an entry from a commit.

Add an entry under `## [Unreleased]` for anything a consumer would notice — a new or
changed public API, a behaviour change, a fix to something they could have hit, a
security property, a compressed-output or frame-format change. Refactors, test-only
changes and CI work need none.

**A change that moves `api/kzstd.api` or `api/kzstd.klib.api` always needs an entry**,
and it goes under `### Breaking` if a consumer has to change code rather than just
recompile. `Breaking` leads the group order for that reason: with committed ABI dumps,
the first thing a consumer needs to know is whether recompiling is enough.

The changelog is also what the GitHub Release page says — `release.yml` renders
`./gradlew getChangelog --no-header --no-links` into the release body, and GitHub's own
`generate_release_notes` is deliberately off, so a release is described once.

## Submitting a change

1. Branch off `main`.
2. Make the change; add tests for any new behavior.
3. Run `./gradlew build` and fix anything red. If you changed the public API, run
   `./gradlew apiDump` and commit the result.
4. Add a `CHANGELOG.md` entry under `## [Unreleased]` if a consumer would notice —
   see [Changelog](#changelog) above. An `api/*.api` move always needs one.
5. Sign off every commit (`git commit -s`).
6. Open the PR and describe what changed and why.

## Reusing code from sibling Meshtastic-org projects

kzstd is GPL-3.0, the same license as the other Meshtastic projects, so lifting
code from them is allowed. When you do: keep the `SPDX-License-Identifier: GPL-3.0-or-later`
header, add a copyright line crediting the source repo, and note the origin in the
commit message (`Origin: <repo>/<path> @ <sha>`).

## Reporting security issues

Don't open a public issue — follow [`SECURITY.md`](SECURITY.md).
