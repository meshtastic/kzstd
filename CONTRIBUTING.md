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

## Submitting a change

1. Branch off `master`.
2. Make the change; add tests for any new behavior.
3. Run `./gradlew build` and fix anything red. If you changed the public API, run
   `./gradlew apiDump` and commit the result.
4. Sign off every commit (`git commit -s`).
5. Open the PR and describe what changed and why.

## Reusing code from sibling Meshtastic-org projects

kzstd is GPL-3.0, the same license as the other Meshtastic projects, so lifting
code from them is allowed. When you do: keep the `SPDX-License-Identifier: GPL-3.0-or-later`
header, add a copyright line crediting the source repo, and note the origin in the
commit message (`Origin: <repo>/<path> @ <sha>`).

## Reporting security issues

Don't open a public issue — follow [`SECURITY.md`](SECURITY.md).
