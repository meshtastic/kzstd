// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.PureZstdDecoder
import org.meshtastic.kzstd.internal.PureZstdEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [PureZstdEncoder.resolveOffsetCode] — the encode-side counterpart of
 * [PureZstdDecoder.applyOffset] — both in isolation (does it pick the right
 * `offsetValue` and rotate `rep` the way the spec says) and by cross-checking it
 * directly against the decoder's own rotation logic (does encode-then-decode
 * recover the original distance, sequence after sequence, with a SHARED,
 * correctly-evolving `rep` state on both sides).
 *
 * This is the highest-risk workstream in the encoder-parity plan: an off-by-one
 * in the rotation would often still decode correctly for the sequence it was
 * introduced on, and only surface as corruption on a LATER sequence that hits a
 * repeat-offset code against the now-wrong `rep` state — exactly the kind of bug
 * a plain round-trip test over natural-language/JSON corpora could miss by luck.
 */
class RepeatOffsetCodeTest {

    @Test
    fun repCode0_matchesRep0_noRotation() {
        val rep = intArrayOf(10, 20, 30)
        val offsetValue = PureZstdEncoder.resolveOffsetCode(distance = 10, litLen = 5, rep = rep)
        assertEquals(1, offsetValue)
        assertContentEquals(intArrayOf(10, 20, 30), rep, "repCode 0 must not rotate rep")
    }

    @Test
    fun repCode1_matchesRep1_swapsRep0AndRep1() {
        val rep = intArrayOf(10, 20, 30)
        val offsetValue = PureZstdEncoder.resolveOffsetCode(distance = 20, litLen = 5, rep = rep)
        assertEquals(2, offsetValue)
        assertContentEquals(intArrayOf(20, 10, 30), rep)
    }

    @Test
    fun repCode2_matchesRep2_rotatesAllThree() {
        val rep = intArrayOf(10, 20, 30)
        val offsetValue = PureZstdEncoder.resolveOffsetCode(distance = 30, litLen = 5, rep = rep)
        assertEquals(3, offsetValue)
        assertContentEquals(intArrayOf(30, 10, 20), rep)
    }

    @Test
    fun explicitOffset_whenDistanceMatchesNoRepSlot_shiftsAllThree() {
        val rep = intArrayOf(10, 20, 30)
        val offsetValue = PureZstdEncoder.resolveOffsetCode(distance = 99, litLen = 5, rep = rep)
        assertEquals(102, offsetValue) // 99 + 3
        assertContentEquals(intArrayOf(99, 10, 20), rep)
    }

    @Test
    fun litLenZero_repCode1Form_matchesRep1() {
        val rep = intArrayOf(10, 20, 30)
        val offsetValue = PureZstdEncoder.resolveOffsetCode(distance = 20, litLen = 0, rep = rep)
        assertEquals(1, offsetValue)
        assertContentEquals(intArrayOf(20, 10, 30), rep)
    }

    @Test
    fun litLenZero_repCode2Form_matchesRep2() {
        val rep = intArrayOf(10, 20, 30)
        val offsetValue = PureZstdEncoder.resolveOffsetCode(distance = 30, litLen = 0, rep = rep)
        assertEquals(2, offsetValue)
        assertContentEquals(intArrayOf(30, 10, 20), rep)
    }

    @Test
    fun litLenZero_distanceMatchesRep0Only_fallsBackToExplicit() {
        // Deliberately excluded case: litLen==0 has no cheap code for rep[0]
        // (that slot is reserved for the underflow-prone "rep[0]-1" form, which
        // this encoder never emits) -- must fall through to an explicit offset,
        // which rotates rep the same way any other explicit offset does.
        val rep = intArrayOf(10, 20, 30)
        val offsetValue = PureZstdEncoder.resolveOffsetCode(distance = 10, litLen = 0, rep = rep)
        assertEquals(13, offsetValue) // 10 + 3, NOT a repeat code
        assertContentEquals(intArrayOf(10, 10, 20), rep)
    }

    /**
     * Encode/decode consistency: run a realistic mix of repeated and fresh
     * offsets through [PureZstdEncoder.resolveOffsetCode] with one `rep` copy,
     * and independently through [PureZstdDecoder.applyOffset] with an
     * identically-seeded second `rep` copy, in the same chronological order a
     * real sequence stream is built and read. Every recovered distance must
     * equal the original -- this is what actually catches a subtle rotation bug,
     * since a wrong-but-consistent rotation would still often decode correctly
     * on the very sequence it corrupts, only diverging later.
     */
    @Test
    fun encodeThenDecodeRecoversEveryDistance_acrossARepeatOffsetHeavySequence() {
        val encRep = intArrayOf(1, 4, 8)
        val decRep = intArrayOf(1, 4, 8)

        // litLen, distance pairs: a mix of fresh offsets, immediate repeats, and
        // litLen==0 sequences (PLI/marker-style zero-literal matches) chosen to
        // exercise rep0/rep1/rep2 hits and the excluded litLen==0+rep0 case at
        // various points in an evolving rep state -- exactly which branch each
        // step lands on depends on the running state, which is the point: this
        // test doesn't assert on that, only that encoder and decoder stay in
        // lockstep across the whole stream regardless.
        val sequenceStream = listOf(
            5 to 50,
            3 to 50, // repeats the previous distance
            0 to 4,
            2 to 8,
            0 to 1,
            4 to 200, // fresh, unrelated to anything seen so far
            1 to 200, // repeats the previous distance
        )

        for ((litLen, distance) in sequenceStream) {
            val offsetValue = PureZstdEncoder.resolveOffsetCode(distance, litLen, encRep)
            val recovered = PureZstdDecoder.applyOffset(offsetValue, litLen, decRep)
            assertEquals(distance, recovered, "distance mismatch for litLen=$litLen distance=$distance")
            assertContentEquals(encRep, decRep, "rep state diverged after litLen=$litLen distance=$distance")
        }
    }

    /**
     * Adoption guard: [PureZstdEncoder.resolveOffsetCode] being correct in
     * isolation doesn't prove the encoder's real match-finding path ever
     * reaches it. Compress the dict-trained `structured` corpus with kzstd's
     * own encoder and confirm at least one sequence actually carries a
     * repeat-offset code (`offsetValue` in `1..3`) -- otherwise this whole
     * workstream could silently fall back to always-explicit offsets while
     * every other test (round-trip, byte-identical, libzstd interop) stays
     * green for a reason unrelated to repeat-offset correctness.
     */
    @Test
    fun compressingStructuredDictCorpus_actuallyEmitsAtLeastOneRepeatOffsetCode() {
        val dict = ZstdDictionary(TestVectors.trainedDict)
        var sawRepeatCode = false

        for (sample in TestVectors.structured) {
            val frame = Zstd.compress(sample, dict)
            PureZstdDecoder.decode(frame, dict.parsed, TestVectors.MAX_DECOMPRESSED_SIZE) { offsetValue ->
                if (offsetValue in 1..3) sawRepeatCode = true
            }
        }

        assertTrue(sawRepeatCode, "no sample in the structured dict corpus produced a repeat-offset sequence code")
    }
}
