# Changelog

All notable changes to kzstd are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Encoder-side parity work closing several gaps against the libzstd/RFC 8878
spec — the 128 KiB single-block input limit lifted, real ratio improvements
for both dictionary-compressed and dictionary-free frames, plus
dictionary-ID and content-checksum validation. No wire format or
public-API changes, except where noted below.

### Fixed

- The decoder now validates a frame's Content_Checksum when
  `Content_Checksum_Flag` is set: it reads the trailing 4-byte XXH64
  checksum and throws `ZstdException` on a mismatch against the decoded
  content, for ANY conformant frame — not just kzstd's own — so a real
  `libzstd`-produced frame (checksums are on by default in the `zstd` CLI)
  is no longer accepted with silently-corrupted content.
- The decoder now validates a frame's declared Dictionary_ID against the
  supplied dictionary's own embedded ID (when both are present), throwing a
  clear `ZstdException` on mismatch instead of a generic corruption error.

### Added

- `Zstd.compress` takes an opt-in `checksum: Boolean = false` parameter; when
  true, the encoder sets `Content_Checksum_Flag` and appends the XXH64
  checksum of the input. Defaults to false, so every existing call site's
  frame bytes are unchanged.
- `Zstd.compress` now accepts input up to 128 MiB. It cuts the input into
  128 KiB (`Block_Maximum_Size`) chunks and emits them as one multi-block
  frame, `Last_Block` set on the final block only; anything larger than
  128 KiB used to be rejected with a `ZstdException`. `Zstd.decompress` has
  always read multi-block frames from any encoder, and real libzstd reads
  these. 3 MB of synthetic JSON telemetry compresses to 556,657 bytes across
  24 blocks — between libzstd's level 3 (579,374) and level 19 (362,967).

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
- Without a dictionary — the plain `Zstd.compress(data)` call — the encoder now
  entropy-codes each block from the block's OWN data, where before it could
  only emit raw literals and the spec's predefined FSE distributions: Huffman
  literals built from the block's byte histogram (`Literals_Block_Type` 2), and
  FSE tables for the literal-length / offset / match-length streams normalized
  from the block's own code counts (`Symbol_Compression_Mode` 2). Measured:
  ~7.8 KB of synthetic JSON telemetry records 2007 → 1521 bytes, 887 bytes of
  concatenated structured records 487 → 425, a 208-byte prose sample 213 → 190.
- The encoder now also emits the RLE forms the decoder has always read:
  `RLE_Block` for a constant input (a 1500-byte run of one byte, 17 → 10
  bytes), RLE literals when every literal is the same byte, and RLE sequence
  tables when a stream's every code is the same (a sample of 26 byte-runs,
  85 → 42 bytes).
- Every per-block encoding choice — the literals section and each of the three
  sequence streams independently — is now made by measuring every valid
  alternative and taking the smallest, so a form is used only when it actually
  wins. Ties keep the previous behaviour, and dictionary-compressed frames come
  out the same size as before.
- Entropy tables and the three repeat offsets are now carried from block to
  block within a frame, which is what the format means by them: "Repeat"
  sequence tables and "Treeless" literals name the PREVIOUS block's tables, and
  the dictionary's only for a frame's first block. A later block therefore
  reuses a table for nothing instead of describing its own, and a `Raw` or
  `RLE` block describes nothing and so leaves the state untouched — the block
  after a stretch of incompressible data still reaches the dictionary's own
  tables. A 128 KiB noise block followed by a dictionary-trained sample
  compresses that sample's block to 46 bytes rather than 54.
- `level` (1–22) now governs match-finding search depth: higher levels search
  more candidate matches per position, which can shrink output at the cost of
  more work. Level 19 (`Zstd.DEFAULT_LEVEL`) maps to exactly the search depth
  the encoder always used, so the mapping itself changes no output; the encoder
  still uses one fixed strategy at every level, not zstd's other per-level
  parameters (#52).

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

- Blocks are compressed independently: a match never reaches back into an
  earlier block's output, only into this block and the dictionary. Large
  inputs therefore compress less well than a windowed encoder would manage —
  and combined with the 1023-byte literals cap below, a full 128 KiB block
  keeps raw literals and takes its ratio from the sequence tables alone. A
  windowed, cross-block matcher is the follow-up.
- Huffman-coded literals stay single-stream, so they apply to at most 1023
  bytes of literals per block, and their tree description uses the direct
  4-bit weight form, so a block containing a literal byte above 128 falls back
  to raw literals. FSE-compressed weight descriptions and the 4-stream literals
  layout would lift those limits and are not implemented; neither affects
  decoding, which reads both.
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
