// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.MatchIndex
import org.meshtastic.kzstd.internal.ParsedDictionary

/**
 * A digested zstd dictionary, ready to compress and decompress with.
 *
 * Construct one from raw dictionary [bytes] and reuse it for as many
 * [Zstd.compress] / [Zstd.decompress] calls as you like: the expensive work —
 * parsing the dictionary's entropy tables and indexing its content for the
 * matcher — happens ONCE, in this constructor. This mirrors the digested-dictionary
 * objects every mature zstd binding exposes (libzstd `ZSTD_CDict`/`ZSTD_DDict`,
 * zstd-jni `ZstdDictCompress`/`ZstdDictDecompress`, Rust's `EncoderDictionary`,
 * python-zstandard's `ZstdCompressionDict`).
 *
 * [bytes] may be a trained dictionary (produced by `zstd --train` / `ZDICT`) or
 * any raw byte content used as a prefix; an empty array means "no dictionary"
 * (see [EMPTY]). The bytes are copied defensively, so a `ZstdDictionary` is fully
 * immutable after construction and safe to share across threads — concurrent
 * compress/decompress calls with the same instance need no synchronization.
 */
public class ZstdDictionary @Throws(ZstdException::class) constructor(bytes: ByteArray) {
    internal val parsed: ParsedDictionary
    internal val matchIndex: MatchIndex

    init {
        // Parsing untrusted dictionary bytes is part of the public contract, so
        // normalize any failure to ZstdException (the engine throws it directly for
        // recognized malformations; this also catches a stray bare exception). Error
        // (e.g. OutOfMemoryError) is intentionally NOT caught.
        try {
            parsed = ParsedDictionary.parse(bytes.copyOf())
            matchIndex = MatchIndex.build(parsed.content)
        } catch (e: ZstdException) {
            throw e
        } catch (e: Exception) {
            throw ZstdException(
                "invalid zstd dictionary (size=${bytes.size}): ${e.message ?: e::class.simpleName ?: "unknown"}",
                e,
            )
        }
    }

    public companion object {
        /** The empty dictionary — backs the dictionary-less [Zstd] overloads. */
        public val EMPTY: ZstdDictionary = ZstdDictionary(ByteArray(0))
    }
}
