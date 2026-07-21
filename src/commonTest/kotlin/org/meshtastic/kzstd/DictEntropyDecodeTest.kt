// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Decodes a pinned, real libzstd-with-trained-dict frame that uses TREELESS literals
 * (it references the dictionary's Huffman table) — on EVERY target.
 *
 * This is the cross-target guard for the dictionary-entropy DECODE path. kzstd's own
 * encoder emits only Raw literals + Predefined FSE, so its own frames never reach the
 * treeless / FSE-repeat / dict-Huffman branches. Without this pinned frame those
 * branches — including the Huffman weight-decode the one extraction fix corrected —
 * would be exercised only on the JVM (via zstd-jni in `KzstdLibzstdInteropTest`), never
 * on Native / JS / Wasm. The frame's provenance and that it is genuinely treeless are
 * asserted on the JVM by `KzstdLibzstdInteropTest.libzstdActuallyEmitsTreelessDictFrames`.
 */
class DictEntropyDecodeTest {

    @Test
    fun decodesPinnedTreelessDictFrameOnEveryTarget() {
        val dict = ZstdDictionary(TestVectors.trainedDict)
        val out = Zstd.decompress(TestVectors.treelessDictFrame, dict, TestVectors.MAX_DECOMPRESSED_SIZE)
        assertContentEquals(TestVectors.treelessDictPlaintext, out)
    }
}
