// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the encoder's CURRENT frame bytes for a few fixed inputs as a forward drift
 * tripwire: it fails if a future change alters the compression output (R3 "logic
 * unchanged"). The encoder was moved into kzstd verbatim (only the decoder's Huffman
 * weight guard was corrected — a decode-side fix that cannot affect encoder output),
 * and `KzstdLibzstdInteropTest` independently proves these frames are valid,
 * libzstd-decodable output. This test does NOT compare against captured
 * original-engine bytes; it guards against future drift only.
 *
 * To refresh after an INTENTIONAL encoder change: print `hex(Zstd.compress(...))`
 * for each input and paste the new expected values below.
 */
class ByteIdenticalRegressionTest {

    private val dict = ZstdDictionary(TestVectors.trainedDict)

    private fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    @Test
    fun encoderFramesAreStable() {
        assertEquals("28b52ffd0000010000", hex(Zstd.compress(ByteArray(0))), "empty")
        assertEquals("28b52ffd0000190000616263", hex(Zstd.compress("abc".encodeToByteArray())), "abc")
        assertEquals(
            "28b52ffd00008d00005868656c6c6f20776f726c640100e14a11",
            hex(Zstd.compress("hello hello hello world".encodeToByteArray())),
            "repetitive text",
        )
        assertEquals(
            "28b52ffd000055040074077b2274797065223a22686561727462656174222c22736571223a363438" +
                "3838362c226e6f642d32337461746f6b222c226c6174223a2d36382e32303131302c226c6f6e22" +
                "3a2d32352e36383235352c226d7367223a22726567696f6e20616e64206e6f6d696e616c6d6f6e" +
                "69746f72656420746865227d0600bbf07ba560cd1841c5a804e808d413",
            hex(Zstd.compress(TestVectors.structured[0])),
            "structured, dict-less",
        )
        assertEquals(
            "28b52ffd002085010088363438383836382e3232352e36383235350900" +
                "8f8705049a3d40dcc301ae4267d4a3002c180cb48003aa0f4b651b20",
            hex(Zstd.compress(TestVectors.structured[0], dict)),
            "structured, trained dict",
        )
    }
}
