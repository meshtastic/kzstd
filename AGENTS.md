# AGENTS.md

Canonical guidance for AI coding agents and maintainers working in this repo.
(`CLAUDE.md` and `GEMINI.md` are pointers to this file.)

## First read

1. `README.md` — what kzstd is and how to use it.
2. `CONTRIBUTING.md` — environment, build/test commands, DCO sign-off, PR flow.
3. `CHANGELOG.md` — release history.
4. The design invariants below — do not violate them.

## What this is

kzstd is a pure-Kotlin, multiplatform Zstandard (zstd) codec with dictionary
support and **zero runtime dependencies**. It produces and reads standard zstd
frames that interoperate with libzstd in both directions. It was extracted from
[TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK).

## Layout

- `src/commonMain/kotlin/org/meshtastic/kzstd/` — the public API: `Zstd` (the
  compress/decompress facade), `ZstdDictionary` (a digested dictionary), and
  `ZstdException`.
- `src/commonMain/kotlin/org/meshtastic/kzstd/internal/` — the RFC 8878 engine
  (encoder, decoder, FSE/Huffman, bit readers/writers, dictionary parser, match
  index). All `internal`; not part of the public API.
- `src/{commonTest,jvmTest,nativeTest,wasmWasiTest}/` — tests.
- `scripts/train_test_dict.py` — regenerates the committed trained test dictionary.

This is intentionally a **single-module** project: kzstd is one small codec, so the
multi-module `build-logic` / `bom` scaffolding used by larger meshtastic KMP SDKs
would be over-engineering here.

## Design invariants (do not violate)

- **One-shot only (no incremental streaming API yet).** Every frame is independently
  decodable; there is no cross-call state.
- **One block per frame (current limit).** The encoder emits a single block, so
  `compress` rejects inputs > 128 KiB (zstd's `Block_Maximum_Size`) with a
  `ZstdException`. Keep that guard until multi-block encoding lands — without it a
  large input silently produces a frame neither libzstd nor kzstd can decode.
- **No shared mutable state, no lock.** A `ZstdDictionary` digests its dictionary
  once in its constructor and is immutable thereafter; the engine objects keep all
  per-call state in locals. Do not reintroduce global caches. The encoder's
  predefined FSE tables must stay `by lazy` (safe cross-thread publication) — this
  is what lets the codec carry no `atomicfu`/lock.
- **`maxSize` is a required decompression-bomb guard** on every decode.
- **The public API throws only `ZstdException`** (annotated `@Throws` so it bridges
  to Swift / Kotlin-Native callers instead of aborting the process).
- **`explicitApi()` + binary-compatibility-validator:** run `./gradlew apiDump`
  after any public-API change and commit `api/kzstd.api`.
- **Frames stay libzstd-interoperable in both directions** — guarded by the
  `jvmTest` zstd-jni oracle and the pinned cross-target dict-entropy fixture.

## Commands (needs JDK 21)

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew build          # compile all 13 targets, run tests, apiCheck
./gradlew jvmTest        # JVM tests (incl. the zstd-jni interop oracle)
./gradlew apiDump        # refresh the API baseline after public-API changes
python3 scripts/train_test_dict.py   # regenerate the trained test dictionary
```

The interop oracle (zstd-jni) and the native concurrency test run on macOS; the
iOS/tvOS *simulator* tests need their SDKs installed (CI runs the full matrix on
`macos-latest`). Linux/Windows native test binaries are cross-compiled but run only
on their own host.

## Publishing

Maven Central via the vanniktech plugin (`org.meshtastic:kzstd`); JitPack is a
fallback (`com.github.meshtastic:kzstd`). Releases are tag-driven — see
`RELEASING.md`.

## Conventions

- Commits are **signed off** (DCO): `git commit -s`. The repo owner prefers to be
  the commit author — do **not** add `Co-Authored-By` trailers.
- Source files carry an `SPDX-License-Identifier: GPL-3.0-or-later` header.
- Commit messages: imperative mood, with a body explaining what + why.
- Do not auto-commit; stage changes and describe what you did.
