package com.kinetica.keyboard.engine

/**
 * Two-word context boosts keyed on trie word ids, the [BigramTable] idea one word longer:
 * sorted packed longs (first << 42 | second << 21 | next) with one quantized byte each,
 * normalized per (first, second) context against that context's likeliest next word.
 *
 * The bigram table sees only the previous word, so after "want to" it cannot prefer "go" over
 * "to"'s other followers. A trigram table can, and it only ever reorders: [multiplier] is 1.0
 * for an unseen triple, so a missing entry backs off to the bigram the score already carries.
 *
 * Ids must fit in 21 bits (2,097,151 nodes); entries with larger ids are dropped at build.
 */
class TrigramTable private constructor(
    private val keys: LongArray,
    private val boosts: ByteArray,
) {
    val size: Int get() = keys.size

    /** The triple's boost byte (0..255), 0 when absent or when any id is missing. */
    fun boostByte(firstId: Int, secondId: Int, nextId: Int): Int {
        if (keys.isEmpty() || !fits(firstId) || !fits(secondId) || !fits(nextId)) return 0
        val i = java.util.Arrays.binarySearch(keys, pack(firstId, secondId, nextId))
        return if (i < 0) 0 else boosts[i].toInt() and 0xFF
    }

    /** 1 + [KineticaConstants.TRIGRAM_BOOST_MAX] scaled by the boost byte; 1.0 when unseen. */
    fun multiplier(firstId: Int, secondId: Int, nextId: Int, boostMax: Float = KineticaConstants.TRIGRAM_BOOST_MAX): Float {
        val b = boostByte(firstId, secondId, nextId)
        return if (b == 0) 1f else 1f + boostMax * b / 255f
    }

    companion object {
        val EMPTY = TrigramTable(LongArray(0), ByteArray(0))

        private const val BITS = 21
        private const val MASK = (1L shl BITS) - 1

        private fun fits(id: Int): Boolean = id >= 0 && id.toLong() <= MASK

        private fun pack(a: Int, b: Int, c: Int): Long =
            (a.toLong() shl (2 * BITS)) or (b.toLong() shl BITS) or c.toLong()

        /** Entries are (firstId, secondId, nextId, rawCount); equal triples sum their counts. */
        fun build(entries: List<IntArray>, counts: LongArray): TrigramTable {
            val idx = entries.indices.filter { i -> entries[i].all { fits(it) } }
                .sortedBy { pack(entries[it][0], entries[it][1], entries[it][2]) }
            if (idx.isEmpty()) return EMPTY
            val keys = LongArray(idx.size)
            val sums = LongArray(idx.size)
            var n = 0
            for (i in idx) {
                val key = pack(entries[i][0], entries[i][1], entries[i][2])
                // Accent folding can make distinct spellings share a triple: combine them.
                if (n > 0 && keys[n - 1] == key) {
                    sums[n - 1] += counts[i]
                } else {
                    keys[n] = key
                    sums[n] = counts[i]
                    n++
                }
            }
            // A context's triples are contiguous after sorting: normalize each group against
            // its own maximum so every context uses the full byte range.
            val boosts = ByteArray(n)
            var start = 0
            while (start < n) {
                val ctx = keys[start] ushr BITS
                var end = start
                var max = 0L
                while (end < n && (keys[end] ushr BITS) == ctx) {
                    if (sums[end] > max) max = sums[end]
                    end++
                }
                for (j in start until end) {
                    val q = if (max <= 0) 0 else Math.round(255.0 * sums[j] / max).toInt().coerceIn(1, 255)
                    boosts[j] = q.toByte()
                }
                start = end
            }
            return TrigramTable(keys.copyOf(n), boosts)
        }
    }
}
