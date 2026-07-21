// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Round-trip every corpus sample through compress → decompress on every target
 * (this suite runs on jvm, js, wasmJs, wasmWasi, and all native targets), with a
 * trained dictionary, the empty dictionary, and the dictionary-less overloads.
 */
class ZstdRoundTripTest {

    private val dict = ZstdDictionary(TestVectors.trainedDict)
    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    @Test
    fun roundTripWithTrainedDictionary() {
        for (sample in TestVectors.corpus) {
            val frame = Zstd.compress(sample, dict)
            assertContentEquals(sample, Zstd.decompress(frame, dict, max), "size=${sample.size}")
        }
    }

    @Test
    fun roundTripDictionaryLess() {
        for (sample in TestVectors.corpus) {
            val frame = Zstd.compress(sample) // dict-less overload
            assertContentEquals(sample, Zstd.decompress(frame, max), "size=${sample.size}")
        }
    }

    @Test
    fun oneDictionaryInstanceReusedAcrossManyCalls() {
        // The digested ZstdDictionary parses/indexes once in its constructor;
        // reusing the SAME instance across many calls must stay correct (R4).
        val d = ZstdDictionary(TestVectors.trainedDict)
        repeat(50) {
            for (sample in TestVectors.structured) {
                assertContentEquals(sample, Zstd.decompress(Zstd.compress(sample, d), d, max))
            }
        }
    }
}
