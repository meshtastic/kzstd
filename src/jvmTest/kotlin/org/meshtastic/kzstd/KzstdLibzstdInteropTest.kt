// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import com.github.luben.zstd.Zstd as LibZstd

/**
 * The both-directions interop oracle (R6): kzstd's pure-Kotlin frames must decode
 * under real libzstd, and kzstd must decode real libzstd's frames — with and
 * without a dictionary.
 *
 * The [libzstdWithTrainedDictDecodesUnderKzstd] direction is also the only thing
 * that exercises kzstd's dictionary-entropy DECODE path: kzstd's own encoder emits
 * Predefined-FSE + Raw-literals frames, so it never produces the treeless-literals
 * / FSE-repeat / repeat-offset-seeded frames that reference a dictionary's entropy
 * tables. libzstd, compressing training-distribution data WITH the trained dict,
 * does — and kzstd must decode them (the P1 guard from the architecture review).
 */
class KzstdLibzstdInteropTest {

    private val dictBytes = TestVectors.trainedDict
    private val kdict = ZstdDictionary(dictBytes)
    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    @Test
    fun kzstdFramesDecodeUnderLibzstd_withDict() {
        val ddict = ZstdDictDecompress(dictBytes)
        for (sample in TestVectors.corpus) {
            val frame = Zstd.compress(sample, kdict)
            val back = LibZstd.decompress(frame, ddict, max)
            assertContentEquals(sample, back, "kzstd->libzstd (dict), size=${sample.size}")
        }
    }

    @Test
    fun kzstdFramesDecodeUnderLibzstd_dictless() {
        for (sample in TestVectors.corpus) {
            val frame = Zstd.compress(sample)
            val back = LibZstd.decompress(frame, max)
            assertContentEquals(sample, back, "kzstd->libzstd (dictless), size=${sample.size}")
        }
    }

    @Test
    fun libzstdWithTrainedDictDecodesUnderKzstd() {
        val cdict = ZstdDictCompress(dictBytes, Zstd.DEFAULT_LEVEL)
        for (sample in TestVectors.structured) {
            val frame = LibZstd.compress(sample, cdict)
            val back = Zstd.decompress(frame, kdict, max)
            assertContentEquals(sample, back, "libzstd(dict)->kzstd, size=${sample.size}")
        }
    }

    @Test
    fun libzstdFramesDecodeUnderKzstd_dictless() {
        for (sample in TestVectors.corpus) {
            val frame = LibZstd.compress(sample, Zstd.DEFAULT_LEVEL)
            val back = Zstd.decompress(frame, max)
            assertContentEquals(sample, back, "libzstd->kzstd (dictless), size=${sample.size}")
        }
    }

    @Test
    fun libzstdActuallyEmitsTreelessDictFrames() {
        // Guards the contingency in libzstdWithTrainedDictDecodesUnderKzstd: that test
        // only round-trips, so if libzstd ever stopped reusing the dict's Huffman table
        // (treeless literals, litType 3) the dict-entropy decode path would silently
        // stop being exercised. Assert at least one structured sample still produces a
        // treeless frame — and that the committed cross-target fixture is itself treeless.
        val cdict = ZstdDictCompress(dictBytes, Zstd.DEFAULT_LEVEL)
        val treeless = TestVectors.structured.count { firstBlockLitType(LibZstd.compress(it, cdict)) == 3 }
        assertTrue(
            treeless > 0,
            "libzstd emitted no treeless (dict-Huffman) frames — dict-entropy decode path untested",
        )
        assertTrue(
            firstBlockLitType(TestVectors.treelessDictFrame) == 3,
            "the committed treelessDictFrame is not actually treeless",
        )
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
        val bh = (frame[p].toInt() and 0xFF) or ((frame[p + 1].toInt() and 0xFF) shl 8) or
            ((frame[p + 2].toInt() and 0xFF) shl 16)
        val blockType = (bh ushr 1) and 0x3
        p += 3
        return if (blockType == 2) (frame[p].toInt() and 0xFF) and 0x3 else -1
    }
}
