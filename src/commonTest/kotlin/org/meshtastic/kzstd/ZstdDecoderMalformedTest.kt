// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Adversarial malformed-frame hardening (R7): a corrupt, truncated, or hostile
 * frame must surface as a typed [ZstdException] — never a bare index-out-of-bounds,
 * an unbounded allocation, or an infinite loop. Runs on every target.
 */
class ZstdDecoderMalformedTest {

    private val dict = ZstdDictionary(TestVectors.trainedDict)
    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    @Test
    fun emptyAndBadMagicThrowZstdException() {
        assertFailsWith<ZstdException> { Zstd.decompress(ByteArray(0), dict, max) }
        assertFailsWith<ZstdException> { Zstd.decompress(ByteArray(8), dict, max) } // all zeros
        assertFailsWith<ZstdException> { Zstd.decompress("not a zstd frame".encodeToByteArray(), dict, max) }
    }

    @Test
    fun reservedBlockTypeThrowsZstdException() {
        // frame magic + Frame_Header_Descriptor(0) + Window_Descriptor(0) +
        // a 3-byte block header with Block_Type = 3 (reserved): last=1,type=3,size=0
        // => header = 1 | (3 shl 1) = 0x07.
        val frame = byteArrayOf(
            0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(),
            0x00,
            0x00,
            0x07, 0x00, 0x00,
        )
        assertFailsWith<ZstdException> { Zstd.decompress(frame, dict, max) }
    }

    @Test
    fun truncationOnlyEverThrowsZstdException() {
        // Truncate every structured frame at every length. A truncated frame must
        // surface only as ZstdException — never a foreign throwable (a bare
        // IndexOutOfBounds etc.). This asserts the exception TYPE; termination is
        // guaranteed separately by the bounded decode loops.
        for (sample in TestVectors.structured) {
            val frame = Zstd.compress(sample, dict)
            for (len in 0 until frame.size) {
                try {
                    Zstd.decompress(frame.copyOfRange(0, len), dict, max)
                } catch (_: ZstdException) {
                    // expected
                }
            }
        }
    }

    @Test
    fun bitFlipFuzzOnlyEverThrowsZstdException() {
        // Flip every bit of every structured frame; decoding must surface only as
        // ZstdException (or decode to some byte string) — never a foreign throwable.
        for (sample in TestVectors.structured) {
            val base = Zstd.compress(sample, dict)
            for (i in base.indices) {
                for (bit in 0 until 8) {
                    val m = base.copyOf()
                    m[i] = (m[i].toInt() xor (1 shl bit)).toByte()
                    try {
                        Zstd.decompress(m, dict, max)
                    } catch (_: ZstdException) {
                        // expected for most flips
                    }
                }
            }
        }
    }

    @Test
    fun malformedDictionaryOnlyEverThrowsZstdException() {
        // A corrupt-but-magic-prefixed dictionary fed to the ZstdDictionary
        // constructor (the untrusted-parse path) must surface only as ZstdException,
        // never a foreign throwable. Keep the trained dict's magic, then truncate
        // and bit-flip the entropy-table region.
        val good = TestVectors.trainedDict
        for (len in 8..minOf(good.size, 80)) {
            try {
                ZstdDictionary(good.copyOfRange(0, len))
            } catch (_: ZstdException) {
                // expected
            }
        }
        val end = minOf(good.size, 200)
        for (i in 8 until end) {
            for (bit in 0 until 8) {
                val m = good.copyOf()
                m[i] = (m[i].toInt() xor (1 shl bit)).toByte()
                try {
                    ZstdDictionary(m)
                } catch (_: ZstdException) {
                    // expected
                }
            }
        }
    }
}
