// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd.internal

/**
 * Pure-Kotlin XXH64 (64-bit xxHash), the algorithm RFC 8878 §3.1.1 mandates
 * for a zstd frame's optional Content_Checksum: "the least significant 32
 * bits of the XXH64() checksum of the original decompressed data, seed 0".
 *
 * This is a standalone, common-Kotlin port of the reference XXH64 algorithm
 * (no external dependency — kzstd is zero-runtime-dependency). It operates on
 * a whole [ByteArray] (or a `[offset, offset+length)` slice), matching how the
 * decoder needs it: hash the fully-decoded frame content once, after the
 * block loop completes.
 */
internal object Xxh64 {

    private const val PRIME1 = -0x61c8864e7a143579L // 0x9E3779B185EBCA87
    private const val PRIME2 = -0x3d4d51c2d82b14b1L // 0xC2B2AE3D27D4EB4F
    private const val PRIME3 = 0x165667B19E3779F9L
    private const val PRIME4 = -0x7a1435883d4d519dL // 0x85EBCA77C2B2AE63
    private const val PRIME5 = 0x27D4EB2F165667C5L

    /** Hash the full [data] array with [seed] (RFC 8878 uses seed 0). */
    fun hash(data: ByteArray, seed: Long = 0L): Long = hash(data, 0, data.size, seed)

    /** Hash `data[offset until offset+length]` with [seed]. */
    fun hash(data: ByteArray, offset: Int, length: Int, seed: Long = 0L): Long {
        var i = offset
        val end = offset + length
        var h64: Long

        if (length >= 32) {
            var v1 = seed + PRIME1 + PRIME2
            var v2 = seed + PRIME2
            var v3 = seed
            var v4 = seed - PRIME1
            val limit = end - 32
            while (i <= limit) {
                v1 = round(v1, readLE64(data, i))
                v2 = round(v2, readLE64(data, i + 8))
                v3 = round(v3, readLE64(data, i + 16))
                v4 = round(v4, readLE64(data, i + 24))
                i += 32
            }
            h64 = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18)
            h64 = mergeRound(h64, v1)
            h64 = mergeRound(h64, v2)
            h64 = mergeRound(h64, v3)
            h64 = mergeRound(h64, v4)
        } else {
            h64 = seed + PRIME5
        }

        h64 += length.toLong()

        while (i + 8 <= end) {
            val k1 = round(0L, readLE64(data, i))
            h64 = h64 xor k1
            h64 = rotl(h64, 27) * PRIME1 + PRIME4
            i += 8
        }

        if (i + 4 <= end) {
            h64 = h64 xor ((readLE32(data, i)) * PRIME1)
            h64 = rotl(h64, 23) * PRIME2 + PRIME3
            i += 4
        }

        while (i < end) {
            h64 = h64 xor ((data[i].toLong() and 0xFF) * PRIME5)
            h64 = rotl(h64, 11) * PRIME1
            i++
        }

        h64 = h64 xor (h64 ushr 33)
        h64 *= PRIME2
        h64 = h64 xor (h64 ushr 29)
        h64 *= PRIME3
        h64 = h64 xor (h64 ushr 32)
        return h64
    }

    private fun round(acc: Long, input: Long): Long {
        var a = acc + input * PRIME2
        a = rotl(a, 31)
        a *= PRIME1
        return a
    }

    private fun mergeRound(acc: Long, v: Long): Long {
        val merged = acc xor round(0L, v)
        return merged * PRIME1 + PRIME4
    }

    private fun rotl(x: Long, r: Int): Long = (x shl r) or (x ushr (64 - r))

    private fun readLE64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun readLE32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
