// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import com.github.luben.zstd.ZstdDictDecompress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.github.luben.zstd.Zstd as LibZstd

/**
 * Multi-block frames against the real-libzstd oracle: kzstd's own decoder
 * agreeing with kzstd's own encoder proves nothing about the block chain, since
 * both halves would share the same mistake. libzstd reading these frames is
 * what proves the split is conformant.
 *
 * These inputs are multi-megabyte, so they live in `jvmTest` rather than
 * `commonTest`, which runs on thirteen targets.
 */
class MultiBlockInteropTest {

    private val blockMax = 1 shl 17 // 128 KiB

    @Test
    fun multiMegabyteFrameDecodesUnderLibzstd() {
        val data = telemetry(3 shl 20)
        val frame = Zstd.compress(data)

        val blocks = FrameInspector.blocks(frame)
        assertEquals((data.size + blockMax - 1) / blockMax, blocks.size, "one block per 128 KiB chunk")
        assertEquals(1, blocks.count { it.last }, "exactly one Last_Block")
        assertTrue(blocks.last().last, "Last_Block must be the final block")

        // Later blocks reuse tables described by earlier ones, which only libzstd
        // reading the frame can confirm the encoder and decoder agree about.
        val repeated = blocks.drop(1).count { block ->
            FrameInspector.sequenceModesOf(frame, block)?.toList()?.contains(3) == true
        }
        assertTrue(repeated > 0, "no later block repeated an earlier block's table")

        assertContentEquals(data, LibZstd.decompress(frame, data.size), "kzstd -> libzstd, ${data.size} bytes")
        assertContentEquals(data, Zstd.decompress(frame, maxSize = data.size), "kzstd -> kzstd, ${data.size} bytes")
    }

    @Test
    fun multiMegabyteIncompressibleFrameDecodesUnderLibzstd() {
        // Raw blocks all the way down: the chain's headers are the only thing
        // holding the frame together, so a mis-sized one shows up immediately.
        val data = Random(0x5EED).nextBytes(2 shl 20)
        val frame = Zstd.compress(data)

        val blocks = FrameInspector.blocks(frame)
        assertEquals((data.size + blockMax - 1) / blockMax, blocks.size)
        assertTrue(blocks.all { it.type == 0 }, "expected Raw blocks for random input")
        assertContentEquals(data, LibZstd.decompress(frame, data.size))
        assertContentEquals(data, Zstd.decompress(frame, maxSize = data.size))
    }

    /**
     * Sequences carry the decoder's three repeat-offset slots forward across
     * block boundaries (they are per-FRAME state, not per-block). An encoder
     * that restarted them at every block would emit repeat codes meaning some
     * other distance, and libzstd would hand back different bytes — which a
     * kzstd-only round-trip, restarting them the same wrong way on both sides,
     * would not catch.
     */
    @Test
    fun repeatOffsetsCarryAcrossBlockBoundaries() {
        // Long-range structure, so most sequences reuse a recent offset and the
        // slots are in constant motion when each boundary is crossed.
        val unit = telemetry(1 shl 14)
        val data = ByteArray(unit.size * 40) { unit[it % unit.size] }
        val frame = Zstd.compress(data)
        assertTrue(FrameInspector.blocks(frame).size > 4, "expected several blocks")
        assertContentEquals(data, LibZstd.decompress(frame, data.size))
    }

    /**
     * The companion to the dictionary case below: an RLE BLOCK in the middle of a
     * frame must leave the entropy state alone just as a Raw one does, so the
     * block after it can still repeat the table described two blocks back. Both
     * sides have to agree on that, and only libzstd reading the frame proves it.
     */
    @Test
    fun anRleBlockBetweenCompressedBlocksLeavesTheTablesLive() {
        val data = telemetry(blockMax) + ByteArray(blockMax) { 'Q'.code.toByte() } + telemetry(blockMax)
        val frame = Zstd.compress(data)

        val blocks = FrameInspector.blocks(frame)
        assertEquals(3, blocks.size)
        assertEquals(1, blocks[1].type, "the constant chunk should be an RLE_Block")
        assertTrue(
            FrameInspector.sequenceModesOf(frame, blocks[2])?.toList()?.contains(3) == true,
            "the block after the RLE block should still repeat a table described before it",
        )

        assertContentEquals(data, LibZstd.decompress(frame, data.size))
        assertContentEquals(data, Zstd.decompress(frame, maxSize = data.size))
    }

    /**
     * A dictionary match's distance is measured from the CURRENT position, so in
     * the second and later blocks the dictionary sits a whole block further back
     * than it does in the first. Here the first block is pure noise, so the
     * second block's only compressible history is the dictionary itself.
     *
     * It also pins the rule that a Raw block leaves the frame's entropy state
     * untouched: the noise block describes nothing, so Treeless literals in the
     * second block still name the DICTIONARY's Huffman table — and libzstd has to
     * resolve it the same way for the frame to come back intact.
     */
    @Test
    fun multiBlockDictionaryFrameDecodesUnderLibzstd() {
        val dictBytes = TestVectors.trainedDict
        val kdict = ZstdDictionary(dictBytes)
        val ddict = ZstdDictDecompress(dictBytes)

        for (sample in TestVectors.structured) {
            val data = Random(0xD1C7).nextBytes(blockMax) + sample
            val frame = Zstd.compress(data, kdict)
            val blocks = FrameInspector.blocks(frame)
            assertEquals(2, blocks.size, "expected two blocks for ${data.size} bytes")
            assertEquals(0, blocks[0].type, "the noise block should be Raw, describing nothing")
            assertEquals(2, blocks[1].type, "the tail block should be a Compressed_Block")
            assertEquals(
                3,
                FrameInspector.literalsTypeOf(frame, blocks[1]),
                "the tail block should still reach the dictionary's Huffman table (Treeless)",
            )
            // Well under half: the tail is a sample the dictionary was trained on,
            // so it only shrinks this far if the second block really is matching
            // into the dictionary. Entropy-coding the literals alone would not.
            assertTrue(
                blocks[1].size < sample.size / 2,
                "tail block ${blocks[1].size} B for a ${sample.size} B sample — dictionary matches lost?",
            )
            assertContentEquals(
                data,
                LibZstd.decompress(frame, ddict, data.size),
                "kzstd(dict) -> libzstd, tail sample ${sample.size} bytes",
            )
            assertContentEquals(data, Zstd.decompress(frame, kdict, maxSize = data.size))
        }
    }

    /** JSON-ish telemetry records, generated to at least [size] bytes. */
    private fun telemetry(size: Int): ByteArray {
        var seed = 0x2468ACE
        fun next(): Int {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            return seed ushr 8
        }
        val states = listOf("ok", "warn", "offline", "degraded", "recovering", "unknown")
        val words = listOf(
            "region", "latency", "throughput", "stable", "nominal",
            "monitored", "link", "quick", "fox", "dog",
        )
        val sb = StringBuilder(size + 256)
        while (sb.length < size) {
            val msg = (0 until 3 + next() % 6).joinToString(" ") { words[next() % words.size] }
            sb.append("{\"type\":\"telemetry\",\"seq\":").append(next() % 1000000)
                .append(",\"node\":\"node-").append(next() % 64)
                .append("\",\"state\":\"").append(states[next() % states.size])
                .append("\",\"lat\":").append(next() % 90).append('.').append(next() % 100000)
                .append(",\"msg\":\"").append(msg).append("\"}")
        }
        return sb.toString().encodeToByteArray().copyOf(size)
    }

    /**
     * Lifting the single-block cap removed the only ceiling on total history
     * size. Without a replacement, the encoder would declare a windowLog beyond
     * libzstd's default decompression limit (`ZSTD_WINDOWLOG_LIMIT_DEFAULT` =
     * 27, i.e. 128 MiB) for large-enough input — a frame real-world libzstd
     * consumers reject by default (confirmed below: even a real libzstd
     * decompressor with no explicit window-log override refuses it), breaking
     * this codec's own libzstd-interoperability invariant. `encode()` now
     * rejects that case up front instead of silently emitting a
     * non-interoperable frame. 128 MiB+ is JVM-only (not commonTest) for the
     * same reason as the rest of this file: too large to allocate on all
     * thirteen targets.
     */
    @Test
    fun inputBeyondTheDefaultWindowLogLimitIsRejected() {
        val overLimit = (1L shl 27) + 1 // one byte past 128 MiB
        val data = ByteArray(overLimit.toInt())
        val error = assertFailsWith<ZstdException> { Zstd.compress(data) }
        assertTrue(
            error.message.orEmpty().contains("window"),
            "expected a window-size error, got: ${error.message}",
        )
    }
}
