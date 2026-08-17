// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

import org.meshtastic.kzstd.internal.Xxh64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * XXH64 correctness against known reference vectors (seed 0). The low-32-bits
 * of each of these is exactly the 4-byte trailer real `zstd` CLI wrote for the
 * same content in a checksummed frame — extracted with `zstd -19 <file>` and
 * confirmed byte-for-byte against these full 64-bit values, so this is
 * checked against real libzstd, not just re-derived from this same port.
 */
class Xxh64Test {

    @Test
    fun emptyInput() {
        assertEquals(-0x10B924C8AE271667L, Xxh64.hash(ByteArray(0)))
    }

    @Test
    fun singleByte() {
        assertEquals(-0x2DB13B0E567391A5L, Xxh64.hash(byteArrayOf('a'.code.toByte())))
    }

    @Test
    fun threeBytes() {
        assertEquals(
            0x44BC2CF5AD770999L,
            Xxh64.hash(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())),
        )
    }

    @Test
    fun twoHundredBytePattern() {
        val data = ByteArray(200) { (it % 251).toByte() }
        // Full 64-bit value unknown here (only the low 32 bits were verified
        // against the zstd CLI's checksum trailer); this pins the low 32 bits,
        // which is the only part RFC 8878 actually uses.
        assertEquals(0xB99E879CL, Xxh64.hash(data) and 0xFFFFFFFFL)
    }

    @Test
    fun sliceOffsetMatchesWholeArrayHash() {
        val padded = byteArrayOf(0, 0, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0)
        assertEquals(
            Xxh64.hash(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())),
            Xxh64.hash(padded, 2, 3),
        )
    }
}
