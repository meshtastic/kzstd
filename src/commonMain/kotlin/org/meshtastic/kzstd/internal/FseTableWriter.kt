// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd.internal

import org.meshtastic.kzstd.ZstdException

// Builds an FSE table for one sequence symbol stream from THAT STREAM's own code
// counts (RFC 8878 §4.1.1, Symbol_Compression_Mode 2 "FSE_Compressed"), and
// writes the table description a decoder needs to rebuild it.
//
// Everything here is the exact inverse of code the decoder already has:
// normalized counts feed FseTable.build (the decoder's own table build, so
// encoder and decoder cannot disagree about the table), and the description
// writer mirrors parseFseTable field for field, including its shrinking field
// width and its run-length encoding of absent symbols.

/**
 * Smallest Accuracy_Log the description can carry: the header stores
 * `Accuracy_Log - 5` in 4 bits.
 */
internal const val FSE_MIN_TABLELOG: Int = 5

/** A freshly built sequence-stream FSE table plus its wire description. */
internal class FreshFseTable(val encoder: FseEncTable, val description: ByteArray)

/**
 * Build an FSE table for [codes] (one sequence stream's symbols, in any order —
 * only their counts matter), or null when a fresh table is not applicable:
 * fewer than two distinct codes is the RLE case, which is always smaller.
 *
 * [maxSymbol] and [maxLog] are the stream's format-defined bounds (e.g.
 * [OFFSET_MAX_SYMBOL] / [OFFSET_MAX_LOG]); the decoder parses the description
 * with the same pair, so the table it rebuilds is identical to the one returned
 * here.
 */
internal fun buildFreshFseTable(codes: IntArray, maxSymbol: Int, maxLog: Int): FreshFseTable? {
    if (codes.size < 2) return null
    val counts = IntArray(maxSymbol + 1)
    for (c in codes) counts[c]++
    var distinct = 0
    for (c in counts) if (c > 0) distinct++
    if (distinct < 2) return null

    var highestCode = maxSymbol
    while (highestCode > 0 && counts[highestCode] == 0) highestCode--
    val tableLog = fseTableLog(codes.size, highestCode, distinct, maxLog) ?: return null
    val normalized = normalizeFseCounts(counts, maxSymbol, tableLog)
    val decode = FseTable.build(normalized, maxSymbol, tableLog)
    return FreshFseTable(
        FseEncTable.fromDecodeTable(decode, maxSymbol),
        writeFseTableDescription(normalized, maxSymbol, tableLog),
    )
}

/**
 * Accuracy_Log for a stream of [count] symbols whose highest code is
 * [highestCode] and which uses [distinct] distinct codes, bounded by the
 * stream's [maxLog]; null when even the largest allowed table could not give
 * every code a cell (unreachable for the three sequence streams, whose
 * alphabets are far smaller than their tables).
 *
 * This is zstd's `FSE_optimalTableLog`: precision beyond roughly a quarter of
 * the symbol count buys nothing but description bytes, yet a table must also
 * stay coarse enough to be worth describing and wide enough to spread the
 * alphabet — hence the floor derived from both the symbol count and the
 * alphabet size, applied AFTER the count-derived ceiling.
 */
private fun fseTableLog(count: Int, highestCode: Int, distinct: Int, maxLog: Int): Int? {
    var log = maxLog
    val fromCount = highBit(count - 1) - 2
    if (fromCount < log) log = fromCount
    val floor = minOf(highBit(count) + 1, highBit(highestCode) + 2)
    if (floor > log) log = floor
    log = log.coerceIn(FSE_MIN_TABLELOG, maxLog)
    while ((1 shl log) < distinct && log < maxLog) log++
    return if ((1 shl log) < distinct) null else log
}

/**
 * Scale [counts] (0..[maxSymbol]) into a distribution summing to EXACTLY
 * `1 shl tableLog`, where every symbol that occurs keeps a nonzero probability
 * and every symbol that does not stays at zero.
 *
 * Deliberately never emits the "less than 1" probability (-1) that the format
 * allows and the decoder understands: giving rare symbols a full cell costs a
 * little ratio but keeps both this routine and the description writer free of
 * negative counts, which is the fiddliest corner of the encoding. The caller
 * guarantees `distinct <= 1 shl tableLog`, which is what makes a floor of 1 per
 * present symbol achievable.
 */
internal fun normalizeFseCounts(counts: IntArray, maxSymbol: Int, tableLog: Int): IntArray {
    val tableSize = 1 shl tableLog
    var total = 0L
    for (s in 0..maxSymbol) total += counts[s]
    if (total <= 0) throw ZstdException("FSE normalize: empty count distribution")

    val normalized = IntArray(maxSymbol + 1)
    var assigned = 0
    for (s in 0..maxSymbol) {
        val c = counts[s]
        if (c <= 0) continue
        // Round to nearest, but never below one cell.
        val share = ((c.toLong() * tableSize + total / 2) / total).toInt().coerceAtLeast(1)
        normalized[s] = share
        assigned += share
    }

    // Rounding leaves the total off by a little either way; settle it against
    // the symbol with the most cells, where a cell is worth the least.
    while (assigned != tableSize) {
        // Give a cell back / take one from the symbol holding the most, but
        // never leave a symbol that occurs with no cell at all.
        val floor = if (assigned > tableSize) 1 else 0
        var target = -1
        for (s in 0..maxSymbol) {
            val cells = normalized[s]
            if (cells > floor && (target < 0 || cells > normalized[target])) target = s
        }
        if (target < 0) {
            // Only reachable if more symbols occur than the table has cells,
            // which the caller's tableLog choice rules out.
            throw ZstdException("FSE normalize: $assigned cells cannot be reduced to $tableSize")
        }
        if (assigned > tableSize) {
            normalized[target]--
            assigned--
        } else {
            normalized[target]++
            assigned++
        }
    }
    return normalized
}

/**
 * Write the FSE_Table_Description for [normalized] (RFC 8878 §4.1.1, "FSE Table
 * Description") — the exact inverse of [parseFseTable].
 *
 * The description is a forward, LSB-first bit stream: a 4-bit biased
 * Accuracy_Log, then one field per symbol whose width shrinks as the remaining
 * probability budget drains, so common small counts cost fewer bits. A count
 * fits in `nbBits - 1` bits while it is below `max`; otherwise it takes the
 * full width, biased by `max` once it reaches `threshold`, which is exactly how
 * the reader decides whether to fetch that extra bit. Absent symbols after a
 * zero count are run-length encoded in 2-bit groups, a group of 3 meaning
 * "three more, keep reading".
 */
internal fun writeFseTableDescription(normalized: IntArray, maxSymbol: Int, tableLog: Int): ByteArray {
    val bits = ForwardBitWriter()
    val tableSize = 1 shl tableLog
    bits.write(tableLog - FSE_MIN_TABLELOG, 4)

    var remaining = tableSize + 1 // +1 for the reader's extra-accuracy bias
    var threshold = tableSize
    var nbBits = tableLog + 1
    var symbol = 0
    var previousIsZero = false

    while (remaining > 1 && symbol <= maxSymbol) {
        if (previousIsZero) {
            // Run of further absent symbols, in groups of up to 3.
            val start = symbol
            while (symbol <= maxSymbol && normalized[symbol] == 0) symbol++
            var skipped = symbol - start
            while (skipped >= 3) {
                bits.write(3, 2)
                skipped -= 3
            }
            bits.write(skipped, 2)
            previousIsZero = false
            continue
        }

        val count = normalized[symbol]
        symbol++
        val max = (2 * threshold - 1) - remaining
        val encoded = count + 1 // the reader subtracts this bias back off
        when {
            encoded >= threshold -> bits.write(encoded + max, nbBits)
            encoded >= max -> bits.write(encoded, nbBits)
            else -> bits.write(encoded, nbBits - 1)
        }
        remaining -= count
        previousIsZero = count == 0

        // Shrink the field width as the budget drains.
        while (remaining < threshold) {
            nbBits--
            threshold = threshold shr 1
        }
    }
    if (remaining != 1) {
        throw ZstdException("FSE description: distribution does not sum to $tableSize (remaining=$remaining)")
    }
    return bits.finish()
}

/**
 * Forward, LSB-first bit writer — the inverse of [ForwardBitReader], and used
 * only for FSE table descriptions. Bits fill the low end of the current byte
 * first; [finish] flushes any partial final byte zero-padded, which is what
 * makes the description byte-aligned at its end (where the reader resumes).
 */
internal class ForwardBitWriter {
    private val bytes = ArrayList<Byte>(16)
    private var container: Int = 0
    private var bitsInContainer: Int = 0

    fun write(value: Int, n: Int) {
        if (n == 0) return
        container = container or ((value and ((1 shl n) - 1)) shl bitsInContainer)
        bitsInContainer += n
        while (bitsInContainer >= 8) {
            bytes.add((container and 0xFF).toByte())
            container = container ushr 8
            bitsInContainer -= 8
        }
    }

    fun finish(): ByteArray {
        if (bitsInContainer > 0) {
            bytes.add((container and 0xFF).toByte())
            container = 0
            bitsInContainer = 0
        }
        return ByteArray(bytes.size) { bytes[it] }
    }
}

/** Floor(log2(v)) for v >= 1; 0 for smaller values. */
private fun highBit(v: Int): Int {
    var n = 0
    var x = v
    while (x > 1) {
        x = x shr 1
        n++
    }
    return n
}
