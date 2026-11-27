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
        val maxSizes = intArrayOf(48, 46, 49, 52, 54, 54)
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
     * returning (llMode, ofMode, mlMode). Handles Raw literals (litType 0) and
     * Huffman-coded literals with size_format 0 (litType 2 or 3, single
     * stream) -- the only forms kzstd's own encoder produces. Assumes a
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

        // Literals_Section_Header (RFC 8878 §3.1.1.3.1): litType is bits 0-1.
        // Size_Format is 1 or 2 bits, read PROGRESSIVELY -- bit 2 alone
        // selects the 1-byte/5-bit-size form (0), in which case bit 3 is
        // already part of the size field, NOT a second format bit; only when
        // bit 2 is 1 does bit 3 additionally distinguish between forms.
        // Reading bits 2-3 together as a flat 2-bit field (as litType's own
        // 2-bit field can be) is WRONG here.
        val litB0 = frame[p].toInt() and 0xFF
        val litType = litB0 and 0x3

        val headerLen: Int
        val literalsLen: Int

        when (litType) {
            0 -> { // Raw literals
                if ((litB0 ushr 2) and 0x1 == 0) {
                    headerLen = 1
                    literalsLen = litB0 ushr 3
                } else if ((litB0 ushr 3) and 0x1 == 0) {
                    headerLen = 2
                    literalsLen = ((litB0 ushr 4) and 0xF) or ((frame[p + 1].toInt() and 0xFF) shl 4)
                } else {
                    headerLen = 3
                    literalsLen = ((litB0 ushr 4) and 0xF) or
                        ((frame[p + 1].toInt() and 0xFF) shl 4) or
                        ((frame[p + 2].toInt() and 0xFF) shl 12)
                }
            }

            2, 3 -> { // Compressed (2) / Treeless (3) Huffman literals
                // Unlike Raw/RLE above, Size_Format for Huffman-coded literals
                // is a genuine flat 2-bit field (0/1/2/3 are 4 DISTINCT forms:
                // single-stream-10-bit, 4-stream-10-bit, 4-stream-14-bit,
                // 4-stream-18-bit) -- there is no bit2-alone shortcut here.
                // kzstd's own encoder (this PR) only ever emits size_format 0
                // (single stream, <= 1023-byte payloads); the rest are out of
                // scope for this helper since this encoder never produces them.
                val sizeFormat = (litB0 ushr 2) and 0x3
                if (sizeFormat == 0) {
                    headerLen = 3
                    val b1 = frame[p + 1].toInt() and 0xFF
                    val b2 = frame[p + 2].toInt() and 0xFF
                    literalsLen = (b1 ushr 6) or (b2 shl 2)
                } else {
                    throw UnsupportedOperationException(
                        "Huffman literals (litType $litType) with size_format $sizeFormat (4-stream layout) " +
                            "not parsed by this helper",
                    )
                }
            }

            else -> {
                throw UnsupportedOperationException("litType $litType not supported by this helper")
            }
        }

        p += headerLen + literalsLen

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
