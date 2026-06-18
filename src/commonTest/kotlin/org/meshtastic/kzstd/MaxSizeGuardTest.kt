// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The decompression-bomb guard (R7): a small frame can expand greatly, so
 * `decompress` must reject output that would exceed the caller-supplied `maxSize`
 * BEFORE growing the buffer to that size.
 */
class MaxSizeGuardTest {

    @Test
    fun decompressRejectsOutputThatExceedsMaxSize() {
        // 4000 identical bytes compress to a tiny self-referential frame but expand
        // back to 4000 on decode.
        val original = ByteArray(4000) { 'Z'.code.toByte() }
        val frame = Zstd.compress(original)
        assertTrue(frame.size < original.size, "expected a tiny frame, got ${frame.size}")

        // A cap below the true output must throw rather than allocate 4000 bytes.
        assertFailsWith<ZstdException> { Zstd.decompress(frame, maxSize = 1024) }

        // With an adequate cap it round-trips exactly.
        val back = Zstd.decompress(frame, maxSize = 8192)
        assertEquals(original.size, back.size)
    }
}
