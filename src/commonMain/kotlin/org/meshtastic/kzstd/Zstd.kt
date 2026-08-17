// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.PureZstdDecoder
import org.meshtastic.kzstd.internal.PureZstdEncoder

/**
 * One-shot zstd compression and decompression over complete, standard zstd
 * frames (magic number included), interoperable with libzstd in both directions.
 *
 * The API is deliberately one-shot only — there is NO streaming interface. Each
 * call compresses or decompresses a whole frame from a single byte array, with
 * no cross-call state, so every frame is independently decodable (what packet /
 * mesh transports need).
 *
 * [compress] emits a single zstd block per frame, so its input is bounded by zstd's
 * 128 KiB `Block_Maximum_Size`; a larger input throws [ZstdException] (multi-block
 * encoding is a planned addition). [decompress] reads any conformant frame,
 * including multi-block frames produced by other encoders.
 *
 * Pass a [ZstdDictionary] for dictionary compression; the dictionary-less
 * overloads operate on plain frames.
 */
public object Zstd {

    /**
     * Default value for the `level` argument. NOTE: `level` governs ONLY
     * match-finding effort (how many candidate distances the matcher examines
     * per position) -- higher values can find longer/cheaper matches at the
     * cost of more work, and this DOES change compressed output size (though
     * never correctness: every level produces a valid, decodable frame). It
     * does not implement zstd's other 21 levels' distinct strategies or
     * parameters (windowLog, targetLength, etc.) -- the pure-Kotlin encoder
     * uses a single fixed greedy/lazy strategy at every level -- and it never
     * gates entropy-table choice (dict-entropy reuse is unconditional
     * whenever the dictionary's tables are usable, independent of `level`).
     */
    public const val DEFAULT_LEVEL: Int = 19

    /**
     * Compress [data] into a standard zstd frame using [dictionary].
     *
     * [checksum], when true, sets Content_Checksum_Flag and appends a
     * trailing 4-byte XXH64 checksum of [data] (RFC 8878 §3.1.1) that
     * [decompress] (and any conformant decoder) validates. Defaults to false
     * — existing callers' frame bytes are unchanged.
     */
    @Throws(ZstdException::class)
    public fun compress(
        data: ByteArray,
        dictionary: ZstdDictionary,
        level: Int = DEFAULT_LEVEL,
        checksum: Boolean = false,
    ): ByteArray = wrapFailures("compression", data.size) {
        PureZstdEncoder.encode(data, dictionary.parsed, dictionary.matchIndex, level, checksum)
    }

    /**
     * Compress [data] into a standard zstd frame with no dictionary. See
     * [compress] (dictionary overload) for [checksum].
     */
    @Throws(ZstdException::class)
    public fun compress(data: ByteArray, level: Int = DEFAULT_LEVEL, checksum: Boolean = false): ByteArray =
        compress(data, ZstdDictionary.EMPTY, level, checksum)

    /**
     * Decompress a standard zstd [frame] using [dictionary], rejecting output
     * larger than [maxSize] bytes (a decompression-bomb guard — always required).
     */
    @Throws(ZstdException::class)
    public fun decompress(frame: ByteArray, dictionary: ZstdDictionary, maxSize: Int): ByteArray =
        wrapFailures("decompression", frame.size) {
            PureZstdDecoder.decode(frame, dictionary.parsed, maxSize)
        }

    /** Decompress a standard zstd [frame] with no dictionary. */
    @Throws(ZstdException::class)
    public fun decompress(frame: ByteArray, maxSize: Int): ByteArray = decompress(frame, ZstdDictionary.EMPTY, maxSize)

    /**
     * Run [body], letting a [ZstdException] propagate and wrapping any other
     * (non-[Error]) failure in a [ZstdException] so callers catch one type.
     * `Error` (e.g. `OutOfMemoryError`) is intentionally NOT caught.
     */
    private inline fun wrapFailures(op: String, size: Int, body: () -> ByteArray): ByteArray = try {
        body()
    } catch (e: ZstdException) {
        throw e
    } catch (e: Exception) {
        throw ZstdException("zstd $op failed (size=$size): ${e.message ?: e::class.simpleName ?: "unknown"}", e)
    }
}
