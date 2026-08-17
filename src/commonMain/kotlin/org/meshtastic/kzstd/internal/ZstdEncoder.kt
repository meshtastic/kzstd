// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd.internal

import org.meshtastic.kzstd.ZstdException

/**
 * Pure-Kotlin, dictionary-aware zstd frame ENCODER (RFC 8878) — the inverse of
 * [PureZstdDecoder].
 *
 * [PureZstdEncoder.encode] takes raw bytes + a trained dictionary and returns a
 * COMPLETE standard zstd frame (magic included). The frame is decodable by real
 * libzstd (zstd-jni and every other binding) AND by [PureZstdDecoder]. It is the
 * compress half that lets `wasmWasi` — which has no JS host, no cinterop, and no
 * native libzstd — both compress and decompress in pure Kotlin.
 *
 * ## Strategy (the simplest VALID path that still benefits from the dict)
 *
 *  - **Frame header:** single-segment, dictID OFF, contentSize OFF, checksum
 *    OFF — byte-for-byte the descriptor the SDK's decoder (and libzstd) accept,
 *    with a window large enough to cover `dict.content + input`.
 *  - **Blocks:** the input is cut into chunks of at most `Block_Maximum_Size`
 *    (128 KiB), one block each, with Last_Block set on the final one. Each
 *    block independently takes whichever type is smallest: a Compressed_Block,
 *    an RLE_Block when every byte of the chunk is identical, or a Raw_Block
 *    when compression does not pay (a valid fallback; the SDK's own `0xFF`
 *    skip-compress also covers incompressible payloads above this layer).
 *  - **Matching:** a 4-byte hash-chain matcher over `[dict.content ++ chunk]`, so
 *    matches can back-reference the dictionary content. The dict index is built
 *    once per `ZstdDictionary` instance, so the dict content is hashed once and
 *    reused across calls. Lazy (1-step lookahead) matching for a better ratio.
 *  - **Literals:** RLE (litType 1) when every literal is the same byte, the
 *    Huffman table the decoder already holds (Treeless, litType 3, single-stream
 *    only) when it covers every literal byte in this block, or RAW (litType 0)
 *    — whichever is smallest.
 *  - **Sequences:** FSE-coded, independently per LL/OF/ML stream, each stream
 *    picking the cheapest of: the "Repeat" table (mode 3) the decoder already
 *    holds, when it covers this block's codes; RLE (mode 1) when
 *    every sequence uses the same code; a fresh FSE table built from this
 *    block's own code counts (mode 2); or the PREDEFINED table (mode 0) —
 *    always total over its symbol range, and exactly what [PureZstdDecoder] /
 *    libzstd build from the spec's default distributions. Cost is compared in
 *    bits and computed exactly ([FseEncTable.streamBitCost]), so the chosen
 *    mode is genuinely the smallest.
 *  - **Offsets:** a repeat-offset code (1..3) when the match distance equals
 *    one of the three most-recently-used offsets (rotated exactly as
 *    [PureZstdDecoder]'s `applyOffset` does), else an explicit literal offset
 *    (`offset_code = distance + 3`).
 *  - **Per-frame state:** the three repeat offsets and the tables the "Repeat"
 *    modes above name are FRAME state, not block state — seeded from the
 *    dictionary and then carried block to block, exactly as [PureZstdDecoder]
 *    carries them (see `FrameEntropy`).
 *
 * Pure common Kotlin: no `java.*`, no `expect/actual`.
 */
internal object PureZstdEncoder {

    private val FRAME_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private const val MIN_MATCH = 3
    private const val HASH_LOG = 17 // 128 K buckets — enough to index a 512 KB dict
    private const val HASH_SIZE = 1 shl HASH_LOG

    // zstd's Block_Maximum_Size (RFC 8878 §3.1.1.2): a block's regenerated content
    // cannot exceed min(windowSize, 128 KiB), so this is the chunk size the input
    // is cut into. The window this encoder declares always covers the whole input,
    // so 128 KiB is the binding half of that minimum.
    private const val MAX_BLOCK_SIZE = 1 shl 17 // 128 KiB

    /**
     * Encode [data] into a complete zstd frame using [dict] (parsed entropy
     * tables + content) and its [index] as match history. [level] governs
     * ONLY match-finding effort ([searchDepthFor]: how many candidate
     * distances the matcher examines per position) -- it does not gate
     * entropy-table choice (dict-entropy reuse from the Repeat-mode /
     * Treeless-literals work is unconditional whenever the dict's tables are
     * covered, independent of `level`) or implement zstd's other 21 levels'
     * distinct strategies/parameters (windowLog, targetLength, etc.); this
     * encoder uses a single fixed greedy/lazy strategy at every level, just
     * with a shallower or deeper search.
     *
     * [checksum], when true, sets Content_Checksum_Flag and appends the
     * trailing 4-byte XXH64(seed=0) low-32-bits checksum of [data] (RFC 8878
     * §3.1.1). Defaults to false so every existing call site's wire output is
     * byte-for-byte unchanged (see [ByteIdenticalRegressionTest]).
     */
    fun encode(
        data: ByteArray,
        dict: ParsedDictionary,
        index: MatchIndex,
        level: Int = 19,
        checksum: Boolean = false,
    ): ByteArray {
        val depth = searchDepthFor(level)
        val out = ArrayList<Byte>(data.size + 20)
        FRAME_MAGIC.forEach { out.add(it) }
        out.add(frameHeaderDescriptor(checksum))
        // The window must span the whole frame's back-reference history, not just
        // one block's: a later block's dictionary match reaches back past every
        // block before it.
        out.add(windowDescriptor(dict.content.size + data.size))

        // Per-FRAME entropy state, seeded from the dictionary and carried from
        // block to block. Held in a local: this singleton keeps no mutable state.
        val state = FrameEntropy(dict)

        // Cut the input into Block_Maximum_Size chunks, one block each. `do/while`
        // rather than `while`, so an empty input still emits the one (empty,
        // Last_Block) block a frame must have.
        var start = 0
        do {
            val end = minOf(start + MAX_BLOCK_SIZE, data.size)
            encodeBlock(out, data, start, end, lastBlock = end == data.size, dict, index, depth, state)
            start = end
        } while (start < data.size)

        // RFC 8878 §3.1.1: the checksum is a trailing 4-byte field covering the
        // FULL frame content, so it goes after every block -- not per block, and
        // not until the whole multi-block loop above has finished.
        if (checksum) {
            val h = Xxh64.hash(data) and 0xFFFFFFFFL
            out.add((h and 0xFF).toByte())
            out.add(((h ushr 8) and 0xFF).toByte())
            out.add(((h ushr 16) and 0xFF).toByte())
            out.add(((h ushr 24) and 0xFF).toByte())
        }

        return ByteArray(out.size) { out[it] }
    }

    /**
     * Encode `data[start until end]` as one block appended to [out], choosing the
     * smallest of the three block types.
     *
     * All three carry the same 3-byte header, so the smallest block is simply the
     * one with the smallest payload: 1 byte for RLE (only when every byte of the
     * chunk is identical), the chunk length for Raw, or the compressed body. Ties
     * go to the simpler form.
     *
     * [state] is the frame's running entropy state. Encoding this block moves it
     * -- sequences rotate the repeat offsets, and any table this block describes
     * becomes what "Repeat" names next -- so the block is encoded against a COPY
     * that is adopted only if the Compressed form actually wins. A Raw or RLE
     * block describes nothing and executes no sequence, so it must leave the
     * decoder's state exactly where it was.
     */
    @Suppress("LongParameterList")
    private fun encodeBlock(
        out: ArrayList<Byte>,
        data: ByteArray,
        start: Int,
        end: Int,
        lastBlock: Boolean,
        dict: ParsedDictionary,
        index: MatchIndex,
        depth: SearchDepth,
        state: FrameEntropy,
    ) {
        val chunk = data.copyOfRange(start, end)
        val program = buildSequences(chunk, dict, index, depth, priorBytes = start)
        val blockState = state.copy()
        val compressedBlock = encodeCompressedBlock(program, chunk, blockState)

        val constantByte = constantByteOrNull(chunk)
        when {
            constantByte != null &&
                chunk.size > 1 &&
                (compressedBlock == null || compressedBlock.size > 1) -> {
                // RLE_Block: the single repeated byte IS the block body.
                writeBlockHeader(out, lastBlock, blockType = 1, blockSize = chunk.size)
                out.add(constantByte)
            }

            compressedBlock != null && compressedBlock.size < chunk.size -> {
                // Block_Header (3 bytes LE): last, type=2 (Compressed), size=blockSize
                writeBlockHeader(out, lastBlock, blockType = 2, blockSize = compressedBlock.size)
                compressedBlock.forEach { out.add(it) }
                state.adopt(blockState)
            }

            else -> {
                // Raw_Block fallback: the literal bytes are the block.
                writeBlockHeader(out, lastBlock, blockType = 0, blockSize = chunk.size)
                chunk.forEach { out.add(it) }
            }
        }
    }

    /**
     * The state a zstd decoder carries from block to block within one frame: the
     * three repeat offsets, and the tables that "Repeat" modes name -- the
     * Huffman table Treeless literals reuse ([huffman]) and the previous block's
     * FSE table per sequence stream.
     *
     * It mirrors [PureZstdDecoder]'s `DecodeState` field for field, and is seeded
     * the same way, from the dictionary: that is exactly what those modes mean
     * for a frame's first block. The two must agree at every block boundary or a
     * Repeat mode names one table on the encode side and another on the decode
     * side, which no round-trip through this codec alone would reveal.
     *
     * Instances live in [encode]'s locals, never on the singleton.
     */
    private class FrameEntropy(
        val repeatOffsets: IntArray,
        var huffman: HuffmanTable?,
        var litLenFse: FseTable?,
        var offsetFse: FseTable?,
        var matchLenFse: FseTable?,
    ) {
        constructor(dict: ParsedDictionary) : this(
            repeatOffsets = dict.repeatOffsets.copyOf(),
            huffman = dict.literalsHuffman,
            litLenFse = dict.literalLengthFse,
            offsetFse = dict.offsetFse,
            matchLenFse = dict.matchLengthFse,
        )

        /** A detached copy to encode one candidate block against. */
        fun copy(): FrameEntropy = FrameEntropy(repeatOffsets.copyOf(), huffman, litLenFse, offsetFse, matchLenFse)

        /** Take on [other]'s state, once the block encoded against it is emitted. */
        fun adopt(other: FrameEntropy) {
            other.repeatOffsets.copyInto(repeatOffsets)
            huffman = other.huffman
            litLenFse = other.litLenFse
            offsetFse = other.offsetFse
            matchLenFse = other.matchLenFse
        }
    }

    /** The byte every element of [bytes] equals, or null (including when empty). */
    private fun constantByteOrNull(bytes: ByteArray): Byte? {
        if (bytes.isEmpty()) return null
        val first = bytes[0]
        for (b in bytes) if (b != first) return null
        return first
    }

    // ── Frame header ───────────────────────────────────────────────────────────

    /**
     * Frame_Header_Descriptor: Frame_Content_Size flag 0, Single_Segment 0,
     * Content_Checksum [checksum], Dictionary_ID 0. (Single_Segment 0 means a
     * Window_Descriptor byte follows, which is how the SDK's frames are shaped
     * and what its decoder skips.)
     */
    private fun frameHeaderDescriptor(checksum: Boolean): Byte = if (checksum) (1 shl 2).toByte() else 0

    /**
     * Window_Descriptor byte. The window must cover the full back-reference
     * history (dict content + input). We pick the smallest exponent whose window
     * size >= history, encoded as zstd's `(exponent-10)<<3 | mantissa`. Using
     * mantissa 0 keeps it simple: windowSize = 1 << exponent.
     */
    private fun windowDescriptor(historySize: Int): Byte {
        var exponent = 10 // minimum window log
        while ((1L shl exponent) < historySize.toLong() && exponent < 31) exponent++
        val windowLog = exponent - 10
        return (windowLog shl 3).toByte()
    }

    private fun writeBlockHeader(out: ArrayList<Byte>, lastBlock: Boolean, blockType: Int, blockSize: Int) {
        val header = (if (lastBlock) 1 else 0) or (blockType shl 1) or (blockSize shl 3)
        out.add((header and 0xFF).toByte())
        out.add(((header ushr 8) and 0xFF).toByte())
        out.add(((header ushr 16) and 0xFF).toByte())
    }

    // ── Sequence building (the LZ matcher) ───────────────────────────────────────

    /** One zstd sequence: literalLength literals, then a matchLength match at `offset`. */
    private class Seq(val litLen: Int, val matchLen: Int, val offset: Int)

    private class Program(val sequences: List<Seq>, val literals: ByteArray)

    /**
     * Per-`level` match-finding effort: how many candidate distances the
     * matcher examines per position, for the input chain ([maxChain]) and the
     * dictionary's cached index ([maxCandidates]) respectively. Purely a
     * search-depth knob -- never gates entropy-table choice or the encoder's
     * strategy, and never changes what a valid sequence stream can contain,
     * only which candidate match gets chosen.
     */
    internal class SearchDepth(val maxChain: Int, val maxCandidates: Int)

    /**
     * Map `level` (nominally 1..22, zstd's public range) to a [SearchDepth].
     * `level` == 19 (`Zstd.DEFAULT_LEVEL`) MUST map to exactly (64, 32) --
     * today's fixed values -- so compressing at the default level produces
     * byte-identical output to before this mapping existed. Out-of-range
     * values are clamped rather than rejected, matching real zstd's tolerance
     * for level requests outside its advertised range. The top tier is capped
     * (256, 128) rather than left to grow further, per the plan's CPU-blowup
     * concern for highly repetitive payloads at high effort. `internal` (not
     * `private`) so tests can verify the mapping directly.
     */
    internal fun searchDepthFor(level: Int): SearchDepth {
        val clamped = level.coerceIn(1, 22)
        return if (clamped >= 19) {
            val t = clamped - 19 // 0..3
            SearchDepth(maxChain = 64 + t * 64, maxCandidates = 32 + t * 32)
        } else {
            val t = clamped - 1 // 0..17
            SearchDepth(
                maxChain = 8 + (t * (64 - 8)) / 17,
                maxCandidates = 4 + (t * (32 - 4)) / 17,
            )
        }
    }

    /**
     * Greedy/lazy LZ over one block's `data` chunk, referencing the dictionary
     * content as history. Positions are expressed against the virtual
     * `[dictContent ++ data]` array: a match at distance `d` from input position
     * `i` copies bytes that may lie in the dict content (when `d > i`) or earlier
     * in `data`.
     *
     * [priorBytes] is how many bytes of the frame precede this chunk. Matches
     * within the chunk are unaffected by it -- this is deliberately the simple
     * form, where a block never matches into an earlier block's output -- but the
     * dictionary does not move with the chunk: in the real frame it sits
     * [priorBytes] further back, so a dictionary match's distance grows by that
     * much, and it may no longer run past the dictionary's end (what follows
     * there is the frame's first block, not this chunk).
     */
    private fun buildSequences(
        data: ByteArray,
        dict: ParsedDictionary,
        index: MatchIndex,
        depth: SearchDepth,
        priorBytes: Int,
    ): Program {
        val dictContent = dict.content
        val dictLen = dictContent.size
        val n = data.size

        // Combined-history accessor: position p in [0, dictLen) is dict content,
        // p in [dictLen, dictLen+n) is data[p-dictLen].
        fun histByte(p: Int): Int =
            if (p < dictLen) dictContent[p].toInt() and 0xFF else data[p - dictLen].toInt() and 0xFF

        // Per-input hash chain (continues the dict's chain). head/prev index the
        // combined history. We only INSERT input positions here; dict positions
        // live in the cached index.
        val head = IntArray(HASH_SIZE) { -1 }
        val prev = IntArray(n) { -1 }

        fun hashAt(p: Int): Int {
            // 4-byte hash; guarded so the last <4 bytes are never hashed.
            val b0 = histByte(p)
            val b1 = histByte(p + 1)
            val b2 = histByte(p + 2)
            val b3 = histByte(p + 3)
            val v = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            return ((v * -1640531527) ushr (32 - HASH_LOG)) and (HASH_SIZE - 1)
        }

        fun insert(inputPos: Int) {
            // inputPos is a `data` index; its combined-history position is
            // dictLen+inputPos. The 4-byte hash needs 4 bytes available.
            if (inputPos + 4 > n) return
            val h = hashAt(dictLen + inputPos)
            prev[inputPos] = head[h]
            head[h] = inputPos
            // Also chain to the dict's most-recent occurrence for this hash so
            // matches can reach into the dictionary. We thread that via the
            // MatchIndex lookup in findMatch instead of merging arrays.
        }

        // Length of the common run between combined-history positions a and b
        // (a < b), capped at `limit` data-bytes available from b.
        fun matchLength(a: Int, b: Int, limit: Int): Int {
            var l = 0
            while (l < limit && histByte(a + l) == histByte(b + l)) l++
            return l
        }

        // Find the best match for input position `i`. Searches the input chain
        // and the dict index; returns (length, distance) or null.
        fun findMatch(i: Int): Long? {
            // The 4-byte hash + prefix check needs 4 bytes available at `i`.
            if (i + 4 > n) return null
            val cur = dictLen + i
            val available = n - i
            var bestLen = MIN_MATCH - 1
            var bestDist = 0

            // 1) Input chain (recent `data` positions with the same 4-byte hash).
            var p = head[hashAt(cur)]
            var chain = 0
            while (p >= 0 && chain < depth.maxChain) {
                val candHist = dictLen + p
                val l = matchLength(candHist, cur, available)
                if (l > bestLen) {
                    bestLen = l
                    bestDist = cur - candHist
                }
                p = prev[p]
                chain++
            }

            // 2) Dictionary chain (positions inside the dict content sharing the
            // 4-byte prefix at `cur`). Distances here are large (cur - dictPos),
            // and grow by `priorBytes` for every block after the first, which is
            // also why such a match must stop at the dictionary's end.
            val key = first4(::histByte, cur)
            index.forEachCandidate(key, depth.maxCandidates) { dictPos ->
                // What follows the dictionary's last byte is this chunk only when
                // the chunk is the frame's first block; after that it is the
                // frame's earlier output, so the match has to stop at the end of
                // the dictionary content.
                val limit = if (priorBytes == 0) available else minOf(available, dictLen - dictPos)
                val l = matchLength(dictPos, cur, limit)
                if (l > bestLen) {
                    bestLen = l
                    bestDist = cur - dictPos + priorBytes
                }
            }

            if (bestLen >= MIN_MATCH && bestDist > 0) {
                return (bestLen.toLong() shl 32) or (bestDist.toLong() and 0xFFFFFFFFL)
            }
            return null
        }

        val sequences = ArrayList<Seq>()
        val literals = ArrayList<Byte>(n)
        var i = 0
        var litStart = 0
        while (i < n) {
            val m = findMatch(i)
            if (m == null) {
                insert(i)
                i++
                continue
            }
            var len = (m ushr 32).toInt()
            var dist = (m and 0xFFFFFFFFL).toInt()

            // Lazy: peek i+1 for a strictly longer match; if found, emit a single
            // literal and take the better match next round.
            if (i + 1 < n) {
                insert(i)
                val m2 = findMatch(i + 1)
                if (m2 != null) {
                    val len2 = (m2 ushr 32).toInt()
                    if (len2 > len) {
                        i++
                        continue
                    }
                }
                // Emit the match at i. Flush pending literals as this sequence's
                // literal run.
                val litLen = i - litStart
                for (k in litStart until i) literals.add(data[k])
                sequences.add(Seq(litLen, len, dist))
                // Insert all covered positions into the chain for future matches.
                var q = i + 1
                val matchEnd = i + len
                while (q < matchEnd && q + MIN_MATCH <= n) {
                    insert(q)
                    q++
                }
                i = matchEnd
                litStart = i
            } else {
                val litLen = i - litStart
                for (k in litStart until i) literals.add(data[k])
                sequences.add(Seq(litLen, len, dist))
                i += len
                litStart = i
            }
        }
        // Trailing literals after the last match (or the whole input if no match).
        for (k in litStart until n) literals.add(data[k])

        return Program(sequences, ByteArray(literals.size) { literals[it] })
    }

    private inline fun first4(histByte: (Int) -> Int, p: Int): Int {
        val b0 = histByte(p)
        val b1 = histByte(p + 1)
        val b2 = histByte(p + 2)
        val b3 = histByte(p + 3)
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    // ── Compressed block encoding ────────────────────────────────────────────────

    /**
     * Encode a single Compressed_Block body: a RAW literals section, then the
     * FSE-coded sequences section. Returns null only if it could not be built
     * (it always can for our inputs).
     */
    private fun encodeCompressedBlock(program: Program, data: ByteArray, state: FrameEntropy): ByteArray? {
        val out = ArrayList<Byte>(data.size + 16)

        writeLiteralsSection(out, program.literals, state)

        // Sequences_Section.
        writeSequences(out, program.sequences, state)

        return ByteArray(out.size) { out[it] }
    }

    // Single-stream Huffman literals (RFC 8878 3.1.1.3.1.1, size_format 0)
    // cap both Regenerated_Size and Compressed_Size at 10 bits each. The
    // 4-stream jump-table layout, needed only past ~1 KB of literals, is out of
    // scope: this codec's real payloads (CoT/TAK protobuf bytes) are
    // comfortably under this cap, and runs over it fall back to Raw.
    private const val MAX_SINGLE_STREAM_LITERALS = 1023

    /**
     * Literals_Section_Header + body, in whichever encoding is smallest:
     *
     *  - **RLE (litType 1)** -- one stored byte regenerates the whole run.
     *  - **Treeless (litType 3)** -- reuses the Huffman table the decoder is
     *    already holding, costing no tree description at all, when it covers
     *    every literal byte in this block. That table is the dictionary's for
     *    the frame's first block, and afterwards whichever table the last
     *    Huffman-coded block described.
     *  - **Huffman_Compressed (litType 2)** -- a table built from THIS block's
     *    own histogram, plus the tree description a decoder needs to rebuild
     *    it. Pays for the description, so it wins only once the literals are
     *    both numerous and skewed enough. Choosing it replaces the table
     *    Treeless will reuse in later blocks.
     *  - **Raw (litType 0)** -- the fallback, and the winner for short or
     *    high-entropy literal runs.
     *
     * The comparison is mandatory rather than a hint: an entropy-coded form can
     * lose outright (Treeless for a payload unlike the dict's training corpus,
     * Huffman for near-uniform literals), so each candidate is built in full
     * and measured. Ties keep the earlier, simpler candidate in the list order
     * above -- which is also what keeps the dictionary's Treeless path from
     * being displaced by an equally-sized fresh table.
     *
     * Updates [state] when the chosen form describes a table, and only then:
     * Raw and RLE literals describe none, and the decoder likewise keeps
     * whatever it was holding.
     */
    private fun writeLiteralsSection(out: ArrayList<Byte>, literals: ByteArray, state: FrameEntropy) {
        val rawCost = rawLiteralsHeaderLen(literals.size) + literals.size
        val best = listOfNotNull(
            buildRleLiterals(literals),
            buildTreelessLiterals(literals, state.huffman),
            buildHuffmanLiterals(literals),
        ).minByOrNull { it.section.size }

        if (best != null && best.section.size < rawCost) {
            best.section.forEach { out.add(it) }
            if (best.described != null) state.huffman = best.described
            return
        }
        // Literals_Section_Header (Raw, litType 0). Regenerated_Size = literals
        // length, encoded with the 1/2/3-byte size_format variants.
        writeRawLiteralsHeader(out, literals.size)
        literals.forEach { out.add(it) }
    }

    /**
     * One candidate Literals_Section: the bytes it would occupy, plus the
     * Huffman table it DESCRIBES on the wire (null unless it carries a tree
     * description), which becomes what a later block's Treeless literals reuse.
     */
    private class LiteralsCandidate(val section: ByteArray, val described: HuffmanTable?)

    /** RLE literals (litType 1): header + the single repeated byte, or null. */
    private fun buildRleLiterals(literals: ByteArray): LiteralsCandidate? {
        val constant = constantByteOrNull(literals) ?: return null
        val section = ArrayList<Byte>(4)
        writeRawOrRleLiteralsHeader(section, literals.size, litType = 1)
        section.add(constant)
        return LiteralsCandidate(ByteArray(section.size) { section[it] }, described = null)
    }

    /**
     * Treeless literals (litType 3): the table the decoder already holds, so the
     * section carries no tree description -- only the header and the stream.
     * Null when there is no such table, it does not cover some literal byte, or
     * the result overflows the single-stream size fields.
     */
    private fun buildTreelessLiterals(literals: ByteArray, huffman: HuffmanTable?): LiteralsCandidate? {
        if (huffman == null) return null
        if (literals.isEmpty() || literals.size > MAX_SINGLE_STREAM_LITERALS) return null
        val encTable = HuffmanEncTable.fromDecodeTable(huffman)
        for (b in literals) if (!encTable.isCovered(b.toInt() and 0xFF)) return null
        val stream = huffmanLiteralsStream(literals, encTable)
        if (stream.size > MAX_SINGLE_STREAM_LITERALS) return null
        return LiteralsCandidate(
            literalsSection(litType = 3, regenSize = literals.size, body = stream),
            described = null,
        )
    }

    /**
     * Huffman_Compressed literals (litType 2): a canonical table built from this
     * block's own byte histogram, described on the wire ahead of the stream.
     * Null when the alphabet cannot be Huffman-coded at all
     * ([buildLiteralsHuffman]) or the result overflows the single-stream size
     * fields.
     */
    private fun buildHuffmanLiterals(literals: ByteArray): LiteralsCandidate? {
        if (literals.isEmpty() || literals.size > MAX_SINGLE_STREAM_LITERALS) return null
        val histogram = IntArray(256)
        for (b in literals) histogram[b.toInt() and 0xFF]++
        val table = buildLiteralsHuffman(histogram) ?: return null
        val stream = huffmanLiteralsStream(literals, table.encoder)
        // Compressed_Size counts the tree description AND the stream.
        val body = table.description + stream
        if (body.size > MAX_SINGLE_STREAM_LITERALS) return null
        return LiteralsCandidate(
            literalsSection(litType = 2, regenSize = literals.size, body = body),
            described = table.decoder,
        )
    }

    /**
     * Huffman-code [literals] into one backward bitstream. Written in reverse so
     * the decoder, reading the stream backward from its stop bit, recovers them
     * in order.
     *
     * The bitstream is built in full rather than having its length estimated
     * from summed code lengths -- the trailing stop bit (see [ReverseBitWriter])
     * can push the real length one byte past a naive `ceil(totalBits/8)`.
     */
    private fun huffmanLiteralsStream(literals: ByteArray, encTable: HuffmanEncTable): ByteArray {
        val bw = ReverseBitWriter()
        for (i in literals.size - 1 downTo 0) encTable.encode(bw, literals[i].toInt() and 0xFF)
        return bw.finish()
    }

    /**
     * A complete Huffman-coded Literals_Section: the size_format-0 header
     * (single stream, 10-bit Regenerated_Size / Compressed_Size) followed by
     * [body] -- the inverse of [PureZstdDecoder]'s size_format-0 read. For
     * litType 2 the body is the tree description plus the stream; for litType 3
     * it is the stream alone.
     */
    private fun literalsSection(litType: Int, regenSize: Int, body: ByteArray): ByteArray {
        val b0 = litType or ((regenSize and 0xF) shl 4)
        val b1 = ((regenSize ushr 4) and 0x3F) or ((body.size and 0x3) shl 6)
        val b2 = (body.size ushr 2) and 0xFF
        val out = ByteArray(3 + body.size)
        out[0] = b0.toByte()
        out[1] = b1.toByte()
        out[2] = b2.toByte()
        body.copyInto(out, 3)
        return out
    }

    private fun rawLiteralsHeaderLen(size: Int): Int = when {
        size < 32 -> 1
        size < 4096 -> 2
        else -> 3
    }

    private fun writeRawLiteralsHeader(out: ArrayList<Byte>, size: Int) =
        writeRawOrRleLiteralsHeader(out, size, litType = 0)

    /**
     * Literals_Section_Header for the two uncoded forms, litType=0 (Raw) and
     * litType=1 (RLE) -- they share one header layout, differing only in
     * whether `Regenerated_Size` bytes or a single byte follow. size_format
     * selects the Regenerated_Size width:
     *  - size <  32      : 1-byte header, 5-bit size  (size_format bit0 = 0)
     *  - size <  4096    : 2-byte header, 12-bit size (size_format = 0b01)
     *  - else            : 3-byte header, 20-bit size (size_format = 0b11)
     */
    private fun writeRawOrRleLiteralsHeader(out: ArrayList<Byte>, size: Int, litType: Int) {
        when {
            size < 32 -> {
                out.add(((size shl 3) or (0 shl 2) or litType).toByte()) // [size:5][00][type:2]
            }

            size < 4096 -> {
                val b0 = litType or (0b01 shl 2) or ((size and 0xF) shl 4)
                val b1 = (size ushr 4) and 0xFF
                out.add(b0.toByte())
                out.add(b1.toByte())
            }

            else -> {
                val b0 = litType or (0b11 shl 2) or ((size and 0xF) shl 4)
                val b1 = (size ushr 4) and 0xFF
                val b2 = (size ushr 12) and 0xFF
                out.add(b0.toByte())
                out.add(b1.toByte())
                out.add(b2.toByte())
            }
        }
    }

    /**
     * Sequences_Section for one block, advancing [state]: the sequences written
     * here rotate its repeat offsets, and each stream's chosen table becomes what
     * "Repeat" names in the next block. A block with no sequences leaves both
     * alone, exactly as the decoder does -- it returns before the
     * Symbol_Compression_Modes byte, which such a block does not even carry.
     */
    private fun writeSequences(out: ArrayList<Byte>, sequences: List<Seq>, state: FrameEntropy) {
        val nbSeq = sequences.size
        // Number_of_Sequences.
        when {
            nbSeq == 0 -> {
                out.add(0)
                return
            }

            nbSeq < 128 -> out.add(nbSeq.toByte())

            nbSeq < 0x7F00 -> {
                out.add((((nbSeq ushr 8) + 128) and 0xFF).toByte())
                out.add((nbSeq and 0xFF).toByte())
            }

            else -> {
                out.add(0xFF.toByte())
                val v = nbSeq - 0x7F00
                out.add((v and 0xFF).toByte())
                out.add(((v ushr 8) and 0xFF).toByte())
            }
        }

        // Translate each Seq into (code, extraValue) triples. Threads the
        // repeat-offset state forward across sequences in original (chronological)
        // order -- Array(size) { init } is guaranteed to invoke init for indices
        // 0 until size in order, so this single pass matches how
        // ZstdDecoder.applyOffset rotates `rep` per sequence on decode. This
        // must happen BEFORE picking LL/OF/ML tables below: which table each
        // stream needs is a function of the codes actually produced.
        val codes = Array(nbSeq) { computeCodes(sequences[it], state.repeatOffsets) }

        // Symbol_Compression_Modes: 2 bits each for LL, OF, ML (high bits
        // first), low 2 bits reserved (0). Each stream picks its own cheapest
        // valid table (see [chooseSequenceTable]).
        val llCodes = IntArray(nbSeq) { codes[it].llCode }
        val ofCodes = IntArray(nbSeq) { codes[it].ofCode }
        val mlCodes = IntArray(nbSeq) { codes[it].mlCode }
        val ll = chooseSequenceTable(
            state.litLenFse,
            predefinedLiteralLengthEnc,
            predefinedLiteralLengthDec,
            LITERAL_LENGTH_MAX_SYMBOL,
            LITERAL_LENGTH_MAX_LOG,
            llCodes,
        )
        val of = chooseSequenceTable(
            state.offsetFse,
            predefinedOffsetEnc,
            predefinedOffsetDec,
            OFFSET_MAX_SYMBOL,
            OFFSET_MAX_LOG,
            ofCodes,
        )
        val ml = chooseSequenceTable(
            state.matchLenFse,
            predefinedMatchLengthEnc,
            predefinedMatchLengthDec,
            MATCH_LENGTH_MAX_SYMBOL,
            MATCH_LENGTH_MAX_LOG,
            mlCodes,
        )
        // Whatever each stream settled on is what the NEXT block's Repeat mode
        // names -- for every mode, including Predefined and RLE (the decoder
        // stores its resolved table the same way regardless of how it got it).
        state.litLenFse = ll.next
        state.offsetFse = of.next
        state.matchLenFse = ml.next
        val llTable = ll.table
        val ofTable = of.table
        val mlTable = ml.table
        out.add(((ll.mode shl 6) or (of.mode shl 4) or (ml.mode shl 2)).toByte())

        // Any table DESCRIPTIONS (an RLE symbol byte, or a full FSE table
        // header) follow the mode byte in stream order LL, OF, ML -- the order
        // [PureZstdDecoder.decodeSequences] resolves them in, which is NOT the
        // LL/ML/OF order the bitstream's extra bits use below.
        ll.description.forEach { out.add(it) }
        of.description.forEach { out.add(it) }
        ml.description.forEach { out.add(it) }

        val bw = ReverseBitWriter()

        // Encode in REVERSE sequence order so the backward bitstream reads
        // forward (see FseEncTable). The decoder read order is:
        //   init LL, OF, ML;
        //   per seq: symbols (from state), extra bits OF/ML/LL, then update LL/ML/OF.
        // The reverse-order writes below reproduce exactly that on decode.

        // Initial states from the LAST sequence's symbols.
        val last = codes[nbSeq - 1]
        var llSt = llTable.initialState(last.llCode)
        var ofSt = ofTable.initialState(last.ofCode)
        var mlSt = mlTable.initialState(last.mlCode)

        // Last sequence's extra bits (encoder order LL, ML, OF).
        writeExtra(bw, last)

        for (i in nbSeq - 2 downTo 0) {
            val c = codes[i]
            // Update transitions for seq i (encoder order OF, ML, LL).
            ofSt = ofTable.encode(bw, ofSt, c.ofCode)
            mlSt = mlTable.encode(bw, mlSt, c.mlCode)
            llSt = llTable.encode(bw, llSt, c.llCode)
            // Seq i extra bits (encoder order LL, ML, OF).
            writeExtra(bw, c)
        }

        // Flush the initial states (encoder order ML, OF, LL).
        mlTable.flushState(bw, mlSt)
        ofTable.flushState(bw, ofSt)
        llTable.flushState(bw, llSt)

        val stream = bw.finish()
        stream.forEach { out.add(it) }
    }

    /** Per-sequence FSE symbols + extra-bit payloads. */
    private class Codes(
        val llCode: Int,
        val llExtra: Int,
        val mlCode: Int,
        val mlExtra: Int,
        val ofCode: Int,
        val ofExtra: Int,
    )

    private fun writeExtra(bw: ReverseBitWriter, c: Codes) {
        // Decoder reads OF extra, then ML extra, then LL extra (chronological).
        // Reversed for the backward writer: LL, ML, OF.
        bw.writeBits(c.llExtra, LL_EXTRA_BITS[c.llCode])
        bw.writeBits(c.mlExtra, ML_EXTRA_BITS[c.mlCode])
        bw.writeBits(c.ofExtra, c.ofCode)
    }

    private fun computeCodes(seq: Seq, rep: IntArray): Codes {
        val llCode = literalLengthCode(seq.litLen)
        val llExtra = seq.litLen - LL_BASELINE[llCode]
        val mlCode = matchLengthCode(seq.matchLen)
        val mlExtra = seq.matchLen - ML_BASELINE[mlCode]
        // offsetValue is either a repeat-offset code (1..3, mutating `rep`) or an
        // explicit literal offset (distance + 3). ofCode = number of extra bits =
        // highBit(offsetValue); ofExtra = offsetValue - (1<<ofCode).
        val offsetValue = resolveOffsetCode(seq.offset, seq.litLen, rep)
        val ofCode = highBit(offsetValue)
        val ofExtra = offsetValue - (1 shl ofCode)
        return Codes(llCode, llExtra, mlCode, mlExtra, ofCode, ofExtra)
    }

    /**
     * Resolve [distance] (this sequence's match offset) against the 3-slot
     * repeat-offset cache [rep], returning the `offsetValue` to emit and
     * mutating [rep] to match exactly what [ZstdDecoder]'s `applyOffset` would
     * compute for that offsetValue -- so the decoder's rep state stays in sync
     * with the encoder's across the whole sequence stream.
     *
     * Deliberately does NOT emit the `literalLength == 0 && offsetValue == 3`
     * special case (`actual = rep[0] - 1`): it underflows when `rep[0] == 1` and
     * is the only repeat-code form that isn't a direct slot read. When
     * `litLen == 0` and `distance == rep[0]` exactly, there is no cheap code for
     * that combination (that slot is reserved for the excluded form) and this
     * falls through to an explicit offset -- correct and safe, only
     * occasionally suboptimal by a few bits. Do not "fix" this into the
     * underflow-prone form.
     */
    internal fun resolveOffsetCode(distance: Int, litLen: Int, rep: IntArray): Int {
        val rep0 = rep[0]
        val rep1 = rep[1]
        val rep2 = rep[2]
        if (litLen != 0) {
            when (distance) {
                // repCode 0: no rotation.
                rep0 -> return 1

                // repCode 1: swap rep[0]/rep[1].
                rep1 -> {
                    rep[0] = rep1
                    rep[1] = rep0
                    return 2
                }

                // repCode 2: rotate rep2->rep0, rep0->rep1, rep1->rep2.
                rep2 -> {
                    rep[0] = rep2
                    rep[1] = rep0
                    rep[2] = rep1
                    return 3
                }
            }
        } else {
            when (distance) {
                // repCode 1 (litLen==0 form): same rotation as above.
                rep1 -> {
                    rep[0] = rep1
                    rep[1] = rep0
                    return 1
                }

                // repCode 2 (litLen==0 form): same rotation as above.
                rep2 -> {
                    rep[0] = rep2
                    rep[1] = rep0
                    rep[2] = rep1
                    return 2
                }
            }
        }
        // Explicit literal offset: shifts the repeat-offset slots.
        rep[2] = rep1
        rep[1] = rep0
        rep[0] = distance
        return distance + 3
    }

    /**
     * One stream's chosen FSE encode table, its 2-bit Symbol_Compression_Mode,
     * the table description bytes (if any) that must follow the mode byte, and
     * [next] -- the decode-side table the reader ends up holding for this stream,
     * which is what the NEXT block's Repeat mode would name.
     */
    private class SeqTableChoice(val table: FseEncTable, val mode: Int, val description: ByteArray, val next: FseTable)

    /**
     * Choose the cheapest valid table for one sequence symbol stream, costed in
     * BITS ([FseEncTable.streamBitCost] is exact, not an entropy estimate) plus
     * 8 bits per description byte:
     *
     *  - **Repeat (3)** -- [repeatTable], the table the decoder is already
     *    holding for this stream (the dictionary's trained one in the frame's
     *    first block, the previous block's after that), when it assigns nonzero
     *    probability to every code this block needs. Costs no description bytes,
     *    which makes it the cheapest option outright whenever it fits -- and it
     *    fails safe, since [FseEncTable.streamBitCost] returns null for a code
     *    the table cannot represent.
     *  - **Predefined (0)** -- the spec's default distribution; also free of
     *    description bytes, and total over its declared symbol range.
     *  - **RLE (1)** -- when every sequence uses the SAME code: one description
     *    byte and not a single bit in the bitstream.
     *  - **FSE_Compressed (2)** -- a table built from THIS block's own code
     *    counts, plus the description a decoder needs to rebuild it. It fits
     *    the block's actual distribution rather than the spec's guess at one,
     *    so it wins whenever there are enough sequences to repay its
     *    description.
     *
     * Candidates are considered in that order and a later one must be strictly
     * cheaper to win, so ties keep the mode that costs no description bytes and
     * leaves the dictionary paths in place.
     */
    @Suppress("LongParameterList")
    private fun chooseSequenceTable(
        repeatTable: FseTable?,
        predefined: FseEncTable,
        predefinedDecode: FseTable,
        maxSymbol: Int,
        maxLog: Int,
        codes: IntArray,
    ): SeqTableChoice {
        var best: SeqTableChoice? = null
        var bestBits = Long.MAX_VALUE

        fun consider(candidate: SeqTableChoice) {
            val bits = candidate.table.streamBitCost(codes) ?: return
            val total = bits + candidate.description.size * 8L
            if (total < bestBits) {
                bestBits = total
                best = candidate
            }
        }

        if (repeatTable != null) {
            consider(
                SeqTableChoice(FseEncTable.fromDecodeTable(repeatTable, maxSymbol), 3, ByteArray(0), repeatTable),
            )
        }
        consider(SeqTableChoice(predefined, 0, ByteArray(0), predefinedDecode))
        val constantCode = codes[0].takeIf { first -> codes.all { it == first } }
        if (constantCode != null) {
            val rle = FseTable.rle(constantCode)
            consider(
                SeqTableChoice(
                    FseEncTable.fromDecodeTable(rle, maxSymbol),
                    1,
                    byteArrayOf(constantCode.toByte()),
                    rle,
                ),
            )
        }

        val fresh = buildFreshFseTable(codes, maxSymbol, maxLog)
        if (fresh != null) consider(SeqTableChoice(fresh.encoder, 2, fresh.description, fresh.decoder))

        // Predefined covers every code a block of any reachable size produces, so
        // this is unreachable in practice -- but silently falling back to a table
        // that cannot represent a code would emit a corrupt stream, so say so
        // instead. (The one theoretical route is an offset code above the
        // predefined table's 28: a dictionary match from a block that starts more
        // than 512 MB into the frame.)
        return best ?: throw ZstdException("no FSE table can encode this block's sequence codes")
    }

    /** Map a literal length to its FSE code (largest baseline <= length). */
    private fun literalLengthCode(len: Int): Int {
        var c = LL_BASELINE.size - 1
        while (c > 0 && LL_BASELINE[c] > len) c--
        return c
    }

    private fun matchLengthCode(len: Int): Int {
        var c = ML_BASELINE.size - 1
        while (c > 0 && ML_BASELINE[c] > len) c--
        return c
    }

    private fun highBit(v: Int): Int {
        var n = 0
        var x = v
        while (x > 1) {
            x = x shr 1
            n++
        }
        return n
    }

    // Predefined FSE encoding tables, built once. These three `by lazy` (default
    // SYNCHRONIZED) properties are the ONLY object-level state on this singleton;
    // `by lazy` safely publishes the immutable tables to all threads, which is part
    // of what lets the codec carry no lock. Any future object-level encoder state
    // MUST stay immutable + safely published (keep `by lazy`, or an eager `val`).
    private val predefinedLiteralLengthEnc: FseEncTable by lazy {
        FseEncTable.build(LL_DEFAULT_DISTRIBUTION, LL_DEFAULT_DISTRIBUTION.size - 1, LL_DEFAULT_LOG)
    }
    private val predefinedMatchLengthEnc: FseEncTable by lazy {
        FseEncTable.build(ML_DEFAULT_DISTRIBUTION, ML_DEFAULT_DISTRIBUTION.size - 1, ML_DEFAULT_LOG)
    }
    private val predefinedOffsetEnc: FseEncTable by lazy {
        FseEncTable.build(OF_DEFAULT_DISTRIBUTION, OF_DEFAULT_DISTRIBUTION.size - 1, OF_DEFAULT_LOG)
    }

    // The decode-side halves of the same three tables. A block that picks
    // Predefined leaves the decoder holding THIS table for the stream, so it is
    // also what a following block's Repeat mode would name -- the encoder has to
    // be able to hand it back. Same immutable + `by lazy` rule as above.
    private val predefinedLiteralLengthDec: FseTable by lazy { predefinedLiteralLengthTable() }
    private val predefinedMatchLengthDec: FseTable by lazy { predefinedMatchLengthTable() }
    private val predefinedOffsetDec: FseTable by lazy { predefinedOffsetTable() }
}
