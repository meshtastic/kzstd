// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.github.luben.zstd.Zstd as LibZstd

/**
 * The both-directions interop oracle (R6): kzstd's pure-Kotlin frames must decode
 * under real libzstd, and kzstd must decode real libzstd's frames — with and
 * without a dictionary.
 *
 * [kzstdFramesDecodeUnderLibzstd_withDict] is now also where kzstd's OWN
 * dict-entropy encode paths (repeat-offset codes, FSE-repeat sequences,
 * Treeless Huffman literals) get validated against a real, independent
 * decoder -- not just kzstd's own decoder round-tripping its own output.
 * [libzstdWithTrainedDictDecodesUnderKzstd] remains the reverse check: it's
 * still the only thing that exercises kzstd's dictionary-entropy DECODE path
 * against every combination libzstd might choose that kzstd's own encoder
 * doesn't (e.g. 4-stream Huffman literals, out of scope for this encoder).
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

    @Test
    fun libzstdFrameWithRealDictIdDecodesUnderKzstd_correctDict() {
        // libzstd sets a real (non-zero) Dictionary_ID by default when compressing
        // with a proper Zstandard-format dictionary (RFC 8878 §5) -- unlike kzstd's
        // own encoder, which never sets one (Dictionary_ID_Flag=0 always).
        val cdict = ZstdDictCompress(dictBytes, Zstd.DEFAULT_LEVEL)
        val frame = LibZstd.compress(TestVectors.structured[0], cdict)
        assertTrue(frameDictId(frame) != 0, "libzstd did not set a frame Dictionary_ID")
        val back = Zstd.decompress(frame, kdict, max)
        assertContentEquals(TestVectors.structured[0], back)
    }

    @Test
    fun libzstdFrameWithRealDictIdDecodesUnderKzstd_wrongDictThrowsClearError() {
        // Same dictionary content/entropy tables, but a different embedded
        // Dictionary_ID -- simulates "the caller supplied the wrong dictionary".
        val wrongIdBytes = dictBytes.copyOf()
        val wrongId = TestVectors.trainedDictId xor 0x5A5A5A5A
        wrongIdBytes[4] = (wrongId and 0xFF).toByte()
        wrongIdBytes[5] = ((wrongId ushr 8) and 0xFF).toByte()
        wrongIdBytes[6] = ((wrongId ushr 16) and 0xFF).toByte()
        wrongIdBytes[7] = ((wrongId ushr 24) and 0xFF).toByte()
        val wrongDict = ZstdDictionary(wrongIdBytes)

        val cdict = ZstdDictCompress(dictBytes, Zstd.DEFAULT_LEVEL)
        val frame = LibZstd.compress(TestVectors.structured[0], cdict)
        assertTrue(frameDictId(frame) != 0, "libzstd did not set a frame Dictionary_ID")

        val ex = assertFailsWith<ZstdException> { Zstd.decompress(frame, wrongDict, max) }
        assertTrue(
            ex.message?.contains("dictionary ID mismatch", ignoreCase = true) == true,
            "expected a dictionary-ID-mismatch message, got: ${ex.message}",
        )
    }

    /** Frame's declared Dictionary_ID (RFC 8878 §3.1.1.3), or 0 if the field is absent. */
    private fun frameDictId(frame: ByteArray): Int {
        var p = 4 // skip frame magic
        val fhd = frame[p].toInt() and 0xFF
        p++
        val singleSegment = (fhd ushr 5) and 0x1
        val dictIdFlag = fhd and 0x3
        if (singleSegment == 0) p++ // window descriptor
        val dictIdBytes = when (dictIdFlag) {
            0 -> 0
            1 -> 1
            2 -> 2
            else -> 4
        }
        var id = 0
        for (i in 0 until dictIdBytes) id = id or ((frame[p + i].toInt() and 0xFF) shl (8 * i))
        return id
    }

    @Test
    fun kzstdValidatesLibzstdContentChecksum() {
        // The zstd CLI/library enables Content_Checksum by default; kzstd's own
        // encoder never has (Content_Checksum_Flag stays off in all of the
        // above), so this is the only place the decode-side XXH64 validation
        // (RFC 8878 §3.1.1) against a REAL checksum gets exercised.
        for (sample in TestVectors.corpus) {
            val frame = ZstdCompressCtx().use { it.setChecksum(true).compress(sample) }
            val back = Zstd.decompress(frame, max)
            assertContentEquals(sample, back, "libzstd(checksum)->kzstd, size=${sample.size}")
        }
    }

    @Test
    fun kzstdRejectsCorruptedChecksummedFrame() {
        val sample = TestVectors.corpus.first { it.isNotEmpty() }
        val frame = ZstdCompressCtx().use { it.setChecksum(true).compress(sample) }
        // Flip a bit in the trailing 4-byte XXH64 checksum field itself, so the
        // frame still parses (magic/header/blocks untouched) but the checksum
        // no longer matches the (still-correctly-decoded) content.
        val corrupted = frame.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0x01).toByte()
        val ex = assertFailsWith<ZstdException> { Zstd.decompress(corrupted, max) }
        assertTrue(ex.message!!.contains("checksum"), "expected a checksum-mismatch message, got: ${ex.message}")
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
