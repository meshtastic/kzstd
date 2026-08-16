// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.FseEncTable
import org.meshtastic.kzstd.internal.FseTable
import org.meshtastic.kzstd.internal.HuffmanEncTable
import org.meshtastic.kzstd.internal.LL_DEFAULT_DISTRIBUTION
import org.meshtastic.kzstd.internal.LL_DEFAULT_LOG
import org.meshtastic.kzstd.internal.ML_DEFAULT_DISTRIBUTION
import org.meshtastic.kzstd.internal.ML_DEFAULT_LOG
import org.meshtastic.kzstd.internal.OF_DEFAULT_DISTRIBUTION
import org.meshtastic.kzstd.internal.OF_DEFAULT_LOG
import org.meshtastic.kzstd.internal.ParsedDictionary
import org.meshtastic.kzstd.internal.ReverseBitReader
import org.meshtastic.kzstd.internal.ReverseBitWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [FseEncTable.fromDecodeTable] and [HuffmanEncTable.fromDecodeTable] —
 * the two "derive an encode table from an already-built decode table" entry
 * points that let the encoder later reuse a dictionary's trained entropy tables
 * without re-deriving them from a normalized-count distribution.
 *
 * Pure table-math: no frame format is touched here, so these carry no risk to
 * [org.meshtastic.kzstd.internal.ZstdEncoder]'s current output.
 */
class EncodeTableDerivationTest {

    @Test
    fun fseFromDecodeTableMatchesBuildForOffsetDistribution() {
        assertEncodesIdentically(OF_DEFAULT_DISTRIBUTION, OF_DEFAULT_DISTRIBUTION.size - 1, OF_DEFAULT_LOG)
    }

    @Test
    fun fseFromDecodeTableMatchesBuildForMatchLengthDistribution() {
        assertEncodesIdentically(ML_DEFAULT_DISTRIBUTION, ML_DEFAULT_DISTRIBUTION.size - 1, ML_DEFAULT_LOG)
    }

    @Test
    fun fseFromDecodeTableMatchesBuildForLiteralLengthDistribution() {
        assertEncodesIdentically(LL_DEFAULT_DISTRIBUTION, LL_DEFAULT_DISTRIBUTION.size - 1, LL_DEFAULT_LOG)
    }

    /**
     * Encodes the same symbol sequence with both [FseEncTable.build] (builds its
     * own decode table internally) and [FseEncTable.fromDecodeTable] (given a
     * separately-built decode table for the same distribution), and asserts the
     * resulting bitstreams are byte-identical.
     */
    private fun assertEncodesIdentically(distribution: IntArray, maxSymbol: Int, tableLog: Int) {
        val viaBuild = FseEncTable.build(distribution, maxSymbol, tableLog)
        val decode = FseTable.build(distribution, maxSymbol, tableLog)
        val viaDecodeTable = FseEncTable.fromDecodeTable(decode, maxSymbol)

        // Exercise every symbol the distribution actually assigns a nonzero
        // probability to, in both ascending and descending symbol order, so any
        // asymmetry in how the two tables partition decode-states would show up.
        val symbols = (0..maxSymbol).filter { distribution[it] != 0 }
        val sequence = symbols + symbols.reversed()

        val bw1 = ReverseBitWriter()
        var st1 = viaBuild.initialState(sequence.last())
        for (i in sequence.size - 2 downTo 0) st1 = viaBuild.encode(bw1, st1, sequence[i])
        viaBuild.flushState(bw1, st1)
        val out1 = bw1.finish()

        val bw2 = ReverseBitWriter()
        var st2 = viaDecodeTable.initialState(sequence.last())
        for (i in sequence.size - 2 downTo 0) st2 = viaDecodeTable.encode(bw2, st2, sequence[i])
        viaDecodeTable.flushState(bw2, st2)
        val out2 = bw2.finish()

        assertEquals(out1.toList(), out2.toList(), "fromDecodeTable must encode byte-identically to build()")
    }

    @Test
    fun huffmanEncTableRoundTripsEveryDictSymbolThroughTheExistingDecoder() {
        val dict = ParsedDictionary.parse(TestVectors.trainedDict)
        val huffman = dict.literalsHuffman
        assertTrue(huffman != null, "committed test dict must carry a trained Huffman table")

        val encTable = HuffmanEncTable.fromDecodeTable(huffman)
        val coveredSymbols = (0..255).filter { encTable.isCovered(it) }
        assertTrue(coveredSymbols.isNotEmpty(), "derived encode table must cover at least one symbol")

        for (symbol in coveredSymbols) {
            val bw = ReverseBitWriter()
            encTable.encode(bw, symbol)
            val bytes = bw.finish()
            val br = ReverseBitReader(bytes, 0, bytes.size)
            val decoded = huffman.decode(br)
            assertEquals(symbol, decoded, "symbol $symbol did not round-trip through the existing decoder")
        }
    }
}
