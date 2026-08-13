# Changelog

All notable changes to kzstd are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/meshtastic/kzstd/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/meshtastic/kzstd/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/meshtastic/kzstd/releases/tag/v0.1.0
