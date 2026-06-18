// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Explicit guard for the wasmWasi target, which has no JS host and no cinterop —
 * proving compress AND decompress both run here proves the codec is genuinely
 * pure-Kotlin on every target. (The commonTest suites also run on wasmWasi; this
 * is a focused, greppable guarantee.)
 */
class WasmWasiCodecTest {

    @Test
    fun compressAndDecompressOnWasmWasi() {
        val dict = ZstdDictionary(TestVectors.trainedDict)
        val max = TestVectors.MAX_DECOMPRESSED_SIZE
        for (sample in TestVectors.structured) {
            val frame = Zstd.compress(sample, dict)
            assertContentEquals(sample, Zstd.decompress(frame, dict, max))
        }
    }
}
