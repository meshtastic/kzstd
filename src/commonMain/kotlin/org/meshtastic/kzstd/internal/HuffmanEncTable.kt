// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd.internal

/**
 * Huffman ENCODING table for the literals section (RFC 8878 §4.2.1) — the
 * inverse of [HuffmanTable].
 *
 * Derived directly from an already-built decode [HuffmanTable] (e.g. one parsed
 * from a trained [ParsedDictionary]'s stored entropy tables) rather than from
 * per-symbol weights: [HuffmanTable.fromWeights] lays each present symbol out as
 * a contiguous, alignment-guaranteed slot range `[start, start + 2^(maxBits -
 * length))` in its flat `symbols`/`numBits` arrays, so a single scan over those
 * arrays recovers each symbol's code length AND its canonical code (the shared
 * high bits of every slot in its range) with no separate weight bookkeeping.
 * This guarantees the derived encoder produces bitstreams the SAME decode table
 * (and libzstd, which builds an identical canonical table from the same
 * weights) can read back.
 */
internal class HuffmanEncTable private constructor(
    /** symbols with no code (never appear in the source alphabet) have length 0. */
    private val lengths: IntArray,
    private val codes: IntArray,
) {
    /** Whether [symbol] (0..255) has an assigned code in this table. */
    fun isCovered(symbol: Int): Boolean = lengths[symbol] > 0

    /** Code length in bits for [symbol]. Only meaningful when [isCovered]. */
    fun bitLength(symbol: Int): Int = lengths[symbol]

    /** Write [symbol]'s canonical code to [bw]. Only valid when [isCovered]. */
    fun encode(bw: ReverseBitWriter, symbol: Int) {
        bw.writeBits(codes[symbol], lengths[symbol])
    }

    companion object {
        private const val NUM_SYMBOLS = 256

        fun fromDecodeTable(decode: HuffmanTable): HuffmanEncTable {
            val lengths = IntArray(NUM_SYMBOLS)
            val codes = IntArray(NUM_SYMBOLS)
            val tableSize = 1 shl decode.maxBits
            var slot = 0
            while (slot < tableSize) {
                val symbol = decode.symbols[slot].toInt() and 0xFF
                val length = decode.numBits[slot].toInt()
                // First slot seen for this symbol: every slot in its
                // [slot, slot + span) range shares the same top `length` bits,
                // which IS the canonical code — read it off this first slot.
                if (lengths[symbol] == 0) {
                    lengths[symbol] = length
                    codes[symbol] = slot ushr (decode.maxBits - length)
                }
                slot += 1 shl (decode.maxBits - length)
            }
            return HuffmanEncTable(lengths, codes)
        }
    }
}
