// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd.internal

// Builds a canonical Huffman table for a block's OWN literals (RFC 8878 §4.2.1,
// Literals_Block_Type 2 "Huffman_Compressed") from that block's byte histogram,
// together with the Huffman_Tree_Description that tells a decoder how to
// rebuild it.
//
// The construction deliberately goes the long way round — histogram → code
// lengths → per-symbol weights → HuffmanTable.fromWeights (the DECODER's own
// table build) → HuffmanEncTable.fromDecodeTable — instead of assigning codes
// directly. Canonical-code assignment then happens exactly once, in the routine
// that already agrees with libzstd (it is what reads libzstd's own
// descriptions), so the encoder cannot drift from the decoder's idea of which
// code belongs to which symbol.

/**
 * Longest literal code this encoder will produce. zstd's own encoder caps
 * Huffman depth here (`HUF_TABLELOG_DEFAULT`); the format's ceiling is
 * [HuffmanTable.HUF_MAX_TABLELOG], one higher, so staying at 11 keeps every
 * description comfortably inside what any decoder accepts.
 */
internal const val MAX_LITERAL_CODE_BITS: Int = 11

/**
 * The most explicit weights a DIRECT (non-FSE-compressed) tree description can
 * carry: its header byte is `127 + Number_of_Weights`, which must fit in a
 * byte. Weights are written for symbols `0 until lastSymbol` and the final
 * present symbol's weight is implied, so the highest literal byte value this
 * encoder can Huffman-code is 128. Blocks containing a higher byte value fall
 * back to Raw literals — FSE-compressed weight descriptions, which would lift
 * the limit, are not implemented.
 */
private const val MAX_DIRECT_WEIGHTS = 128

/**
 * A freshly built literals Huffman table plus its wire description.
 *
 * [decoder] is the decode-side table [encoder] was derived from — the same one a
 * reader rebuilds from [description]. The encoder keeps it so that a later
 * block's Treeless literals name exactly the table the decoder is holding.
 */
internal class FreshHuffmanTable(val encoder: HuffmanEncTable, val decoder: HuffmanTable, val description: ByteArray)

/**
 * Build a Huffman table for the alphabet [histogram] describes (counts per byte
 * value), or null when this block cannot use one:
 *  - fewer than two distinct byte values (one symbol is the RLE literals case,
 *    and the format cannot describe a one-symbol tree: the sole symbol's weight
 *    is the implied one, leaving nothing explicit to imply it from);
 *  - a byte value above [MAX_DIRECT_WEIGHTS].
 */
internal fun buildLiteralsHuffman(histogram: IntArray): FreshHuffmanTable? {
    val present = ArrayList<Int>(16)
    for (s in histogram.indices) if (histogram[s] > 0) present.add(s)
    if (present.size < 2) return null
    val lastSymbol = present[present.size - 1]
    if (lastSymbol > MAX_DIRECT_WEIGHTS) return null

    val lengths = huffmanCodeLengths(histogram, present)
    var maxBits = 0
    for (s in present) if (lengths[s] > maxBits) maxBits = lengths[s]

    // Weight = Max_Number_of_Bits + 1 - Number_of_Bits (RFC 8878 §4.2.1.3), so
    // the longest code has weight 1 and an absent symbol weight 0.
    val weights = IntArray(lastSymbol + 1)
    for (s in present) weights[s] = maxBits + 1 - lengths[s]

    // The description carries weights for symbols 0 until lastSymbol; the last
    // present symbol's weight is whatever makes the total a power of two, which
    // fromWeights recomputes.
    val explicit = IntArray(lastSymbol) { weights[it] }
    val decode = HuffmanTable.fromWeights(explicit, lastSymbol)
    return FreshHuffmanTable(HuffmanEncTable.fromDecodeTable(decode), decode, writeDirectWeights(explicit))
}

/**
 * Huffman_Tree_Description in the DIRECT form (RFC 8878 §4.2.1.2): a header
 * byte of `127 + Number_of_Weights`, then the weights packed two per byte, high
 * nibble first (an odd count leaves the final low nibble zero).
 */
private fun writeDirectWeights(weights: IntArray): ByteArray {
    val out = ByteArray(1 + (weights.size + 1) / 2)
    out[0] = (127 + weights.size).toByte()
    var i = 0
    while (i < weights.size) {
        val high = weights[i] shl 4
        val low = if (i + 1 < weights.size) weights[i + 1] else 0
        out[1 + i / 2] = (high or low).toByte()
        i += 2
    }
    return out
}

/**
 * Code length per symbol (0 for absent), optimal for [histogram] except that no
 * code exceeds [MAX_LITERAL_CODE_BITS].
 *
 * Builds the Huffman tree, keeps only the resulting MULTISET of depths, repairs
 * that multiset to respect the depth limit while staying a COMPLETE code (Kraft
 * sum exactly 1 — an incomplete code would make the description's implied final
 * weight come out wrong), then hands the shortest codes to the most frequent
 * symbols. Reassigning at the end is what makes the repair safe: it can shuffle
 * depths freely without ever pairing a long code with a frequent symbol.
 */
private fun huffmanCodeLengths(histogram: IntArray, present: List<Int>): IntArray {
    val depths = huffmanTreeDepths(histogram, present)

    // Symbols per code length, with anything past the limit clamped onto it.
    val countPerLength = IntArray(MAX_LITERAL_CODE_BITS + 1)
    for (d in depths) countPerLength[if (d > MAX_LITERAL_CODE_BITS) MAX_LITERAL_CODE_BITS else d]++

    balanceKraftSum(countPerLength)

    // Shortest lengths to the highest counts; ties by ascending symbol so the
    // result is deterministic on every target.
    val ordered = present.sortedWith(compareByDescending<Int> { histogram[it] }.thenBy { it })
    val lengths = IntArray(histogram.size)
    var i = 0
    for (len in 1..MAX_LITERAL_CODE_BITS) {
        repeat(countPerLength[len]) { lengths[ordered[i++]] = len }
    }
    return lengths
}

/**
 * Make the code lengths in [countPerLength] a complete code — Kraft sum exactly
 * `2^MAX_LITERAL_CODE_BITS` in units of `2^-MAX_LITERAL_CODE_BITS` — after
 * clamping over-long codes onto the limit.
 *
 * Clamping only ever makes codes SHORTER, so the sum can overrun; each repair
 * step lengthens one code by a bit (the longest that is still below the limit,
 * which is the cheapest place to spend a bit). Overshooting leaves an
 * INCOMPLETE code, so the second loop shortens the longest codes back until the
 * sum lands exactly on the target — always possible, because every term is a
 * multiple of the smallest one.
 */
private fun balanceKraftSum(countPerLength: IntArray) {
    val target = 1 shl MAX_LITERAL_CODE_BITS
    var kraft = 0
    for (len in 1..MAX_LITERAL_CODE_BITS) kraft += countPerLength[len] shl (MAX_LITERAL_CODE_BITS - len)

    while (kraft > target) {
        var len = MAX_LITERAL_CODE_BITS - 1
        while (countPerLength[len] == 0) len--
        countPerLength[len]--
        countPerLength[len + 1]++
        kraft -= 1 shl (MAX_LITERAL_CODE_BITS - len - 1)
    }
    while (kraft < target) {
        var len = MAX_LITERAL_CODE_BITS
        while (countPerLength[len] == 0) len--
        countPerLength[len]--
        countPerLength[len - 1]++
        kraft += 1 shl (MAX_LITERAL_CODE_BITS - len)
    }
}

/**
 * Depth of each leaf in the (unconstrained) Huffman tree over [present]'s
 * counts, returned in no particular order — only the multiset of depths is
 * used.
 *
 * Classic construction: repeatedly merge the two lightest live nodes. The
 * alphabet is at most 256 symbols, so the O(n^2) scan for those two costs
 * nothing measurable and avoids a heap. Children are always created before
 * their parent, so one reverse pass over the node array propagates depths from
 * the root outward.
 */
private fun huffmanTreeDepths(histogram: IntArray, present: List<Int>): IntArray {
    val leaves = present.size
    val nodes = 2 * leaves - 1
    val weight = LongArray(nodes)
    val left = IntArray(nodes)
    val right = IntArray(nodes)
    val live = BooleanArray(nodes)
    for (i in 0 until leaves) {
        weight[i] = histogram[present[i]].toLong()
        live[i] = true
    }

    var next = leaves
    while (next < nodes) {
        var first = -1
        var second = -1
        for (n in 0 until next) {
            if (!live[n]) continue
            if (first < 0 || weight[n] < weight[first]) {
                second = first
                first = n
            } else if (second < 0 || weight[n] < weight[second]) {
                second = n
            }
        }
        weight[next] = weight[first] + weight[second]
        left[next] = first
        right[next] = second
        live[first] = false
        live[second] = false
        live[next] = true
        next++
    }

    val depth = IntArray(nodes)
    for (n in nodes - 1 downTo leaves) {
        depth[left[n]] = depth[n] + 1
        depth[right[n]] = depth[n] + 1
    }
    return IntArray(leaves) { depth[it] }
}
