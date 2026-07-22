# kzstd

[![Maven Central](https://img.shields.io/maven-central/v/org.meshtastic/kzstd)](https://central.sonatype.com/artifact/org.meshtastic/kzstd)
[![CI](https://github.com/meshtastic/kzstd/actions/workflows/ci.yml/badge.svg)](https://github.com/meshtastic/kzstd/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/meshtastic/kzstd/graph/badge.svg)](https://codecov.io/gh/meshtastic/kzstd)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org)

A pure-Kotlin, multiplatform [Zstandard](https://facebook.github.io/zstd/) (zstd)
codec with dictionary support. It produces and reads **standard zstd frames** that
interoperate with libzstd in both directions, and it has **zero runtime
dependencies** — just the Kotlin standard library, on every target.

kzstd was extracted from [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK),
where it replaced three native binding stacks — `zstd-jni` on the JVM, a per-target
`libzstd` cinterop on Kotlin/Native, and `@bokuweb/zstd-wasm` on JS/Wasm — with one
implementation that compiles everywhere Kotlin does.

## Targets

JVM · JS (browser + Node) · Wasm/JS · Wasm/WASI · and nine Kotlin/Native targets:
iOS (arm64, simulator-arm64, x64), macOS (arm64), tvOS (arm64, simulator-arm64),
Linux (x64, arm64), and Windows (mingw-x64).

## Install

```kotlin
// Maven Central
implementation("org.meshtastic:kzstd:0.1.1")
```

## Usage

```kotlin
import org.meshtastic.kzstd.Zstd
import org.meshtastic.kzstd.ZstdDictionary
import org.meshtastic.kzstd.ZstdException

// Without a dictionary
val frame = Zstd.compress(data)
val original = Zstd.decompress(frame, maxSize = 64 * 1024)

// With a dictionary — digest it once, reuse it everywhere
val dict = ZstdDictionary(dictionaryBytes)   // parses tables + indexes content once
val small = Zstd.compress(data, dict)
val back = Zstd.decompress(small, dict, maxSize = 64 * 1024)
```

- **`ZstdDictionary(bytes)`** digests a dictionary once in its constructor (parsing
  its entropy tables and indexing its content) and is immutable afterward, so a
  single instance is safe to share across threads and cheap to reuse. `bytes` may
  be a trained dictionary (`zstd --train` / `ZDICT`) or any raw byte prefix.
- **`maxSize`** on `decompress` is a required decompression-bomb guard: decoding
  stops and throws if the output would exceed it.
- **Failures** surface as a single `ZstdException`.

## Deviations and current limits

- **No streaming.** The API is one-shot only — no `InputStream`/`OutputStream`
  interface; each call handles a whole frame from one byte array, with no cross-call
  state, so every frame is independently decodable (what packet and mesh transports
  need).
- **`level` is currently a no-op.** The encoder uses a single fixed greedy/lazy
  strategy rather than zstd's 1–22 levels. The `level` parameter is accepted for
  call-site familiarity and forward compatibility but does not (yet) change the
  output. Frames remain fully libzstd-compatible.
- **Single block per frame (≤ 128 KiB input).** `Zstd.compress` emits one zstd block,
  so its input is bounded by zstd's 128 KiB `Block_Maximum_Size`; a larger input
  throws `ZstdException`. (`Zstd.decompress` reads multi-block frames from any
  encoder.) Multi-block encoding to lift the cap is planned.

## Interoperability

kzstd reads frames produced by libzstd (including dictionary-compressed frames
that use the dictionary's Huffman/FSE entropy tables), and libzstd reads frames
produced by kzstd. The test suite cross-checks both directions against
[zstd-jni](https://github.com/luben/zstd-jni) (a JVM-test-only oracle, never a
runtime dependency).

## Building & testing

```bash
./gradlew build         # compile every target, run tests, check the API baseline
./gradlew jvmTest       # JVM tests only (includes the libzstd interop oracle)
./gradlew apiDump       # refresh the binary-compatibility API baseline
```

The test dictionary (`src/commonTest`'s `TestVectors`) is a genuinely trained zstd
dictionary regenerated reproducibly by `scripts/train_test_dict.py` (requires the
`zstd` CLI) — its content is generic structured JSON, not domain data.

## Contributing

Contributions are welcome. See [CLAUDE.md](CLAUDE.md) for the architecture, build
and test commands, and the design invariants, and [CHANGELOG.md](CHANGELOG.md) for
release notes. Run `./gradlew build` (JDK 21) before opening a PR, and refresh the
binary-compatibility baseline with `./gradlew apiDump` after any public-API change.

## License

GPL-3.0. See [LICENSE](LICENSE).
