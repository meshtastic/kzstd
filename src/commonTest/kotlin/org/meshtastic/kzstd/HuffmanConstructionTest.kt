// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.ForwardByteReader
import org.meshtastic.kzstd.internal.HuffmanEncTable
import org.meshtastic.kzstd.internal.HuffmanTable
import org.meshtastic.kzstd.internal.MAX_LITERAL_CODE_BITS
import org.meshtastic.kzstd.internal.ReverseBitReader
import org.meshtastic.kzstd.internal.ReverseBitWriter
import org.meshtastic.kzstd.internal.buildLiteralsHuffman
import org.meshtastic.kzstd.internal.parseHuffmanTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Builds a canonical Huffman table from a block's own byte histogram (RFC 8878
 * §4.2.1) and proves the result is self-inverse: the Huffman_Tree_Description
 * kzstd writes, parsed back by kzstd's own [parseHuffmanTable] (the routine
 * that already reads libzstd's descriptions), must rebuild the very table the
 * encoder used.
 *
 * That round-trip is the load-bearing check — a table that is internally
 * consistent but described wrongly on the wire produces literals that decode to
 * garbage everywhere else, and only the description round-trip localises it.
 */
class HuffmanConstructionTest {

    @Test
    fun twoEquallyLikelySymbolsGetOneBitCodes() {
        val hist = IntArray(256)
        hist['a'.code] = 4
        hist['b'.code] = 4
        val built = assertNotNull(buildLiteralsHuffman(hist))
        assertEquals(1, built.encoder.bitLength('a'.code), "'a' code length")
        assertEquals(1, built.encoder.bitLength('b'.code), "'b' code length")
    }

    @Test
    fun skewedHistogramGivesTheFrequentSymbolTheShortestCode() {
        val hist = IntArray(256)
        hist['a'.code] = 100
        hist['b'.code] = 10
        hist['c'.code] = 5
        hist['d'.code] = 1
        val built = assertNotNull(buildLiteralsHuffman(hist))
        val a = built.encoder.bitLength('a'.code)
        assertEquals(1, a, "the dominant symbol should get a 1-bit code")
        assertTrue(a < built.encoder.bitLength('b'.code), "'a' must be shorter than 'b'")
        assertTrue(built.encoder.bitLength('b'.code) <= built.encoder.bitLength('c'.code), "'b' <= 'c'")
        assertTrue(built.encoder.bitLength('c'.code) <= built.encoder.bitLength('d'.code), "'c' <= 'd'")
    }

    @Test
    fun fewerThanTwoDistinctSymbolsHaveNoHuffmanTable() {
        assertNull(buildLiteralsHuffman(IntArray(256)), "empty histogram")
        val one = IntArray(256)
        one['x'.code] = 9
        assertNull(buildLiteralsHuffman(one), "a single-symbol alphabet is the RLE case, not a Huffman one")
    }

    @Test
    fun highByteValuesFallBackToNoTable() {
        // The direct 4-bit weight description can carry at most 128 explicit
        // weights, so a literal byte above 128 cannot be described this way.
        val hist = IntArray(256)
        hist[1] = 5
        hist[200] = 5
        assertNull(buildLiteralsHuffman(hist), "byte 200 exceeds the direct-weight description's reach")
    }

    /**
     * A Fibonacci-shaped histogram is the classic worst case: an unconstrained
     * Huffman tree over it is a degenerate chain far deeper than the format's
     * limit, so this exercises the length-limiting repair, which must still
     * leave a COMPLETE code (an incomplete one makes the implied final weight
     * come up wrong and the description unparseable).
     */
    @Test
    fun deepHistogramIsLengthLimitedAndStillRoundTrips() {
        val hist = IntArray(256)
        var a = 1L
        var b = 1L
        for (s in 0 until 40) {
            hist[s] = a.toInt()
            val next = a + b
            a = b
            b = next
        }
        val built = assertNotNull(buildLiteralsHuffman(hist))
        for (s in 0 until 40) {
            assertTrue(
                built.encoder.bitLength(s) in 1..MAX_LITERAL_CODE_BITS,
                "symbol $s has out-of-range code length ${built.encoder.bitLength(s)}",
            )
        }
        assertDescriptionRoundTrips(hist, built.description, built.encoder)
    }

    @Test
    fun descriptionRoundTripsForAssortedHistograms() {
        for (hist in histograms()) {
            val built = assertNotNull(buildLiteralsHuffman(hist))
            assertDescriptionRoundTrips(hist, built.description, built.encoder)
        }
    }

    private fun histograms(): List<IntArray> = listOf(
        // Two symbols at the extremes of the describable range.
        IntArray(256).also {
            it[0] = 1
            it[128] = 1
        },
        // Dense low alphabet.
        IntArray(256).also { for (s in 0 until 96) it[s] = s + 1 },
        // Sparse: long runs of absent symbols between present ones.
        IntArray(256).also { for (s in 0 until 128 step 17) it[s] = 100 - s },
        // Printable ASCII with an English-ish skew.
        IntArray(256).also {
            it[' '.code] = 300
            it['e'.code] = 200
            it['t'.code] = 150
            it['a'.code] = 90
            it['z'.code] = 1
            it['q'.code] = 1
            it['\n'.code] = 7
        },
    )

    /**
     * Parse [description] exactly as the decoder would, then check the rebuilt
     * table assigns every symbol the same code as [encoder] — by encoding one
     * of each present symbol and decoding it back through the parsed table.
     */
    private fun assertDescriptionRoundTrips(hist: IntArray, description: ByteArray, encoder: HuffmanEncTable) {
        val reader = ForwardByteReader(description, 0, description.size)
        val parsed = parseHuffmanTable(reader)
        assertEquals(description.size, reader.pos, "description length consumed")

        val symbols = (0..255).filter { hist[it] > 0 }
        val bw = ReverseBitWriter()
        // Written in reverse so the decoder reads them in `symbols` order.
        for (i in symbols.indices.reversed()) encoder.encode(bw, symbols[i])
        val stream = bw.finish()

        val br = ReverseBitReader(stream, 0, stream.size)
        for (s in symbols) {
            assertEquals(s, parsed.decode(br), "symbol $s did not survive the description round trip")
        }
        assertRebuiltTableAgrees(parsed, encoder, symbols)
    }

    private fun assertRebuiltTableAgrees(parsed: HuffmanTable, encoder: HuffmanEncTable, symbols: List<Int>) {
        val rebuilt = HuffmanEncTable.fromDecodeTable(parsed)
        for (s in symbols) {
            assertEquals(encoder.bitLength(s), rebuilt.bitLength(s), "code length for symbol $s")
        }
    }
}
