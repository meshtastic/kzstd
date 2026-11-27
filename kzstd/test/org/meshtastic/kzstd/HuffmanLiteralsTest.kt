// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Adoption guard for Huffman_Compressed literals (Literals_Block_Type 2) built
 * from a block's OWN histogram — the dictionary-free path, which is what a
 * plain `Zstd.compress(data)` call uses.
 *
 * Round-trips alone would stay green if the cost model silently regressed to
 * Raw literals, so each test asserts the literals type actually chosen, and one
 * pins a ratio ratchet against the pre-entropy-coding baseline.
 */
class HuffmanLiteralsTest {

    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    /** Text whose literals are numerous and skewed — the case Huffman wins. */
    private val prose: ByteArray = (
        "the quick brown fox jumps over the lazy dog while nominal reports stream " +
            "steadily across every monitored link and latency stays within throughput " +
            "targets even when the region degrades to offline for a while. "
        ).encodeToByteArray()

    @Test
    fun skewedLiteralsUseFreshHuffman() {
        val frame = Zstd.compress(prose)
        assertEquals(2, FrameInspector.literalsType(frame), "prose literals were not Huffman-coded")
        assertContentEquals(prose, Zstd.decompress(frame, max))
    }

    /**
     * Ratio ratchet against the RAW-LITERALS baseline: the exact sizes the
     * encoder produced immediately before fresh Huffman literals existed. These
     * are the numbers that prove entropy coding is actually engaged and paying
     * — a mode assertion alone would still pass if the table were built badly.
     * Refresh (never loosen without a reason) if a later, deliberate change
     * legitimately moves them.
     */
    @Test
    fun huffmanLiteralsShrinkOutput() {
        val structuredRun = TestVectors.structured.reduce { a, b -> a + b }
        val structuredSize = Zstd.compress(structuredRun).size
        assertTrue(
            structuredSize < 487,
            "concatenated structured records: $structuredSize bytes, raw-literals baseline was 487",
        )
        val proseSize = Zstd.compress(prose).size
        assertTrue(proseSize < 213, "prose: $proseSize bytes, raw-literals baseline was 213")
    }

    @Test
    fun incompressibleLiteralsStayRaw() {
        // Near-uniform bytes: a Huffman table plus its description costs more
        // than it saves, so the cost model must keep Raw literals.
        val random = TestVectors.corpus.last()
        val frame = Zstd.compress(random)
        assertNotEquals(2, FrameInspector.literalsType(frame), "near-random literals should not be Huffman-coded")
        assertContentEquals(random, Zstd.decompress(frame, max))
    }

    @Test
    fun everyCorpusSampleStillRoundTrips() {
        for (sample in TestVectors.corpus) {
            val frame = Zstd.compress(sample)
            assertContentEquals(sample, Zstd.decompress(frame, max), "size=${sample.size}")
        }
    }

    /**
     * Literal bytes above 128 cannot be described with the direct 4-bit weight
     * form this encoder writes, so such a block must fall back rather than
     * emit an undescribable table.
     */
    @Test
    fun highByteLiteralsFallBackAndStillRoundTrip() {
        val data = ByteArray(600) { i -> (128 + (i * 7) % 100).toByte() }
        val frame = Zstd.compress(data)
        assertNotEquals(2, FrameInspector.literalsType(frame), "high-byte literals cannot use a direct-weight tree")
        assertContentEquals(data, Zstd.decompress(frame, max))
    }
}
