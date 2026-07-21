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
     * Default value for the `level` argument. NOTE: `level` is currently a NO-OP —
     * the pure-Kotlin encoder uses a single fixed greedy/lazy strategy and does not
     * implement zstd's 1..22 levels. The parameter is accepted for call-site
     * familiarity and forward compatibility; passing a different value does not (yet)
     * change the output.
     */
    public const val DEFAULT_LEVEL: Int = 19

    /** Compress [data] into a standard zstd frame using [dictionary]. */
    @Throws(ZstdException::class)
    public fun compress(data: ByteArray, dictionary: ZstdDictionary, level: Int = DEFAULT_LEVEL): ByteArray =
        wrapFailures("compression", data.size) {
            PureZstdEncoder.encode(data, dictionary.parsed, dictionary.matchIndex, level)
        }

    /** Compress [data] into a standard zstd frame with no dictionary. */
    @Throws(ZstdException::class)
    public fun compress(data: ByteArray, level: Int = DEFAULT_LEVEL): ByteArray =
        compress(data, ZstdDictionary.EMPTY, level)

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
    public fun decompress(frame: ByteArray, maxSize: Int): ByteArray =
        decompress(frame, ZstdDictionary.EMPTY, maxSize)

    /**
     * Run [body], letting a [ZstdException] propagate and wrapping any other
     * (non-[Error]) failure in a [ZstdException] so callers catch one type.
     * `Error` (e.g. `OutOfMemoryError`) is intentionally NOT caught.
     */
    private inline fun wrapFailures(op: String, size: Int, body: () -> ByteArray): ByteArray =
        try {
            body()
        } catch (e: ZstdException) {
            throw e
        } catch (e: Exception) {
            throw ZstdException("zstd $op failed (size=$size): ${e.message ?: e::class.simpleName ?: "unknown"}", e)
        }
}
