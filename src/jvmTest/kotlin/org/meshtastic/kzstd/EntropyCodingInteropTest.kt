// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import com.github.luben.zstd.Zstd as LibZstd

/**
 * Cross-oracle for the encoder's dictionary-FREE entropy coding: every block
 * form kzstd can now choose for a plain `Zstd.compress(data)` call is handed to
 * REAL libzstd (zstd-jni) to decode. kzstd decoding its own output proves only
 * self-consistency; only libzstd accepting these frames proves the wire format
 * is conformant.
 *
 * Each test asserts the block/literals/table MODE first, so it fails loudly if
 * the cost model stops choosing the form under test instead of silently
 * degrading into a round-trip test of the Raw/Predefined fallback.
 */
class EntropyCodingInteropTest {

    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    @Test
    fun rleBlockDecodesUnderLibzstd() {
        val data = TestVectors.constantBytes
        val frame = Zstd.compress(data)
        assertEquals(1, FrameInspector.blockType(frame), "expected an RLE_Block")
        assertContentEquals(data, LibZstd.decompress(frame, max))
    }

    /**
     * All three sequence streams RLE (mode 1) AND no sequence code carrying
     * extra bits: the sequences bitstream degenerates to a single stop-bit byte.
     * That is the sharpest edge in the whole sequences section — a decoder that
     * insists on consuming at least one real bit rejects it — so it is checked
     * against libzstd rather than reasoned about.
     */
    @Test
    fun rleSequenceTablesDecodeUnderLibzstd() {
        val data = TestVectors.byteRuns
        val frame = Zstd.compress(data)
        assertEquals(Triple(1, 1, 1), FrameInspector.sequenceModes(frame), "expected RLE tables for LL/OF/ML")
        assertContentEquals(data, LibZstd.decompress(frame, max))
    }

    /**
     * The whole point of the fresh-Huffman work: a table built from the block's
     * own histogram, described on the wire, and rebuilt by an independent
     * decoder. Nothing but a real libzstd decode proves the
     * Huffman_Tree_Description and the canonical code assignment agree with the
     * spec rather than merely with kzstd's own reader.
     */
    @Test
    fun freshHuffmanLiteralsDecodeUnderLibzstd() {
        for (data in huffmanSamples()) {
            val frame = Zstd.compress(data)
            assertEquals(2, FrameInspector.literalsType(frame), "expected Huffman literals for ${data.size} bytes")
            assertContentEquals(data, LibZstd.decompress(frame, max), "size=${data.size}")
        }
    }

    private fun huffmanSamples(): List<ByteArray> = listOf(
        (
            "the quick brown fox jumps over the lazy dog while nominal reports stream " +
                "steadily across every monitored link and latency stays within throughput " +
                "targets even when the region degrades to offline for a while. "
            ).encodeToByteArray(),
        TestVectors.structured.reduce { a, b -> a + b },
        // Wide alphabets: long weight descriptions, and (for the skewed one)
        // code lengths spread far enough to reach the length limit.
        TestVectors.skewedAlphabet,
        TestVectors.wideAlphabet,
    )

    @Test
    fun rleLiteralsDecodeUnderLibzstd() {
        val data = TestVectors.rleLiteralsSample
        val dictBytes = TestVectors.rawContentDict
        val frame = Zstd.compress(data, ZstdDictionary(dictBytes))
        assertEquals(1, FrameInspector.literalsType(frame), "expected RLE literals")
        // Raw-content dictionary (no trained-dict magic): libzstd treats the
        // bytes as pure back-reference content, which is exactly what kzstd did.
        assertContentEquals(data, LibZstd.decompress(frame, dictBytes, max))
    }
}
