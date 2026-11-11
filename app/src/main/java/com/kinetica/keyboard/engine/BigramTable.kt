package com.kinetica.keyboard.engine

import kotlin.math.ln
import kotlin.math.max

/**
 * Bigram context boosts keyed on trie word ids: sorted packed longs
 * (prevId << 32 | nextId) with one quantized boost byte each. Boost bytes are
 * normalized per previous word (an approximation of P(next|prev)), so the
 * multiplier reorders near-ties without overriding clear gesture geometry.
 *
 * A language with a binary pair file ([PairFile]) loads that instead: the same bytes and
 * the same answers, in three bytes a pair where the packed longs take nine.
 */
class BigramTable private constructor(
    private val keys: LongArray,
    private val boosts: ByteArray,
    /** The table as [PairFile] holds it, three bytes a pair; then [keys] is empty. */
    private val compact: Compact? = null,
    /** A wider table the decoder's boost reads in place of this one ([withContext]). */
    private val context: BigramTable? = null,
) {
    val size: Int get() = compact?.size ?: keys.size

    fun multiplier(prevWordId: Int, nextWordId: Int): Float {
        val byte = (context ?: this).boostByte(prevWordId, nextWordId)
        return if (byte == 0) 1f else 1f + KineticaConstants.BIGRAM_BOOST_MAX * byte / 255f
    }

    /**
     * This table with [wide] behind the decoder's boost ([multiplier]). [boostByte] and
     * [successors] still read this one: the rules that judge a pair by its byte (short slips,
     * the missing-space split, the grammar pass) and the next-word bar were tuned on the
     * curated table, and web text holds `ill be` and `it s` often enough to mislead them.
     */
    fun withContext(wide: BigramTable): BigramTable = BigramTable(keys, boosts, compact, wide)

    /** Pairs behind [multiplier]: the wide table's when there is one. */
    val contextSize: Int get() = (context ?: this).size

    fun sizeBytes(): Int = compact?.sizeBytes() ?: (keys.size * 8 + boosts.size)

    /** The pair's boost byte (0..255, P(next | prev) against prev's likeliest next word), 0 when absent. */
    fun boostByte(prevWordId: Int, nextWordId: Int): Int {
        if (compact != null) return compact.boostByte(prevWordId, nextWordId)
        if (prevWordId < 0 || nextWordId < 0 || keys.isEmpty()) return 0
        val i = java.util.Arrays.binarySearch(keys, pack(prevWordId, nextWordId))
        return if (i < 0) 0 else boosts[i].toInt() and 0xFF
    }

    /**
     * What follows [prevWordId] in the table, strongest first, as (next word id, boost byte).
     * A previous word's pairs are contiguous in the sorted keys, so this is one search and a
     * slice.
     */
    fun successors(prevWordId: Int, limit: Int): List<Pair<Int, Int>> {
        if (compact != null) return compact.successors(prevWordId, limit)
        if (prevWordId < 0 || keys.isEmpty() || limit <= 0) return emptyList()
        var i = java.util.Arrays.binarySearch(keys, pack(prevWordId, 0))
        if (i < 0) i = -i - 1
        val out = ArrayList<Pair<Int, Int>>()
        while (i < keys.size && (keys[i] ushr 32).toInt() == prevWordId) {
            out.add((keys[i] and 0xFFFFFFFFL).toInt() to (boosts[i].toInt() and 0xFF))
            i++
        }
        out.sortWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /**
     * A large table in three bytes a pair. Words are numbered as the pair file lists them
     * (at most 65,536), [start] gives each previous word's run of followers, and a run holds
     * the followers' numbers in rising order with one boost byte each. [indexOfNode] and
     * [nodeOf] tie those numbers to the trie the table was loaded against.
     */
    internal class Compact(
        private val indexOfNode: IntArray,
        private val nodeOf: IntArray,
        private val start: IntArray,
        private val next: ShortArray,
        private val boost: ByteArray,
    ) {
        val size: Int get() = next.size

        fun sizeBytes(): Int = next.size * 3 + (indexOfNode.size + nodeOf.size + start.size) * 4

        private fun indexOf(node: Int): Int = if (node < 0 || node >= indexOfNode.size) -1 else indexOfNode[node]

        fun boostByte(prevNode: Int, nextNode: Int): Int {
            val p = indexOf(prevNode)
            val n = indexOf(nextNode)
            if (p < 0 || n < 0) return 0
            var lo = start[p]
            var hi = start[p + 1] - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = next[mid].toInt() and 0xFFFF
                when {
                    v < n -> lo = mid + 1
                    v > n -> hi = mid - 1
                    else -> return boost[mid].toInt() and 0xFF
                }
            }
            return 0
        }

        fun successors(prevNode: Int, limit: Int): List<Pair<Int, Int>> {
            val p = indexOf(prevNode)
            if (p < 0 || limit <= 0) return emptyList()
            val out = ArrayList<Pair<Int, Int>>()
            for (i in start[p] until start[p + 1]) {
                val index = next[i].toInt() and 0xFFFF
                val node = nodeOf[index]
                // A word the trie lacks, or one whose folded spelling another word owns.
                if (node < 0 || indexOfNode[node] != index) continue
                out.add(node to (boost[i].toInt() and 0xFF))
            }
            out.sortWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            return if (out.size > limit) out.subList(0, limit) else out
        }
    }

    companion object {
        val EMPTY = BigramTable(LongArray(0), ByteArray(0))

        /** The table of a pair file ([PairFile.read]). */
        internal fun compact(c: Compact): BigramTable = BigramTable(LongArray(0), ByteArray(0), c)

        private fun pack(prev: Int, next: Int): Long =
            (prev.toLong() shl 32) or (next.toLong() and 0xFFFFFFFFL)

        /** Entries are (prevWordId, nextWordId, rawCount); equal pairs sum their counts. */
        fun build(entries: List<Triple<Int, Int, Long>>): BigramTable {
            if (entries.isEmpty()) return EMPTY
            val keys = LongArray(entries.size)
            val counts = LongArray(entries.size)
            val idx = entries.indices.sortedBy { pack(entries[it].first, entries[it].second) }
            var uniqueCount = 0
            for (i in idx) {
                val e = entries[i]
                val key = pack(e.first, e.second)
                // Accent folding can make distinct spellings share a pair. Combine
                // their evidence before finding the context maximum or quantizing.
                if (uniqueCount > 0 && keys[uniqueCount - 1] == key) {
                    counts[uniqueCount - 1] += e.third
                } else {
                    keys[uniqueCount] = key
                    counts[uniqueCount] = e.third
                    uniqueCount++
                }
            }
            // Same-prev entries are contiguous after sorting; normalize each group
            // against its own maximum so every context uses the full byte range.
            val boosts = ByteArray(uniqueCount)
            var start = 0
            while (start < uniqueCount) {
                val prev = keys[start] ushr 32
                var end = start
                var maxCount = 1L
                while (end < uniqueCount && (keys[end] ushr 32) == prev) {
                    maxCount = max(maxCount, counts[end])
                    end++
                }
                val logMax = ln(1.0 + maxCount)
                for (i in start until end) {
                    val b = (255.0 * ln(1.0 + counts[i]) / logMax).toInt().coerceIn(1, 255)
                    boosts[i] = b.toByte()
                }
                start = end
            }
            return BigramTable(keys.copyOf(uniqueCount), boosts)
        }
    }
}
