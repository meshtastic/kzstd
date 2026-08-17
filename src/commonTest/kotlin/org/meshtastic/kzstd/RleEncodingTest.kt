// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The encoder's RLE forms (RFC 8878): RLE_Block (Block_Type 1), RLE literals
 * (Literals_Block_Type 1) and RLE sequence tables (Symbol_Compression_Mode 1).
 * Each is the degenerate one-symbol case of the block's own entropy coding, and
 * each is chosen only when it is the smallest valid encoding.
 *
 * The decoder has always READ all three (libzstd emits them); these tests pin
 * that kzstd's encoder now WRITES them, since a round-trip alone would stay
 * green if the encoder silently fell back to Raw/Predefined.
 */
class RleEncodingTest {

    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    @Test
    fun constantInput_emitsRleBlock() {
        val frame = Zstd.compress(TestVectors.constantBytes)
        assertEquals(1, FrameInspector.blockType(frame), "constant input did not produce an RLE_Block")
        assertEquals(TestVectors.constantBytes.size, FrameInspector.blockSize(frame), "RLE Block_Size")
        // Frame magic (4) + descriptor (1) + window (1) + block header (3) + the byte.
        assertEquals(10, frame.size, "RLE_Block frame should be 10 bytes")
        assertContentEquals(TestVectors.constantBytes, Zstd.decompress(frame, max))
    }

    @Test
    fun shortConstantInputsStillRoundTrip() {
        for (n in 0..5) {
            val data = ByteArray(n) { 'z'.code.toByte() }
            val frame = Zstd.compress(data)
            assertContentEquals(data, Zstd.decompress(frame, max), "constant input of $n bytes")
        }
    }

    @Test
    fun constantSequenceCodes_useRleTablesForAllThreeStreams() {
        val frame = Zstd.compress(TestVectors.byteRuns)
        val (llMode, ofMode, mlMode) = FrameInspector.sequenceModes(frame)!!
        assertEquals(1, llMode, "literal-length stream did not use an RLE table")
        assertEquals(1, ofMode, "offset stream did not use an RLE table")
        assertEquals(1, mlMode, "match-length stream did not use an RLE table")
        assertContentEquals(TestVectors.byteRuns, Zstd.decompress(frame, max))
    }

    @Test
    fun constantLiterals_useRleLiterals() {
        val dict = ZstdDictionary(TestVectors.rawContentDict)
        val frame = Zstd.compress(TestVectors.rleLiteralsSample, dict)
        assertEquals(1, FrameInspector.literalsType(frame), "constant literals did not produce RLE literals")
        assertContentEquals(TestVectors.rleLiteralsSample, Zstd.decompress(frame, dict, max))
    }

    /**
     * Ratio ratchet against the sizes the encoder produced before it could emit
     * any RLE form (a Compressed_Block with predefined FSE tables in both
     * cases). Refresh (never loosen without a reason) if a later, deliberate
     * change moves them.
     */
    @Test
    fun rleFormsShrinkOutput() {
        val constant = Zstd.compress(TestVectors.constantBytes).size
        assertTrue(constant < 17, "constant input: $constant bytes, pre-RLE baseline was 17")
        val runs = Zstd.compress(TestVectors.byteRuns).size
        assertTrue(runs < 85, "byte-run sample: $runs bytes, pre-RLE baseline was 85")
    }
}
