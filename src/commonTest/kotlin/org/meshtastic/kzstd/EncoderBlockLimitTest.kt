// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * The encoder emits one zstd block per frame, so its input is bounded by zstd's
 * 128 KiB `Block_Maximum_Size` (RFC 8878 §3.1.1.2). Inputs at the limit round-trip;
 * larger inputs must be REJECTED with a typed [ZstdException] rather than silently
 * producing a frame that neither libzstd nor kzstd can decode.
 */
class EncoderBlockLimitTest {

    private val blockMax = 1 shl 17 // 128 KiB

    @Test
    fun acceptsInputUpToTheBlockLimit() {
        // Compressible (26-byte cycle) so the frame stays tiny and the test is fast.
        val atLimit = ByteArray(blockMax) { (('a'.code) + (it % 26)).toByte() }
        val back = Zstd.decompress(Zstd.compress(atLimit), maxSize = blockMax + 16)
        assertContentEquals(atLimit, back)
    }

    @Test
    fun rejectsInputOverTheBlockLimit() {
        val overLimit = ByteArray(blockMax + 1)
        assertFailsWith<ZstdException> { Zstd.compress(overLimit) }
        assertFailsWith<ZstdException> { Zstd.compress(overLimit, ZstdDictionary.EMPTY) }
    }
}
