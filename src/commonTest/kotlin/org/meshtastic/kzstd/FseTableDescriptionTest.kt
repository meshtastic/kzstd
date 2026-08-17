// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.FSE_MIN_TABLELOG
import org.meshtastic.kzstd.internal.ForwardByteReader
import org.meshtastic.kzstd.internal.FseState
import org.meshtastic.kzstd.internal.FseTable
import org.meshtastic.kzstd.internal.LITERAL_LENGTH_MAX_LOG
import org.meshtastic.kzstd.internal.LITERAL_LENGTH_MAX_SYMBOL
import org.meshtastic.kzstd.internal.MATCH_LENGTH_MAX_LOG
import org.meshtastic.kzstd.internal.MATCH_LENGTH_MAX_SYMBOL
import org.meshtastic.kzstd.internal.OFFSET_MAX_LOG
import org.meshtastic.kzstd.internal.OFFSET_MAX_SYMBOL
import org.meshtastic.kzstd.internal.ReverseBitReader
import org.meshtastic.kzstd.internal.ReverseBitWriter
import org.meshtastic.kzstd.internal.buildFreshFseTable
import org.meshtastic.kzstd.internal.normalizeFseCounts
import org.meshtastic.kzstd.internal.parseFseTable
import org.meshtastic.kzstd.internal.writeFseTableDescription
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FSE table description writer (RFC 8878 §4.1.1) — the exact inverse of the
 * decoder's [parseFseTable] — plus the count normalization that feeds it.
 *
 * These are bit-level round trips deliberately kept away from the frame format:
 * a description that is off by one bit produces a table that decodes every
 * sequence in the block to nonsense, and only checking writer against reader
 * localises that. The reader here is the SAME routine that already parses
 * libzstd's and trained dictionaries' tables, so agreeing with it is agreeing
 * with the spec.
 */
class FseTableDescriptionTest {

    @Test
    fun normalizationSumsToTableSizeAndKeepsEverySymbol() {
        for (counts in countSets()) {
            for (tableLog in FSE_MIN_TABLELOG..9) {
                val maxSymbol = counts.size - 1
                val distinct = counts.count { it > 0 }
                if (distinct > (1 shl tableLog)) continue
                val norm = normalizeFseCounts(counts, maxSymbol, tableLog)
                assertEquals(1 shl tableLog, norm.sum(), "normalized counts must fill the table exactly")
                for (s in 0..maxSymbol) {
                    if (counts[s] > 0) {
                        assertTrue(norm[s] >= 1, "symbol $s occurs but was normalized to ${norm[s]}")
                    } else {
                        assertEquals(0, norm[s], "absent symbol $s was given probability")
                    }
                }
            }
        }
    }

    @Test
    fun descriptionRoundTripsThroughTheDecodersParser() {
        for (counts in countSets()) {
            for (tableLog in FSE_MIN_TABLELOG..9) {
                val maxSymbol = counts.size - 1
                if (counts.count { it > 0 } > (1 shl tableLog)) continue
                val norm = normalizeFseCounts(counts, maxSymbol, tableLog)
                val description = writeFseTableDescription(norm, maxSymbol, tableLog)

                val reader = ForwardByteReader(description, 0, description.size)
                val parsed = parseFseTable(reader, maxLog = 9, maxSymbol = maxSymbol)
                assertEquals(
                    description.size,
                    reader.pos,
                    "the parser consumed ${reader.pos} of ${description.size} description bytes",
                )
                assertTablesEqual(FseTable.build(norm, maxSymbol, tableLog), parsed, "tableLog=$tableLog")
            }
        }
    }

    /**
     * End-to-end for one symbol stream, in exactly the shape the sequences
     * section uses: build a table from the stream's own code counts, write its
     * description, encode the stream backwards, then parse the description and
     * decode the stream forwards.
     */
    @Test
    fun freshTablesEncodeStreamsTheDecoderReadsBack() {
        for ((codes, maxSymbol, maxLog) in codeStreams()) {
            val fresh = assertNotNull(buildFreshFseTable(codes, maxSymbol, maxLog), "no table for ${codes.size} codes")

            val bw = ReverseBitWriter()
            var state = fresh.encoder.initialState(codes[codes.size - 1])
            for (i in codes.size - 2 downTo 0) state = fresh.encoder.encode(bw, state, codes[i])
            fresh.encoder.flushState(bw, state)
            val stream = bw.finish()

            val reader = ForwardByteReader(fresh.description, 0, fresh.description.size)
            val table = parseFseTable(reader, maxLog, maxSymbol)
            val br = ReverseBitReader(stream, 0, stream.size)
            val fseState = FseState(table)
            fseState.init(br)
            for (i in codes.indices) {
                assertEquals(codes[i], fseState.symbol(), "code $i of ${codes.size}")
                if (i < codes.size - 1) fseState.update(br)
            }
        }
    }

    @Test
    fun singleCodeStreamsHaveNoFreshTable() {
        // One distinct code is the RLE case (mode 1), which is always smaller.
        assertNull(buildFreshFseTable(IntArray(20) { 4 }, LITERAL_LENGTH_MAX_SYMBOL, LITERAL_LENGTH_MAX_LOG))
        assertNull(buildFreshFseTable(IntArray(0), LITERAL_LENGTH_MAX_SYMBOL, LITERAL_LENGTH_MAX_LOG))
    }

    /** Count distributions covering the shapes the description encoding branches on. */
    private fun countSets(): List<IntArray> = listOf(
        // Two symbols, adjacent.
        IntArray(36).also {
            it[0] = 7
            it[1] = 3
        },
        // Two symbols at the extremes: one long zero run, longer than the
        // 3-at-a-time run encoding's group size.
        IntArray(36).also {
            it[0] = 1
            it[35] = 1
        },
        // Zero runs of exactly 3 and 4 (the run encoding's boundary).
        IntArray(36).also {
            it[0] = 5
            it[4] = 5
            it[9] = 2
        },
        // Dense and heavily skewed: one symbol takes nearly the whole table.
        IntArray(36).also {
            it[0] = 1000
            for (s in 1 until 36) it[s] = 1
        },
        // Dense and flat.
        IntArray(36).also { for (s in 0 until 36) it[s] = 4 },
        // Match-length shaped: 53 symbols, sparse at the top.
        IntArray(53).also {
            it[0] = 40
            it[1] = 20
            it[16] = 3
            it[52] = 1
        },
        // Offset shaped.
        IntArray(32).also {
            it[0] = 30
            it[3] = 12
            it[7] = 4
            it[31] = 1
        },
    )

    private fun codeStreams(): List<Triple<IntArray, Int, Int>> = listOf(
        Triple(
            IntArray(200) { i -> (i * 7) % 20 },
            LITERAL_LENGTH_MAX_SYMBOL,
            LITERAL_LENGTH_MAX_LOG,
        ),
        Triple(
            IntArray(500) { i ->
                if (i % 5 == 0) {
                    1
                } else if (i % 3 == 0) {
                    9
                } else {
                    0
                }
            },
            MATCH_LENGTH_MAX_SYMBOL,
            MATCH_LENGTH_MAX_LOG,
        ),
        Triple(
            IntArray(64) { i -> if (i % 8 == 0) 31 else i % 4 },
            OFFSET_MAX_SYMBOL,
            OFFSET_MAX_LOG,
        ),
        // Only two distinct codes, wildly unbalanced.
        Triple(
            IntArray(300) { i -> if (i == 150) 35 else 0 },
            LITERAL_LENGTH_MAX_SYMBOL,
            LITERAL_LENGTH_MAX_LOG,
        ),
    )

    private fun assertTablesEqual(expected: FseTable, actual: FseTable, message: String) {
        assertEquals(expected.tableLog, actual.tableLog, "$message: tableLog")
        assertContentEquals(expected.symbol, actual.symbol, "$message: symbols")
        assertContentEquals(expected.nbBits, actual.nbBits, "$message: nbBits")
        assertContentEquals(expected.newState, actual.newState, "$message: newState")
    }
}
