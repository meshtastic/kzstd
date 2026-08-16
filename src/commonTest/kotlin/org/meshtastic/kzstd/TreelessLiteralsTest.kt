// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Adoption guard for the encoder's dict-trained Treeless Huffman literals
 * (RFC 8878 litType 3): the mandatory cost comparison in `writeLiteralsSection`
 * being correct in isolation doesn't prove a real dict-compressed block ever
 * actually picks Treeless over Raw. Compress the dict-trained `structured`
 * corpus with kzstd's own encoder and parse the resulting frame's real
 * Literals_Block_Type to confirm at least one sample used it -- otherwise this
 * workstream could silently regress to always-Raw (e.g. an overly strict cost
 * comparison, or a coverage check that never passes) while every
 * round-trip/byte-identical/libzstd-interop test stays green for a reason
 * unrelated to Treeless-literals correctness.
 */
class TreelessLiteralsTest {

    private val dict = ZstdDictionary(TestVectors.trainedDict)

    @Test
    fun compressingStructuredDictCorpus_actuallyEmitsTreelessLiterals() {
        val sawTreeless = TestVectors.structured.any { sample ->
            firstBlockLitType(Zstd.compress(sample, dict)) == 3
        }
        assertTrue(sawTreeless, "no structured dict sample used Treeless (dict-Huffman) literals (litType 3)")
    }

    /** Literals_Block_Type of the first block (RFC 8878), or -1 if not a Compressed block. */
    private fun firstBlockLitType(frame: ByteArray): Int {
        var p = 4 // skip frame magic
        val fhd = frame[p].toInt() and 0xFF
        p++
        val fcsFlag = (fhd ushr 6) and 0x3
        val singleSegment = (fhd ushr 5) and 0x1
        val dictIdFlag = fhd and 0x3
        if (singleSegment == 0) p++ // window descriptor
        p += when (dictIdFlag) {
            0 -> 0
            1 -> 1
            2 -> 2
            else -> 4
        }
        p += when (fcsFlag) {
            0 -> if (singleSegment == 1) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        val bh = (frame[p].toInt() and 0xFF) or
            ((frame[p + 1].toInt() and 0xFF) shl 8) or
            ((frame[p + 2].toInt() and 0xFF) shl 16)
        val blockType = (bh ushr 1) and 0x3
        p += 3
        return if (blockType == 2) (frame[p].toInt() and 0xFF) and 0x3 else -1
    }
}
