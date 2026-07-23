// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd.internal

/**
 * A reusable hash-chain index over a dictionary's CONTENT, so the encoder can
 * back-reference the dictionary cheaply.
 *
 * A trained dictionary's content can be hundreds of KB; hashing it on every
 * packet would dominate compression time. [build] indexes the content ONCE; the
 * public `ZstdDictionary` holds the resulting [MatchIndex] for its lifetime and
 * reuses it across every compress call, so the dictionary content is hashed once.
 *
 * Each bucket holds a chain of dict-content positions sharing a 4-byte prefix
 * hash. [forEachCandidate] walks the most-recent few positions for a given
 * 4-byte key, bounded so a degenerate (highly repetitive) dictionary can't make
 * a single lookup O(dictLen).
 *
 * Positions are dict-content indices; the encoder expresses match distances
 * against the virtual `[dictContent ++ input]` history, so a dict position `p`
 * is at history position `p`.
 */
internal class MatchIndex private constructor(
    private val content: ByteArray,
    private val head: IntArray,
    private val prev: IntArray,
) {

    /** Walk up to [MAX_CANDIDATES] recent dict positions whose 4-byte prefix == [key]. */
    inline fun forEachCandidate(key: Int, action: (dictPos: Int) -> Unit) {
        var p = head[hash(key)]
        var n = 0
        while (p >= 0 && n < MAX_CANDIDATES) {
            // Confirm the 4-byte prefix actually matches (hash collisions exist).
            if (first4(p) == key) action(p)
            p = prev[p]
            n++
        }
    }

    @PublishedApi
    internal fun first4(p: Int): Int {
        val b0 = content[p].toInt() and 0xFF
        val b1 = content[p + 1].toInt() and 0xFF
        val b2 = content[p + 2].toInt() and 0xFF
        val b3 = content[p + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    companion object {
        const val HASH_LOG = 17
        const val HASH_SIZE = 1 shl HASH_LOG
        const val MIN_MATCH = 3

        /**
         * How many positions in a hash bucket to examine per lookup. Bounds the
         * matcher so a repetitive dictionary can't make compression quadratic.
         */
        @PublishedApi
        internal const val MAX_CANDIDATES = 32

        @PublishedApi
        internal fun hash(v: Int): Int = ((v * -1640531527) ushr (32 - HASH_LOG)) and (HASH_SIZE - 1)

        /** Build a hash-chain index over [content]. Called once per dictionary. */
        internal fun build(content: ByteArray): MatchIndex {
            val head = IntArray(HASH_SIZE) { -1 }
            val n = content.size
            val prev = IntArray(if (n > 0) n else 1) { -1 }
            // Insert every position that has 4 bytes available; chain newest-first
            // so the most-recent (closest, smallest-distance) match is tried first.
            var p = 0
            val last = n - MIN_MATCH - 1
            while (p <= last) {
                val key = (content[p].toInt() and 0xFF) or
                    ((content[p + 1].toInt() and 0xFF) shl 8) or
                    ((content[p + 2].toInt() and 0xFF) shl 16) or
                    ((content[p + 3].toInt() and 0xFF) shl 24)
                val h = hash(key)
                prev[p] = head[h]
                head[h] = p
                p++
            }
            return MatchIndex(content, head, prev)
        }
    }
}
