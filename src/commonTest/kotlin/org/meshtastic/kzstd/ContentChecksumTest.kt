// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Decode-side validation of a frame's Content_Checksum (RFC 8878 §3.1.1).
 * kzstd's OWN encoder never sets Content_Checksum_Flag (see
 * [ByteIdenticalRegressionTest]), so these frames are hand-built byte-for-byte
 * from real `zstd` CLI output (`zstd -19 <file>`, default checksum ON) to
 * exercise the decode path on every target without the JVM-only zstd-jni
 * dependency (that oracle lives in `KzstdLibzstdInteropTest`, jvmTest-only).
 */
class ContentChecksumTest {

    private val max = TestVectors.MAX_DECOMPRESSED_SIZE

    // magic(4) + descriptor(0x24: single-segment + checksum) + content-size(1B) +
    // raw-block-header(3B) + block bytes + 4-byte checksum trailer.
    private fun frame(
        descriptor: Int,
        contentSizeByte: Int,
        blockHeader: Int,
        content: ByteArray,
        checksum: Int,
    ): ByteArray {
        val out = mutableListOf<Byte>()
        out += byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()).toList()
        out += descriptor.toByte()
        out += contentSizeByte.toByte()
        out += (blockHeader and 0xFF).toByte()
        out += ((blockHeader ushr 8) and 0xFF).toByte()
        out += ((blockHeader ushr 16) and 0xFF).toByte()
        out += content.toList()
        out += (checksum and 0xFF).toByte()
        out += ((checksum ushr 8) and 0xFF).toByte()
        out += ((checksum ushr 16) and 0xFF).toByte()
        out += ((checksum ushr 24) and 0xFF).toByte()
        return out.toByteArray()
    }

    @Test
    fun validChecksumOnEmptyContentDecodes() {
        // Captured from `zstd -19` on an empty file: 28b52ffd240001000099e9d851
        val f =
            frame(
                descriptor = 0x24,
                contentSizeByte = 0x00,
                blockHeader = 0x000001,
                content = ByteArray(0),
                checksum = 0x51d8e999,
            )
        assertContentEquals(ByteArray(0), Zstd.decompress(f, max))
    }

    @Test
    fun validChecksumOnAbcContentDecodes() {
        // Captured from `zstd -19` on a file containing "abc":
        // 28b52ffd2403190000616263990977ad
        val f = frame(
            descriptor = 0x24,
            contentSizeByte = 0x03,
            blockHeader = 0x000019,
            content = "abc".encodeToByteArray(),
            checksum = 0xad770999.toInt(),
        )
        assertContentEquals("abc".encodeToByteArray(), Zstd.decompress(f, max))
    }

    @Test
    fun wrongChecksumThrows() {
        val f = frame(
            descriptor = 0x24,
            contentSizeByte = 0x03,
            blockHeader = 0x000019,
            content = "abc".encodeToByteArray(),
            checksum = 0xad770998.toInt(), // one bit flipped from the real value
        )
        assertFailsWith<ZstdException> { Zstd.decompress(f, max) }
    }

    @Test
    fun truncatedChecksumTrailerThrows() {
        val full = frame(
            descriptor = 0x24,
            contentSizeByte = 0x03,
            blockHeader = 0x000019,
            content = "abc".encodeToByteArray(),
            checksum = 0xad770999.toInt(),
        )
        // Drop the last 2 of the 4 checksum bytes: content decodes fine but the
        // trailer read must fail closed, not silently skip validation.
        assertFailsWith<ZstdException> { Zstd.decompress(full.copyOfRange(0, full.size - 2), max) }
    }
}
