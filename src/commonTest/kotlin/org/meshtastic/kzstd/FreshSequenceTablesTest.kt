// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adoption guard for FSE_Compressed sequence tables (Symbol_Compression_Mode 2)
 * built from a block's OWN code counts — the dictionary-free path.
 *
 * A block with enough sequences must actually choose them: the cost model
 * comparing exact bits is only useful if a fresh table ever wins, and a
 * regression to always-Predefined would leave every round-trip test green.
 */
class FreshSequenceTablesTest {

    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    @Test
    fun sequenceHeavyBlockUsesFreshTablesForEveryStream() {
        val frame = Zstd.compress(TestVectors.logRecords)
        val (llMode, ofMode, mlMode) = FrameInspector.sequenceModes(frame)!!
        assertEquals(2, llMode, "literal-length stream did not use a fresh FSE table")
        assertEquals(2, ofMode, "offset stream did not use a fresh FSE table")
        assertEquals(2, mlMode, "match-length stream did not use a fresh FSE table")
        assertContentEquals(TestVectors.logRecords, Zstd.decompress(frame, max))
    }

    @Test
    fun freshTablesShrinkOutput() {
        // Baselines measured on the encoder as it stood before this work:
        // predefined FSE tables and raw literals only. Refresh (never loosen
        // without a reason) if a later, deliberate change moves them.
        val size = Zstd.compress(TestVectors.logRecords).size
        assertTrue(size < 2007, "log records: $size bytes, pre-entropy-coding baseline was 2007")
    }

    /**
     * A short block cannot repay a table description, so it must keep the
     * Predefined tables — the cost model has to work in both directions.
     */
    @Test
    fun shortBlocksKeepPredefinedTables() {
        val frame = Zstd.compress("hello hello hello world".encodeToByteArray())
        val (llMode, ofMode, mlMode) = FrameInspector.sequenceModes(frame)!!
        assertTrue(
            llMode != 2 && ofMode != 2 && mlMode != 2,
            "a single-sequence block should not pay for an FSE table description",
        )
    }

    @Test
    fun freshTablesRoundTripAcrossVariedInputs() {
        val inputs = TestVectors.corpus + listOf(
            TestVectors.logRecords,
            TestVectors.byteRuns,
            TestVectors.skewedAlphabet,
            TestVectors.structured.reduce { a, b -> a + b },
        )
        for (sample in inputs) {
            val frame = Zstd.compress(sample)
            assertContentEquals(sample, Zstd.decompress(frame, max), "size=${sample.size}")
        }
    }
}
