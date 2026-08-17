// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import com.github.luben.zstd.ZstdDictDecompress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
     * A dictionary match's distance is measured from the CURRENT position, so in
     * the second and later blocks the dictionary sits a whole block further back
     * than it does in the first. Here the first block is pure noise, so the
     * second block's only compressible history is the dictionary itself.
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
            assertEquals(2, blocks[1].type, "the tail block should be a Compressed_Block")
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
}
