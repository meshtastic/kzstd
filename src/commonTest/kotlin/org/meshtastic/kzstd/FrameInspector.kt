// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

/**
 * Test-only reader for the structural fields of a kzstd-produced frame:
 * Block_Type, Literals_Block_Type and the Sequences_Section's
 * Symbol_Compression_Modes byte.
 *
 * Encoding-choice tests need these because a round-trip proves only that the
 * frame decodes -- not WHICH of the several valid encodings the cost model
 * picked. Asserting the mode is what stops a silent regression to "always Raw
 * literals / always Predefined tables" from passing every other test.
 *
 * It parses only the forms kzstd's own encoder emits (single-segment-off frame
 * header, no dictID/content-size/checksum; Raw/RLE/Huffman-size_format-0
 * literals), and throws otherwise rather than guessing.
 */
internal object FrameInspector {

    /** Block_Type (0 Raw, 1 RLE, 2 Compressed) of the frame's first block. */
    fun blockType(frame: ByteArray): Int {
        val p = frameHeaderEnd(frame)
        return (blockHeader(frame, p) ushr 1) and 0x3
    }

    /** Block_Size field of the frame's first block header. */
    fun blockSize(frame: ByteArray): Int = blockHeader(frame, frameHeaderEnd(frame)) ushr 3

    /** One block's header fields, as read off the wire. */
    class Block(val type: Int, val size: Int, val last: Boolean)

    /**
     * Every block of the frame, in order, by walking the block chain and
     * skipping each body. The walk asserts the chain ends exactly at the end of
     * the frame, so a wrong Block_Size or a missing/extra Last_Block flag fails
     * here rather than surviving as a frame that happens to still decode.
     *
     * Note the two meanings of `Block_Size`: for Raw and Compressed blocks it is
     * the length of the body that follows, but for an RLE block it is the
     * REGENERATED length while the body is a single byte.
     */
    fun blocks(frame: ByteArray): List<Block> {
        var p = frameHeaderEnd(frame)
        val blocks = ArrayList<Block>()
        while (true) {
            val header = blockHeader(frame, p)
            val block = Block(type = (header ushr 1) and 0x3, size = header ushr 3, last = (header and 1) == 1)
            blocks.add(block)
            p += 3 + if (block.type == 1) 1 else block.size
            if (block.last) break
            check(p < frame.size) { "block chain ran off the end of a ${frame.size}-byte frame" }
        }
        check(p == frame.size) { "block chain ended at $p of a ${frame.size}-byte frame" }
        return blocks
    }

    /**
     * Literals_Block_Type (0 Raw, 1 RLE, 2 Huffman_Compressed, 3 Treeless) of
     * the first block, or -1 when that block is not a Compressed_Block.
     */
    fun literalsType(frame: ByteArray): Int {
        val p = frameHeaderEnd(frame)
        if ((blockHeader(frame, p) ushr 1) and 0x3 != 2) return -1
        return frame[p + 3].toInt() and 0x3
    }

    /**
     * (llMode, ofMode, mlMode) from the first block's Symbol_Compression_Modes
     * byte, or null when the block carries no sequences.
     */
    fun sequenceModes(frame: ByteArray): Triple<Int, Int, Int>? {
        var p = frameHeaderEnd(frame)
        check((blockHeader(frame, p) ushr 1) and 0x3 == 2) { "not a Compressed_Block" }
        p += 3
        p = skipLiteralsSection(frame, p)

        if (sequenceCountAt(frame, p) == 0) return null
        p += sequenceCountFieldLen(frame, p)
        val modes = frame[p].toInt() and 0xFF
        return Triple((modes ushr 6) and 0x3, (modes ushr 4) and 0x3, (modes ushr 2) and 0x3)
    }

    /** Number_of_Sequences of the first block (0 when it carries none). */
    fun sequenceCount(frame: ByteArray): Int {
        var p = frameHeaderEnd(frame) + 3
        p = skipLiteralsSection(frame, p)
        return sequenceCountAt(frame, p)
    }

    /**
     * Accuracy_Log of the LITERAL-LENGTH stream's FSE table description, or
     * null unless that stream is in FSE_Compressed mode. The description's
     * first byte holds `Accuracy_Log - 5` in its low 4 bits.
     *
     * Only the first stream's log is readable without decoding a whole
     * description (they are variable-length), and the literal-length stream
     * comes first — which is enough, since it is also the stream whose
     * Accuracy_Log reaches the format's ceiling first.
     */
    fun literalLengthAccuracyLog(frame: ByteArray): Int? {
        var p = frameHeaderEnd(frame) + 3
        p = skipLiteralsSection(frame, p)
        p += sequenceCountFieldLen(frame, p)
        val modes = frame[p].toInt() and 0xFF
        if ((modes ushr 6) and 0x3 != 2) return null
        return (frame[p + 1].toInt() and 0xF) + 5
    }

    private fun sequenceCountAt(frame: ByteArray, p: Int): Int {
        val nb0 = frame[p].toInt() and 0xFF
        return when {
            nb0 < 128 -> nb0
            nb0 < 255 -> ((nb0 - 128) shl 8) + (frame[p + 1].toInt() and 0xFF)
            else -> (frame[p + 1].toInt() and 0xFF) + ((frame[p + 2].toInt() and 0xFF) shl 8) + 0x7F00
        }
    }

    private fun sequenceCountFieldLen(frame: ByteArray, p: Int): Int {
        val nb0 = frame[p].toInt() and 0xFF
        return when {
            nb0 < 128 -> 1
            nb0 < 255 -> 2
            else -> 3
        }
    }

    /** Byte offset of the first Block_Header. */
    private fun frameHeaderEnd(frame: ByteArray): Int {
        var p = 4 // frame magic
        val fhd = frame[p].toInt() and 0xFF
        p++
        val fcsFlag = (fhd ushr 6) and 0x3
        val singleSegment = (fhd ushr 5) and 0x1
        val dictIdFlag = fhd and 0x3
        if (singleSegment == 0) p++ // Window_Descriptor
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
        return p
    }

    private fun blockHeader(frame: ByteArray, p: Int): Int = (frame[p].toInt() and 0xFF) or
        ((frame[p + 1].toInt() and 0xFF) shl 8) or
        ((frame[p + 2].toInt() and 0xFF) shl 16)

    /**
     * Advance past a Literals_Section (header + body). For Raw/RLE the
     * Size_Format field is read PROGRESSIVELY -- bit 2 alone selects the
     * 1-byte/5-bit-size form, in which case bit 3 already belongs to the size
     * field -- whereas for Huffman-coded literals it is a flat 2-bit field.
     */
    private fun skipLiteralsSection(frame: ByteArray, p: Int): Int {
        val b0 = frame[p].toInt() and 0xFF
        return when (val litType = b0 and 0x3) {
            0, 1 -> {
                val headerLen: Int
                val regenSize: Int
                if ((b0 ushr 2) and 0x1 == 0) {
                    headerLen = 1
                    regenSize = b0 ushr 3
                } else if ((b0 ushr 3) and 0x1 == 0) {
                    headerLen = 2
                    regenSize = ((b0 ushr 4) and 0xF) or ((frame[p + 1].toInt() and 0xFF) shl 4)
                } else {
                    headerLen = 3
                    regenSize = ((b0 ushr 4) and 0xF) or
                        ((frame[p + 1].toInt() and 0xFF) shl 4) or
                        ((frame[p + 2].toInt() and 0xFF) shl 12)
                }
                // RLE literals store ONE byte regardless of Regenerated_Size.
                p + headerLen + if (litType == 0) regenSize else 1
            }

            else -> {
                val sizeFormat = (b0 ushr 2) and 0x3
                check(sizeFormat == 0) { "Huffman literals size_format $sizeFormat not parsed by this helper" }
                val b1 = frame[p + 1].toInt() and 0xFF
                val b2 = frame[p + 2].toInt() and 0xFF
                val compressedSize = (b1 ushr 6) or (b2 shl 2)
                p + 3 + compressedSize
            }
        }
    }
}
