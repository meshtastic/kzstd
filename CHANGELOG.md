# Changelog

All notable changes to kzstd are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Encoder-side parity work closing several gaps against the libzstd/RFC 8878
spec — real ratio improvements for dictionary-compressed frames, no wire
format or public-API changes.

### Changed

- The encoder now emits repeat-offset sequence codes when a match's distance
  repeats one of the three most-recently-used offsets, instead of always an
  explicit literal offset (#48).
- With a trained dictionary, the encoder now reuses the dictionary's own
  trained FSE tables for sequences ("Repeat" mode) and its trained Huffman
  table for literals ("Treeless"), whenever they cover what a given block
  needs and doing so is smaller than the previous fallback (predefined FSE
  tables, raw literals) — a real, measurable size reduction for
  dictionary-compressed frames, not just a wire-format curiosity (#50, #51).
- `level` (1–22) now governs match-finding search depth: higher levels search
  more candidate matches per position, which can shrink output at the cost of
  more work. Level 19 (`Zstd.DEFAULT_LEVEL`) is byte-identical to every
  earlier release; the encoder still uses one fixed strategy at every level,
  not zstd's other per-level parameters (#52).

### Fixed

- The decoder now validates a frame's Dictionary_ID (RFC 8878 §3.1.1.3)
  against the supplied dictionary's own embedded Dictionary_ID (RFC 8878
  §5), when both are present. Decoding a real libzstd frame (which sets a
  real Dictionary_ID by default when compressing with a proper
  Zstandard-format dictionary) with the wrong `ZstdDictionary` now throws a
  `ZstdException` that clearly names it as a dictionary-ID mismatch, instead
  of a confusing generic corruption error. Frames with no declared
  Dictionary_ID (kzstd's own encoder always emits these) and raw content
  dictionaries (no embedded ID) are unaffected — fully backward compatible.

### Notes

- A dictionary-compressed frame's correctness now depends on the dictionary's
  entropy tables matching what the decoder is seeded with, not just its
  content — decoding with the wrong dictionary was already silently wrong
  before this work (kzstd enforces no `Dictionary_ID`), so this doesn't
  introduce a new failure mode, just makes an existing one marginally more
  sensitive. Use the same trained dictionary bytes on both ends, as always.

## [0.1.2]

Dependency and toolchain refresh, plus CI/quality hardening — no codec or public-API changes.

### Added

- CodeQL and OpenSSF Scorecard scanning (#17).
- A Dokka API docs site, published to GitHub Pages (#18).
- klib ABI validation across every target, not just JVM (#16).
- A remote HTTP build cache plus Konan (Kotlin/Native toolchain) caching in CI (#15).
- A Spotless/detekt quality gate and a dedicated Linux test leg (#28).
- Non-gating Codecov coverage upload, later gated on regression (#24).
- Onboarded to the OSS Community Develocity instance for Build Scans and remote caching
  (#36), then restricted cache writes to trusted events only (#37).
- Funding and issue templates (#22).

### Changed

- Built with Gradle 9.7.0 (was 9.6.1, itself bumped from 9.5.x this cycle) (#26, #40).
- Every third-party GitHub Action is now pinned to a full commit SHA (#25).
- `com.github.luben:zstd-jni` (JVM-only interop test dependency) updated to 1.5.7-13 (#30, #43).
- `com.diffplug.spotless` updated to 8.9.0 (#31); `junit-framework` (JVM test suite) to 6.1.3 (#42).
- Renormalized `gradlew.bat` line endings so a fresh clone is clean on Windows (#33).

### Security

- Pinned yarn `resolution()` floors for the Kotlin/JS test harness, clearing open Dependabot
  alerts (#21). Dev-time only; nothing under `kotlin-js-store/` ships in published artifacts.
- Pinned the Gradle wrapper checksum (#22).

## [0.1.1]

Dependency and toolchain refresh — no codec or public-API changes.

### Changed

- Relicensed source SPDX headers from GPL-3.0-only to GPL-3.0-or-later
  (aligns with the Meshtastic org standard; the LICENSE file is unchanged).
- Built with Kotlin 2.4.10 (was 2.4.0).
- junit-framework (JVM test suite only) updated to 6.1.2.

### Security

- Pinned yarn `resolution()` floors for the Kotlin/JS test harness — ws 8.21.0,
  serialize-javascript 7.0.5, webpack 5.104.1, diff 8.0.3 — clearing the open
  Dependabot alerts. Dev-time only; nothing under `kotlin-js-store/` ships in
  published artifacts.
- Dependabot no longer tries (and fails) to update the Kotlin-managed yarn lock
  under `kotlin-js-store/`; Renovate handles all dependency updates.

## [0.1.0]

Initial release. A standalone, pure-Kotlin multiplatform Zstandard (zstd) codec,
extracted from [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK).

### Added

- One-shot `Zstd.compress` / `Zstd.decompress` over standard zstd frames, with
  dictionary and dictionary-less overloads — interoperable with libzstd in both
  directions.
- A digested `ZstdDictionary(bytes)` that parses entropy tables and indexes its
  content once in its constructor; immutable and safe to share across threads.
- A single public `ZstdException` error type, and a required `maxSize`
  decompression-bomb guard on every decode.
- 13 Kotlin Multiplatform targets: JVM; JS (browser + Node); Wasm/JS; Wasm/WASI;
  and nine native (iOS arm64 / simulator-arm64 / x64, macOS arm64, tvOS arm64 /
  simulator-arm64, Linux x64 / arm64, Windows mingw-x64).
- Zero runtime dependencies (Kotlin standard library only).

### Notes

- No streaming API — one-shot by design.
- `compress` emits a single block per frame, so input is bounded by zstd's 128 KiB
  `Block_Maximum_Size`; larger inputs throw `ZstdException`. `decompress` reads
  multi-block frames from any encoder. Multi-block encoding is planned.
- The `level` parameter is currently a no-op; the encoder uses a single fixed
  greedy/lazy strategy. Frames remain libzstd-compatible regardless.

[Unreleased]: https://github.com/meshtastic/kzstd/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/meshtastic/kzstd/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/meshtastic/kzstd/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/meshtastic/kzstd/releases/tag/v0.1.0
