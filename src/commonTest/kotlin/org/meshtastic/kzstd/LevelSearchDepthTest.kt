// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.PureZstdEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [PureZstdEncoder.searchDepthFor] (the `level` -> match-finding-effort
 * mapping) and its effects: the level=19 (`Zstd.DEFAULT_LEVEL`) compatibility
 * constraint, monotonicity across the range, clamping of out-of-range values,
 * that every level still produces a valid/decodable frame, and that a deeper
 * search can actually find a smaller encoding than a shallower one -- proving
 * `level` is a real knob, not a no-op that happens to still round-trip.
 */
class LevelSearchDepthTest {

    @Test
    fun defaultLevelMapsToTheHistoricalFixedDepth() {
        val depth = PureZstdEncoder.searchDepthFor(Zstd.DEFAULT_LEVEL)
        assertEquals(64, depth.maxChain, "level 19 must map to the pre-PR5 fixed maxChain")
        assertEquals(32, depth.maxCandidates, "level 19 must map to the pre-PR5 fixed maxCandidates")
    }

    @Test
    fun levelOneIsTheShallowFloor() {
        val depth = PureZstdEncoder.searchDepthFor(1)
        assertEquals(8, depth.maxChain)
        assertEquals(4, depth.maxCandidates)
    }

    @Test
    fun levelTwentyTwoIsTheCappedCeiling() {
        val depth = PureZstdEncoder.searchDepthFor(22)
        assertEquals(256, depth.maxChain)
        assertEquals(128, depth.maxCandidates)
    }

    @Test
    fun outOfRangeLevelsClampInsteadOfMisbehaving() {
        assertEquals(PureZstdEncoder.searchDepthFor(1).maxChain, PureZstdEncoder.searchDepthFor(0).maxChain)
        assertEquals(PureZstdEncoder.searchDepthFor(1).maxChain, PureZstdEncoder.searchDepthFor(-5).maxChain)
        assertEquals(PureZstdEncoder.searchDepthFor(22).maxChain, PureZstdEncoder.searchDepthFor(23).maxChain)
        assertEquals(PureZstdEncoder.searchDepthFor(22).maxChain, PureZstdEncoder.searchDepthFor(1000).maxChain)
    }

    @Test
    fun searchDepthIsMonotonicallyNonDecreasingAcrossTheWholeRange() {
        var prevChain = 0
        var prevCandidates = 0
        for (level in 1..22) {
            val depth = PureZstdEncoder.searchDepthFor(level)
            assertTrue(depth.maxChain >= prevChain, "maxChain regressed at level $level")
            assertTrue(depth.maxCandidates >= prevCandidates, "maxCandidates regressed at level $level")
            prevChain = depth.maxChain
            prevCandidates = depth.maxCandidates
        }
    }

    @Test
    fun everyLevelStillProducesAValidRoundTrippableFrame_dictless() {
        val levels = intArrayOf(1, 5, 19, 22)
        for (level in levels) {
            for (sample in TestVectors.corpus) {
                val frame = Zstd.compress(sample, level)
                val back = Zstd.decompress(frame, TestVectors.MAX_DECOMPRESSED_SIZE)
                assertContentEquals(sample, back, "level=$level size=${sample.size} failed to round-trip")
            }
        }
    }

    @Test
    fun everyLevelStillProducesAValidRoundTrippableFrame_withDict() {
        val dict = ZstdDictionary(TestVectors.trainedDict)
        val levels = intArrayOf(1, 5, 19, 22)
        for (level in levels) {
            for (sample in TestVectors.structured) {
                val frame = Zstd.compress(sample, dict, level)
                val back = Zstd.decompress(frame, dict, TestVectors.MAX_DECOMPRESSED_SIZE)
                assertContentEquals(sample, back, "level=$level (dict) failed to round-trip")
            }
        }
    }

    /**
     * A deeper search can only find an equal-or-better match at every input
     * position (it examines a superset of what a shallower search examines,
     * nearest-candidate-first, and only replaces the running best on a
     * strictly longer match) -- so compressed size must be monotonically
     * non-increasing as level increases for the same input. This is what
     * actually proves `level` affects real output, not just that it's plumbed
     * through without crashing.
     */
    @Test
    fun higherLevelNeverProducesALargerFrameThanLowerLevel_forTheSameInput() {
        for (sample in TestVectors.corpus) {
            val sizeAtLevel1 = Zstd.compress(sample, 1).size
            val sizeAtLevel22 = Zstd.compress(sample, 22).size
            assertTrue(
                sizeAtLevel22 <= sizeAtLevel1,
                "level 22 (${sizeAtLevel22}b) larger than level 1 (${sizeAtLevel1}b) for size=${sample.size}",
            )
        }
    }

    /**
     * Adoption guard: the monotonicity check above could pass vacuously if no
     * input in the corpus ever actually benefits from a deeper search (e.g.
     * every match is already found within the shallow floor's reach). Builds
     * an input hash-chain-adversarial by construction:
     *
     * - A 24-byte run `S` at the very start.
     * - 60 "decoy" 24-byte chunks that share `S`'s first 4 bytes (so they
     *   land in the SAME input-chain hash bucket as `S`) but are otherwise
     *   PAIRWISE DISTINCT (byte 5 encodes the decoy's own index, and the
     *   remaining bytes are an LCG-derived filler seeded per-decoy) so no
     *   decoy ever matches `S`, another decoy, or even itself elsewhere by
     *   more than the shared 4-byte prefix -- exactly `MIN_MATCH`, the
     *   smallest legal match. (An earlier version of this test used
     *   IDENTICAL decoys, which accidentally created one giant periodic
     *   match spanning nearly the whole input regardless of level --
     *   pairwise-distinct decoys are what actually forces a real chain walk.)
     * - `S` repeated again at the very end.
     *
     * The chain for that shared 4-byte prefix, walked nearest-first from the
     * final position, is: decoy_60, decoy_59, ..., decoy_1, THEN the
     * original `S` -- 61 entries deep. A level-1 search (`maxChain` = 8)
     * only reaches the 8 nearest decoys and settles for a 4-byte match; a
     * level-22 search (`maxChain` = 256) walks the whole chain and finds the
     * original `S`'s full 24-byte match instead -- fewer/cheaper sequences,
     * so a strictly smaller frame.
     */
    @Test
    fun aDeeperSearchActuallyFindsABetterMatchOnAConstructedInput() {
        val unitSize = 24
        val decoyCount = 60
        val s = ByteArray(unitSize) { i -> if (i < 4) 'A'.code.toByte() else ('B'.code + i).toByte() }

        fun decoy(index: Int): ByteArray = ByteArray(unitSize) { i ->
            when {
                i < 4 -> 'A'.code.toByte()

                i == 4 -> index.toByte()

                // distinct per decoy, and < 'B'.code so it never matches `s`
                else -> lcgByte(seed = 1000 + index, position = i)
            }
        }

        val builder = ArrayList<Byte>(unitSize * (decoyCount + 2))
        s.forEach { builder.add(it) }
        for (idx in 0 until decoyCount) decoy(idx).forEach { b -> builder.add(b) }
        s.forEach { builder.add(it) }
        val input = ByteArray(builder.size) { builder[it] }

        val sizeAtLevel1 = Zstd.compress(input, 1).size
        val sizeAtLevel22 = Zstd.compress(input, 22).size
        assertTrue(
            sizeAtLevel22 < sizeAtLevel1,
            "expected a strictly smaller frame at level 22 ($sizeAtLevel22 b) than level 1 ($sizeAtLevel1 b)",
        )
    }

    /** Deterministic filler byte, mixed by both [seed] and [position] so no two calls collide. */
    private fun lcgByte(seed: Int, position: Int): Byte {
        var s = seed * 31 + position
        s = (s * 1103515245 + 12345) and 0x7FFFFFFF
        return (s ushr 16).toByte()
    }
}
