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
    /**
     * Per pair, `-ln P(next | prev)` in steps of [LOGP_STEP] nat (capped at 255 steps), for the
     * log-linear score ([ScoreModel]); P is the pair's count over all counted followers of the
     * previous word. Only a table built from counts ([build]) has it; a pair file holds bytes.
     */
    private val logp: ByteArray? = null,
) {
    val size: Int get() = compact?.size ?: keys.size

    /**
     * `ln P(next | prev)`, or NaN when the pair is absent or no table has probabilities. The
     * wider [context] table answers when its pair file carries them (DPR2, decoder rework step
     * 3): its pairs are a real conditional over everything counted after `prev`. Otherwise this
     * table's own counts, normalised over the followers it lists.
     */
    fun logCond(prevWordId: Int, nextWordId: Int): Float {
        val wide = context?.compact
        if (wide != null && wide.hasLogp) return wide.logCond(prevWordId, nextWordId)
        compact?.let { if (it.hasLogp) return it.logCond(prevWordId, nextWordId) }
        return ownLogCond(prevWordId, nextWordId)
    }

    /**
     * `ln c(prev, next)`, the pair's blended count, from a pair file with probabilities, or NaN.
     * The denominator of a triple's `P(w | prev, next)`.
     */
    fun lnPairCount(prevWordId: Int, nextWordId: Int): Float =
        ((context ?: this).compact)?.lnPairCount(prevWordId, nextWordId) ?: Float.NaN

    private fun ownLogCond(prevWordId: Int, nextWordId: Int): Float {
        val lp = logp ?: return Float.NaN
        if (prevWordId < 0 || nextWordId < 0 || keys.isEmpty()) return Float.NaN
        val i = java.util.Arrays.binarySearch(keys, pack(prevWordId, nextWordId))
        return if (i < 0) Float.NaN else -(lp[i].toInt() and 0xFF) * LOGP_STEP
    }

    fun multiplier(prevWordId: Int, nextWordId: Int): Float {
        val byte = (context ?: this).boostByte(prevWordId, nextWordId)
        return if (byte == 0) 1f else 1f + KineticaConstants.BIGRAM_BOOST_MAX * byte / 255f
    }

    /**
     * This table with [wide] behind the decoder's boost ([multiplier]) and the short-slip rule
     * ([contextByte]). [boostByte] and [successors] still read this one: the missing-space split,
     * the grammar pass and the next-word bar. Measured on the traces (#105), the wide table's
     * bytes split `someone` and `minigames`, and its web text holds `ill be` and `its a` often
     * enough to rewrite `i'll` and `it's` the wrong way.
     */
    fun withContext(wide: BigramTable): BigramTable = BigramTable(keys, boosts, compact, wide, logp)

    /** The pair's boost byte in the table behind [multiplier]: the wide one when there is one. */
    fun contextByte(prevWordId: Int, nextWordId: Int): Int = (context ?: this).boostByte(prevWordId, nextWordId)

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
        /** Per pair, `-ln P(next | prev)` in [LOGP_STEP] steps (DPR2), or null. */
        private val logp: ByteArray? = null,
        /** Per word number, `ln C(word)`: the count of everything after it (DPR2), NaN for none. */
        private val lnTotal: FloatArray? = null,
    ) {
        val size: Int get() = next.size

        val hasLogp: Boolean get() = logp != null

        fun sizeBytes(): Int = next.size * (if (logp != null) 4 else 3) +
            (indexOfNode.size + nodeOf.size + start.size + (lnTotal?.size ?: 0)) * 4

        private fun indexOf(node: Int): Int = if (node < 0 || node >= indexOfNode.size) -1 else indexOfNode[node]

        /** The pair's slot in [next], or -1. */
        private fun find(prevNode: Int, nextNode: Int): Int {
            val p = indexOf(prevNode)
            val n = indexOf(nextNode)
            if (p < 0 || n < 0) return -1
            var lo = start[p]
            var hi = start[p + 1] - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = next[mid].toInt() and 0xFFFF
                when {
                    v < n -> lo = mid + 1
                    v > n -> hi = mid - 1
                    else -> return mid
                }
            }
            return -1
        }

        fun boostByte(prevNode: Int, nextNode: Int): Int {
            val i = find(prevNode, nextNode)
            return if (i < 0) 0 else boost[i].toInt() and 0xFF
        }

        fun logCond(prevNode: Int, nextNode: Int): Float {
            val lp = logp ?: return Float.NaN
            val i = find(prevNode, nextNode)
            return if (i < 0) Float.NaN else -(lp[i].toInt() and 0xFF) * LOGP_STEP
        }

        fun lnPairCount(prevNode: Int, nextNode: Int): Float {
            val totals = lnTotal ?: return Float.NaN
            val l = logCond(prevNode, nextNode)
            return if (l.isNaN()) Float.NaN else totals[indexOf(prevNode)] + l
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

        /** Resolution of the stored log-probabilities, nat per step. */
        const val LOGP_STEP = 1f / 16f

        /** `-ln p` quantized to [LOGP_STEP], 0..255. */
        internal fun quantizeLogP(count: Long, total: Long): Byte {
            val nll = -ln(count.toDouble() / maxOf(total, 1L).toDouble())
            return Math.round(nll / LOGP_STEP).toInt().coerceIn(0, 255).toByte()
        }

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
            val logp = ByteArray(uniqueCount)
            var start = 0
            while (start < uniqueCount) {
                val prev = keys[start] ushr 32
                var end = start
                var maxCount = 1L
                var total = 0L
                while (end < uniqueCount && (keys[end] ushr 32) == prev) {
                    maxCount = max(maxCount, counts[end])
                    total += maxOf(counts[end], 0L)
                    end++
                }
                val logMax = ln(1.0 + maxCount)
                for (i in start until end) {
                    val b = (255.0 * ln(1.0 + counts[i]) / logMax).toInt().coerceIn(1, 255)
                    boosts[i] = b.toByte()
                    logp[i] = quantizeLogP(maxOf(counts[i], 1L), total)
                }
                start = end
            }
            return BigramTable(keys.copyOf(uniqueCount), boosts, logp = logp)
        }
    }
}
