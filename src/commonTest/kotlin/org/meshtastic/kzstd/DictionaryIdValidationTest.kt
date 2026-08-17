// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.ParsedDictionary
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * RFC 8878 §5 / §3.1.1.3: a proper Zstandard-format dictionary carries its own
 * embedded Dictionary_ID, and a frame may declare the Dictionary_ID it expects.
 * These are pure parsing/plumbing checks that run on every target; the actual
 * mismatch-throws-ZstdException behavior against a REAL libzstd-produced frame
 * (which is what sets a non-zero frame Dictionary_ID in practice -- kzstd's own
 * encoder never does) is covered by the jvmTest zstd-jni oracle, since only a
 * real libzstd frame exercises the declares-an-ID path end to end.
 */
class DictionaryIdValidationTest {

    @Test
    fun trainedDictCapturesItsEmbeddedId() {
        val parsed = ParsedDictionary.parse(TestVectors.trainedDict.copyOf())
        assertEquals(TestVectors.trainedDictId, parsed.dictionaryId)
    }

    @Test
    fun rawContentDictionaryHasNoEmbeddedId() {
        val rawContent = "just some shared prefix bytes, not a trained dict".encodeToByteArray()
        val parsed = ParsedDictionary.parse(rawContent)
        assertEquals(0, parsed.dictionaryId)
    }

    @Test
    fun emptyDictionaryHasNoEmbeddedId() {
        assertEquals(0, ZstdDictionary.EMPTY.parsed.dictionaryId)
    }

    @Test
    fun kzstdOwnFramesNeverDeclareAFrameDictionaryId_soWrongDictStillDecodes() {
        // kzstd's encoder always sets Dictionary_ID_Flag=0 (RFC-legal), so a frame
        // it produces never asks the decoder to validate an ID -- decoding with a
        // DIFFERENT (but still valid) dictionary must stay possible, exactly as
        // before this change (full backward compatibility with unversioned callers).
        val dict = ZstdDictionary(TestVectors.trainedDict)
        val otherDict = ZstdDictionary(TestVectors.trainedDict.copyOf().also { it[4] = (it[4] + 1).toByte() })
        val sample = TestVectors.structured[0]

        val frame = Zstd.compress(sample, dict)
        val back = Zstd.decompress(frame, otherDict, TestVectors.MAX_DECOMPRESSED_SIZE)
        assertContentEquals(sample, back)
    }
}
