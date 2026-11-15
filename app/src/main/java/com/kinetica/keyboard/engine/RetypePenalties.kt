package com.kinetica.keyboard.engine

import kotlin.math.exp
import kotlin.math.ln

/**
 * Per-user "decoded Y, meant X" counts, and the penalty they put on Y (decoder rework step 8,
 * issue #131).
 *
 * The personal count only goes up: a word shown as the lead, then deleted and typed again as
 * another word, costs it nothing beyond that one decode, so the same pair goes wrong the same
 * way next time. This counts those pairs, and how often Y was committed at all since its first
 * one. When Y and X are both on a list again, Y's score is multiplied by `(1 - r)^WEIGHT`,
 *
 *     r = net(Y -> X) / (seen(Y) + PRIOR)
 *
 * the share of Y's commits that were meant as X (net of X read for Y). The share matters:
 * `the` was meant as `three` five times in the traces and stood thousands of times, and a
 * penalty from the count alone broke a hundred good commits. `god` for `good` four times in
 * five is a real habit. The penalty needs X on the list, so Y is never pushed down for good,
 * only behind the word it keeps being mistaken for.
 *
 * Immutable, like [WordPenalties]: every change returns a new value, so a decode thread holding
 * the old one never sees it half-written. Stored as one string preference, rows
 * `lang|decoded|meant|count` and `lang|decoded||seen` joined by commas, which keeps it inside
 * the settings backup.
 */
class RetypePenalties private constructor(
    private val byLang: Map<String, Map<String, Map<String, Int>>>,
    private val seenByLang: Map<String, Map<String, Int>>,
) {

    /** One language's counts, as a predictor reads them. Keys lowercase. */
    class Counts(val pairs: Map<String, Map<String, Int>>, val seen: Map<String, Int>) {
        val isEmpty: Boolean get() = pairs.isEmpty()

        /**
         * The multiplier on [word] given the other [listed] words: the largest share of its
         * commits meant as one of them. 1 when none applies.
         */
        fun factorFor(word: String, listed: Collection<String>): Float {
            val row = pairs[word] ?: return 1f
            var worst = 0
            for (x in listed) {
                val n = row[x] ?: continue
                val net = n - (pairs[x]?.get(word) ?: 0)
                if (net > worst) worst = net
            }
            return factor(worst, seen[word] ?: 0)
        }

        companion object {
            val EMPTY = Counts(emptyMap(), emptyMap())
        }
    }

    fun forLanguage(lang: String): Counts {
        val p = byLang[lang] ?: return Counts.EMPTY
        return Counts(p, seenByLang[lang] ?: emptyMap())
    }

    fun count(lang: String, decoded: String, meant: String): Int =
        byLang[lang]?.get(decoded.lowercase())?.get(meant.lowercase()) ?: 0

    fun seen(lang: String, decoded: String): Int = seenByLang[lang]?.get(decoded.lowercase()) ?: 0

    val size: Int get() = byLang.values.sumOf { l -> l.values.sumOf { it.size } }

    /**
     * One more time [decoded] was committed and [meant] was what the user typed instead. Call
     * [withCommit] for the commit itself as well. Counts stop at [MAX_COUNT]; past [MAX_ROWS]
     * pairs the rarest are dropped, so the preference stays small. Words that cannot be
     * stored, and a word against itself, change nothing.
     */
    fun with(lang: String, decoded: String, meant: String): RetypePenalties {
        val y = decoded.lowercase()
        val x = meant.lowercase()
        if (y == x || lang.isEmpty() || !WordPenalties.storable(y) || !WordPenalties.storable(x)) return this
        val out = HashMap<String, Map<String, Map<String, Int>>>(byLang)
        val l = HashMap(out[lang] ?: emptyMap())
        val row = HashMap(l[y] ?: emptyMap())
        row[x] = minOf(MAX_COUNT, (row[x] ?: 0) + 1)
        l[y] = row
        out[lang] = l
        // A word's commits are counted from its first pair on, that commit included.
        var seen = seenByLang
        if ((seen[lang]?.get(y) ?: 0) == 0) {
            val sl = HashMap(seen[lang] ?: emptyMap())
            sl[y] = 1
            seen = HashMap(seen).also { it[lang] = sl }
        }
        val next = RetypePenalties(out, seen)
        return if (next.size > MAX_ROWS) next.trimmed() else next
    }

    /**
     * The decoder's word [decoded] was committed. Counted only for a word that already has a
     * pair, so the store grows with the mistakes, not with the vocabulary; returns this same
     * value otherwise.
     */
    fun withCommit(lang: String, decoded: String): RetypePenalties {
        val y = decoded.lowercase()
        val n = seenByLang[lang]?.get(y) ?: return this
        if (n >= MAX_SEEN) return this
        val sl = HashMap(seenByLang[lang] ?: emptyMap())
        sl[y] = n + 1
        return RetypePenalties(byLang, HashMap(seenByLang).also { it[lang] = sl })
    }

    /** Without every pair naming [word], either way (the bar's "forget this word"). */
    fun without(lang: String, word: String): RetypePenalties {
        val w = word.lowercase()
        val l = byLang[lang] ?: return this
        val kept = HashMap<String, Map<String, Int>>()
        for ((y, row) in l) {
            if (y == w) continue
            val r = row.filterKeys { it != w }
            if (r.isNotEmpty()) kept[y] = r
        }
        val out = HashMap(byLang)
        if (kept.isEmpty()) out.remove(lang) else out[lang] = kept
        return RetypePenalties(out, seenFor(out))
    }

    /** [seenByLang] cut to the words that still have a pair in [pairs]. */
    private fun seenFor(pairs: Map<String, Map<String, Map<String, Int>>>): Map<String, Map<String, Int>> {
        val out = HashMap<String, Map<String, Int>>()
        for ((lang, l) in pairs) {
            val s = seenByLang[lang] ?: continue
            out[lang] = s.filterKeys { it in l }
        }
        return out
    }

    private fun trimmed(): RetypePenalties {
        val rows = ArrayList<Array<Any>>()
        for ((lang, l) in byLang) for ((y, row) in l) for ((x, n) in row) rows.add(arrayOf(lang, y, x, n))
        rows.sortWith(compareByDescending<Array<Any>> { it[3] as Int }.thenBy { it[1] as String }.thenBy { it[2] as String })
        val out = HashMap<String, HashMap<String, HashMap<String, Int>>>()
        for (r in rows.take(MAX_ROWS * 3 / 4)) {
            out.getOrPut(r[0] as String) { HashMap() }.getOrPut(r[1] as String) { HashMap() }[r[2] as String] = r[3] as Int
        }
        return RetypePenalties(out, seenFor(out))
    }

    fun serialize(): String {
        val rows = ArrayList<String>()
        for ((lang, l) in byLang.entries.sortedBy { it.key }) {
            for ((y, row) in l.entries.sortedBy { it.key }) {
                rows.add("$lang$FIELD$y$FIELD$FIELD${seenByLang[lang]?.get(y) ?: 1}")
                for ((x, n) in row.entries.sortedBy { it.key }) rows.add("$lang$FIELD$y$FIELD$x$FIELD$n")
            }
        }
        return rows.joinToString(ROW)
    }

    companion object {
        private const val ROW = ","
        private const val FIELD = "|"

        /** A pair counts no higher than this. */
        const val MAX_COUNT = 20

        /** Pairs kept; the rarest quarter goes when it is passed. */
        const val MAX_ROWS = 400

        /** Commits of a word counted no higher than this. */
        const val MAX_SEEN = 2000

        val EMPTY = RetypePenalties(emptyMap(), emptyMap())

        /** Whether the decoder applies the penalties (the pref, default off). */
        @Volatile @JvmField var enabled = false

        /**
         * Exponent of the penalty: Y's score times `(1 - r)^WEIGHT`. 2 fitted on replay (1, 2, 4
         * tried; 1 and 2 close, 4 no better).
         */
        @Volatile @JvmField var WEIGHT = 2f

        /** Net count from which a pair is penalised. */
        @Volatile @JvmField var MIN_COUNT = 1

        /** Commits added to a word's count before the share is taken, so one mistake is not a habit. */
        @Volatile @JvmField var PRIOR = 2f

        /** The score multiplier for a net count of [n] among [seen] commits. */
        fun factor(n: Int, seen: Int): Float {
            if (n < MIN_COUNT || n <= 0) return 1f
            val r = (n / (maxOf(seen, n) + PRIOR)).coerceIn(0f, 0.95f)
            return exp(WEIGHT * ln(1f - r))
        }

        /**
         * Whether [retyped] is a second try at the commit [was], not a different word: it
         * differs by at most half the longer word's letters (go/to, gels/tells, bit/but) and
         * does not only add letters at the end (`ca` then `can` was a stray space, not a
         * misread). The rule replay labels retypes by.
         */
        fun closeRetype(was: String, retyped: String): Boolean {
            val a = was.lowercase()
            val b = retyped.lowercase()
            if (a == b || a.isEmpty() || b.startsWith(a)) return false
            return editDistance(a, b) <= maxOf(1, maxOf(a.length, b.length) / 2)
        }

        fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }

        /** Reads [serialize]'s form; malformed rows are dropped, not fatal. */
        fun parse(s: String?): RetypePenalties {
            if (s.isNullOrBlank()) return EMPTY
            val out = HashMap<String, HashMap<String, HashMap<String, Int>>>()
            val seen = HashMap<String, HashMap<String, Int>>()
            for (r in s.split(ROW)) {
                val f = r.split(FIELD)
                if (f.size != 4 || f[0].isEmpty() || f[1].isEmpty()) continue
                val n = f[3].toIntOrNull() ?: continue
                if (n <= 0) continue
                if (f[2].isEmpty()) {
                    seen.getOrPut(f[0]) { HashMap() }[f[1].lowercase()] = minOf(n, MAX_SEEN)
                } else {
                    out.getOrPut(f[0]) { HashMap() }.getOrPut(f[1].lowercase()) { HashMap() }[f[2].lowercase()] = minOf(n, MAX_COUNT)
                }
            }
            val base = RetypePenalties(out, seen)
            return RetypePenalties(out, base.seenFor(out))
        }
    }
}
