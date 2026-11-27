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

- No JDK install: the `./kotlin` wrapper provisions JDK 21 for the build.
- Xcode 15+ is only needed to run the iOS/tvOS/macOS targets (macOS host).
- No submodules.

## Build & test

| Task | Command |
|---|---|
| Full build (all 13 targets) | `./kotlin build` |
| JVM tests (incl. the zstd-jni interop oracle) | `./kotlin test -m kzstd -p jvm` |
| API surface check | `./kotlin run -m abitool -- check "$PWD/build" api kzstd` |
| API surface dump (after an intended change) | `./kotlin run -m abitool -- dump "$PWD/build" api kzstd` |
| Static analysis (detekt) | `./kotlin check detekt -m kzstd` |
| Formatting (ktlint CLI 1.8.0, check only) | `java -jar ktlint-cli-1.8.0-all.jar "kzstd/src/**/*.kt" "kzstd/test*/**/*.kt"` |
| Regenerate the trained test dictionary | `python3 scripts/train_test_dict.py` |

The `kotlin` wrapper provisions the Toolchain and its own JDK, so there is no separate JDK to
install. Run `./kotlin` from the repo root.

detekt runs through the local plugin in `plugins/detekt`, without type resolution. ktlint reads `.editorconfig`, so that file remains the
single source of Kotlin style; CI runs the ktlint CLI in check mode, so reformat with
`java -jar ktlint-cli-1.8.0-all.jar -F ...` before committing. Pre-existing findings in the RFC
8878 engine (lifted verbatim from TAKPacket-SDK) are recorded in `kzstd/detekt/baseline.xml`; the
gate blocks *new* issues.

## Public API changes

The public API is captured in `api/kzstd.api` by the binary-compatibility-validator
and `explicitApi()`. After an **intentional** public-API change, run
`./kotlin run -m abitool -- dump "$PWD/build" api kzstd` and commit the regenerated
`api/kzstd.api` and `api/kzstd.klib.api` in the same PR. The `check` mode fails on
unintended drift, and CI runs it. Never edit the `.api` files by hand.

## Changelog

[`CHANGELOG.md`](CHANGELOG.md) is hand-written in
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form, and nothing generates an
entry from a commit. `scripts/changelog.sh` cuts and reads sections without re-rendering
the file, so prose under any heading survives a release.

Add an entry under `## [Unreleased]` for anything a consumer would notice — a new or
changed public API, a behaviour change, a fix to something they could have hit, a
security property, a compressed-output or frame-format change. Refactors, test-only
changes and CI work need none.

**A change that moves `api/kzstd.api` or `api/kzstd.klib.api` always needs an entry**,
and it goes under `### Breaking` if a consumer has to change code rather than just
recompile. `Breaking` comes first in a section for that reason: with committed ABI
dumps, the first thing a consumer needs to know is whether recompiling is enough.

The changelog is also what the GitHub Release page says. `release.yml` puts
`scripts/changelog.sh notes X.Y.Z` in the release body, and GitHub's own
`generate_release_notes` is off, so a release is described once. Releasing is in
[RELEASING.md](RELEASING.md).

## Submitting a change

1. Branch off `main`.
2. Make the change; add tests for any new behavior.
3. Run `./kotlin build` and fix anything red. If you changed the public API, run
   the `abitool dump` command above and commit the result.
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
