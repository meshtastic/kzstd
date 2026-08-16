// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Adoption guard for the encoder's dict-trained "Repeat" FSE tables (RFC 8878
 * Symbol_Compression_Mode 3) for sequences: [FseEncTable.isCovered] being
 * correct in isolation doesn't prove a real dict-compressed block ever picks
 * Repeat mode over Predefined. Compress the dict-trained `structured` corpus
 * with kzstd's own encoder and parse the resulting frame's real
 * Symbol_Compression_Modes byte to confirm at least one LL/OF/ML stream
 * actually used it -- otherwise this workstream could silently regress to
 * always-Predefined while every round-trip/byte-identical/libzstd-interop
 * test stays green for a reason unrelated to Repeat-mode correctness.
 */
class SequenceRepeatModeTest {

    private val dict = ZstdDictionary(TestVectors.trainedDict)

    @Test
    fun compressingStructuredDictCorpus_actuallyUsesRepeatModeForAtLeastOneStream() {
        var sawRepeatMode = false
        for (sample in TestVectors.structured) {
            val frame = Zstd.compress(sample, dict)
            val (llMode, ofMode, mlMode) = firstBlockSequenceModes(frame)
            if (llMode == 3 || ofMode == 3 || mlMode == 3) sawRepeatMode = true
        }
        assertTrue(
            sawRepeatMode,
            "no structured dict sample used Repeat mode (3) for any LL/OF/ML sequence stream",
        )
    }

    /**
     * Ratio ratchet: pins an upper bound on each `structured` sample's
     * dict-compressed size. A silent regression back to always-Predefined
     * tables would still produce a perfectly valid frame -- round-trip and
     * interop tests would stay green -- but would grow these sizes, which
     * this catches. Refresh the bounds (never loosen them without a reason)
     * if a later, deliberate change legitimately shrinks or grows output.
     */
    @Test
    fun repeatModeDoesNotRegressCompressedSize() {
        // Exact current Zstd.compress(sample, dict).size for each
        // TestVectors.structured entry, confirmed via jvmTest (structured[0]
        // also cross-checked against ByteIdenticalRegressionTest's pinned
        // hex). Refresh (never loosen without a reason) if a later,
        // deliberate change legitimately shrinks or grows output.
        val maxSizes = intArrayOf(54, 52, 55, 58, 55, 59)
        TestVectors.structured.forEachIndexed { i, sample ->
            val size = Zstd.compress(sample, dict).size
            assertTrue(
                size <= maxSizes[i],
                "structured[$i] dict-compressed size regressed: $size > ${maxSizes[i]}",
            )
        }
    }

    /**
     * Parses just far enough into a kzstd-produced frame's first (and only)
     * block to read the Sequences_Section's Symbol_Compression_Modes byte,
     * returning (llMode, ofMode, mlMode). Assumes Raw literals (litType 0) --
     * the only literals type kzstd's own encoder emits today (treeless
     * dict-Huffman literals are a later, separate workstream) -- and a
     * Compressed block, which is always what a dict-trained corpus produces.
     */
    private fun firstBlockSequenceModes(frame: ByteArray): Triple<Int, Int, Int> {
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

        // Block_Header (3 bytes LE): assume Compressed_Block (type 2).
        val bh = (frame[p].toInt() and 0xFF) or
            ((frame[p + 1].toInt() and 0xFF) shl 8) or
            ((frame[p + 2].toInt() and 0xFF) shl 16)
        val blockType = (bh ushr 1) and 0x3
        check(blockType == 2) { "expected a Compressed_Block (2), got $blockType" }
        p += 3

        // Literals_Section_Header for Raw/RLE (RFC 8878 3.1.1.3.1): litType is
        // bits 0-1. Size_Format is 1 or 2 bits, read PROGRESSIVELY -- bit 2
        // alone selects the 1-byte/5-bit-size form (0), in which case bit 3 is
        // already part of the size field, NOT a second format bit; only when
        // bit 2 is 1 does bit 3 additionally distinguish the 2-byte (0) vs
        // 3-byte (1) forms. Reading bits 2-3 together as a flat 2-bit field
        // (as litType's own 2-bit field can be) is WRONG here -- it would
        // misread the 1-byte form's size-bit-3 as a bogus second format bit
        // whenever that size bit happens to be 1.
        val litB0 = frame[p].toInt() and 0xFF
        val litType = litB0 and 0x3
        check(litType == 0) { "expected Raw literals (litType 0), got $litType -- update this helper" }
        val headerLen: Int
        val regenSize: Int
        if ((litB0 ushr 2) and 0x1 == 0) {
            headerLen = 1
            regenSize = litB0 ushr 3
        } else if ((litB0 ushr 3) and 0x1 == 0) {
            headerLen = 2
            regenSize = ((litB0 ushr 4) and 0xF) or ((frame[p + 1].toInt() and 0xFF) shl 4)
        } else {
            headerLen = 3
            regenSize = ((litB0 ushr 4) and 0xF) or
                ((frame[p + 1].toInt() and 0xFF) shl 4) or
                ((frame[p + 2].toInt() and 0xFF) shl 12)
        }
        p += headerLen + regenSize

        // Sequences_Section: Number_of_Sequences (1/2/3-byte), then the
        // Symbol_Compression_Modes byte.
        val nb0 = frame[p].toInt() and 0xFF
        p += when {
            nb0 < 128 -> 1
            nb0 < 255 -> 2
            else -> 3
        }
        val modes = frame[p].toInt() and 0xFF
        return Triple((modes ushr 6) and 0x3, (modes ushr 4) and 0x3, (modes ushr 2) and 0x3)
    }
}
