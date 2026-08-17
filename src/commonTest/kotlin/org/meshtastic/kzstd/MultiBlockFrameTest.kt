// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A zstd block's regenerated content cannot exceed `Block_Maximum_Size`
 * (RFC 8878 §3.1.1.2) — 128 KiB — so anything larger has to be split across
 * SEVERAL blocks of one frame, with `Last_Block` set on the final block only.
 * The encoder used to reject such inputs outright.
 *
 * Round-tripping alone would not prove the split is right: a frame whose block
 * chain is mis-sized can still decode when the decoder is lenient about where
 * it stops. So every case here also walks the chain with
 * [FrameInspector.blocks], which asserts the last block's body ends exactly at
 * the end of the frame.
 */
class MultiBlockFrameTest {

    private val blockMax = 1 shl 17 // 128 KiB

    /** Compressible: a 26-byte cycle, so blocks stay small and the tests stay fast. */
    private fun cyclic(size: Int) = ByteArray(size) { ('a'.code + (it % 26)).toByte() }

    /** Incompressible, so blocks fall back to Raw and the chain is at its widest. */
    private fun noisy(size: Int, seed: Int) = Random(seed).nextBytes(size)

    @Test
    fun inputAtTheBlockLimitStaysASingleBlock() {
        val data = cyclic(blockMax)
        val frame = Zstd.compress(data)
        val blocks = FrameInspector.blocks(frame)
        assertEquals(1, blocks.size, "128 KiB exactly still fits one block")
        assertTrue(blocks[0].last, "the only block must be the last block")
        assertContentEquals(data, Zstd.decompress(frame, maxSize = blockMax + 16))
    }

    @Test
    fun oneByteOverTheBlockLimitSplitsIntoTwoBlocks() {
        val data = cyclic(blockMax + 1)
        val frame = Zstd.compress(data)
        val blocks = FrameInspector.blocks(frame)
        assertEquals(2, blocks.size, "128 KiB + 1 needs a second block")
        assertEquals(listOf(false, true), blocks.map { it.last }, "Last_Block belongs to the final block only")
        assertContentEquals(data, Zstd.decompress(frame, maxSize = blockMax + 16))
    }

    @Test
    fun emptyInputIsStillASingleEmptyBlock() {
        val frame = Zstd.compress(ByteArray(0))
        val blocks = FrameInspector.blocks(frame)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].last)
        assertContentEquals(ByteArray(0), Zstd.decompress(frame, maxSize = 16))
    }

    @Test
    fun lastBlockIsSetOnlyOnTheFinalBlockOfALongChain() {
        val data = cyclic(blockMax * 3 + 7)
        val blocks = FrameInspector.blocks(Zstd.compress(data))
        assertEquals(4, blocks.size, "three full blocks plus a 7-byte remainder")
        assertEquals(listOf(false, false, false, true), blocks.map { it.last })
    }

    /**
     * A block's entropy tables stay live for the blocks after it: "Repeat"
     * (Symbol_Compression_Mode 3) means the previous block's table, and costs no
     * description bytes at all. The first block of a dictionary-less frame has no
     * previous table, so it cannot use the mode — which is what makes the
     * contrast worth asserting rather than just the presence of a 3 somewhere.
     */
    @Test
    fun laterBlocksRepeatTheEarlierBlocksSequenceTables() {
        val frame = Zstd.compress(cyclic(blockMax * 3 + 7))
        val blocks = FrameInspector.blocks(frame)

        val first = FrameInspector.sequenceModesOf(frame, blocks[0])
        assertEquals(Triple(0, 0, 0), first, "no dictionary, so the first block has nothing to repeat")
        for (i in 1..2) {
            assertEquals(
                Triple(3, 3, 3),
                FrameInspector.sequenceModesOf(frame, blocks[i]),
                "block $i should repeat the tables already described",
            )
        }
    }

    @Test
    fun multiBlockFramesRoundTrip() {
        // Each shape drives a different block type across the chain: compressible
        // input -> Compressed blocks, noise -> Raw blocks, a constant run -> RLE
        // blocks (whose Block_Size is the REGENERATED size, not the body length).
        val samples = listOf(
            cyclic(blockMax + 1),
            cyclic(blockMax * 2),
            noisy(blockMax + 5000, seed = 7),
            ByteArray(blockMax * 2 + 3) { 'Q'.code.toByte() },
        )
        for (data in samples) {
            val frame = Zstd.compress(data)
            val blocks = FrameInspector.blocks(frame)
            assertTrue(blocks.size > 1, "expected a multi-block frame for ${data.size} bytes")
            val back = Zstd.decompress(frame, maxSize = data.size + 64)
            assertContentEquals(data, back, "round-trip of ${data.size} bytes")
        }
    }

    @Test
    fun multiBlockFramesRoundTripWithADictionary() {
        val dict = ZstdDictionary(TestVectors.trainedDict)
        // Noise first, so the second block's only useful history is the
        // dictionary — which by then sits a whole block further back.
        val data = noisy(blockMax, seed = 11) + TestVectors.structured[0]
        val frame = Zstd.compress(data, dict)
        assertTrue(FrameInspector.blocks(frame).size > 1, "expected a multi-block dictionary frame")
        assertContentEquals(data, Zstd.decompress(frame, dict, maxSize = data.size + 64))
    }
}
