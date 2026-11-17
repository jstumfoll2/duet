package com.kinetica.keyboard.engine

import kotlin.math.ln

/**
 * What kind of writing a field is for, as far as the editor says (issue #136). Coarse on
 * purpose: five classes fill with counts fast enough to be judged, an app name would not, and
 * a list of apps is more than a keyboard should keep.
 */
enum class FieldClass(val key: String) {
    CHAT("chat"), EMAIL("email"), SEARCH("search"), CODE("code"), OTHER("other");

    companion object {
        // android.text.InputType and android.view.inputmethod.EditorInfo, by value: the engine
        // builds without Android. Public API constants, which never change.
        private const val MASK_CLASS = 0x0000000f
        private const val CLASS_TEXT = 0x00000001
        private const val MASK_VARIATION = 0x00000ff0
        private const val VARIATION_URI = 0x00000010
        private const val VARIATION_EMAIL_ADDRESS = 0x00000020
        private const val VARIATION_EMAIL_SUBJECT = 0x00000030
        private const val VARIATION_SHORT_MESSAGE = 0x00000040
        private const val VARIATION_LONG_MESSAGE = 0x00000050
        private const val VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
        private const val FLAG_NO_SUGGESTIONS = 0x00080000
        private const val MASK_ACTION = 0x000000ff
        private const val ACTION_GO = 2
        private const val ACTION_SEARCH = 3
        private const val ACTION_SEND = 4

        /**
         * The class of a field with this `EditorInfo.inputType` and `imeOptions`. The field's
         * own variation first, then its action key: a subject line with a Send key is email.
         */
        fun of(inputType: Int, imeOptions: Int): FieldClass {
            if (inputType and MASK_CLASS != CLASS_TEXT) return OTHER
            val action = imeOptions and MASK_ACTION
            return when (inputType and MASK_VARIATION) {
                VARIATION_URI -> CODE
                VARIATION_EMAIL_ADDRESS, VARIATION_WEB_EMAIL_ADDRESS, VARIATION_EMAIL_SUBJECT,
                VARIATION_LONG_MESSAGE,
                -> EMAIL
                VARIATION_SHORT_MESSAGE -> CHAT
                else -> when {
                    inputType and FLAG_NO_SUGGESTIONS != 0 -> CODE
                    action == ACTION_SEARCH || action == ACTION_GO -> SEARCH
                    action == ACTION_SEND -> CHAT
                    else -> OTHER
                }
            }
        }

        fun ofKey(key: String): FieldClass? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How often each word was committed in each [FieldClass], and what that says about a word's
 * personal boost in the field at hand (issue #136).
 *
 * The personal count is one number per word, so a word typed fifty times in a search box or a
 * commit message is boosted just as hard in a chat. This keeps the split. In a field of class
 * `c`, a word's personal count is read as `count * r`,
 *
 *     r = min(1, (n(w, c) + PRIOR) / (n(w) * p(c) + PRIOR))
 *
 * with `n(w, c)` the word's commits in this class, `n(w)` in all, and `p(c)` this class's share
 * of all commits: the commits seen here over the commits expected here if the word did not care
 * where it was typed. A word used everywhere alike keeps `r = 1`; one that lives in another
 * class loses most of its boost here and all of it nowhere. It never raises a boost, so the
 * worst it can do is hand a word back to its dictionary frequency.
 *
 * Not immutable, unlike the small stores beside it: a row per word typed would be copied on
 * every commit. Rows are replaced whole, so a decode thread reads either the old row or the new.
 */
class FieldWords {

    private val rows = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    @Volatile private var totals = IntArray(CLASSES)

    val size: Int get() = rows.size

    fun count(word: String, cls: FieldClass): Int = rows[word.lowercase()]?.get(cls.ordinal) ?: 0

    fun total(cls: FieldClass): Int = totals[cls.ordinal]

    /** One more commit of [word] in a field of class [cls]. Words that cannot be stored change nothing. */
    fun add(word: String, cls: FieldClass) {
        val w = word.lowercase()
        if (!WordPenalties.storable(w)) return
        val old = rows[w]
        if (old == null && rows.size >= MAX_WORDS) trim()
        val row = old?.copyOf() ?: IntArray(CLASSES)
        if (row[cls.ordinal] >= MAX_COUNT) return
        row[cls.ordinal]++
        rows[w] = row
        totals = totals.copyOf().also { it[cls.ordinal]++ }
    }

    /** Without [word] (the bar's "forget this word"). True when it was held. */
    fun remove(word: String): Boolean {
        val row = rows.remove(word.lowercase()) ?: return false
        totals = IntArray(CLASSES) { (totals[it] - row[it]).coerceAtLeast(0) }
        return true
    }

    /** Drops the rarest quarter of the words. The class totals keep their commits: they did happen. */
    private fun trim() {
        val byUse = rows.entries.sortedWith(compareBy<Map.Entry<String, IntArray>> { it.value.sum() }.thenBy { it.key })
        for (e in byUse.take(MAX_WORDS / 4)) rows.remove(e.key)
    }

    /**
     * The `r` above for [word] in a field of class [cls]: the share of its personal count that
     * counts here, in (0, 1]. 1 for a word never counted.
     */
    fun share(word: String, cls: FieldClass): Float {
        val row = rows[word] ?: return 1f
        val t = totals
        var all = 0L
        for (n in t) all += n
        if (all <= 0) return 1f
        var n = 0
        for (v in row) n += v
        val expected = n * (t[cls.ordinal].toFloat() / all)
        return ((row[cls.ordinal] + PRIOR) / (expected + PRIOR)).coerceAtMost(1f)
    }

    /**
     * The multiplier that turns the personal boost of a word committed [personalCount] times
     * into the boost of `personalCount * share`: 1 when the word belongs here as much as
     * anywhere, down to `1 / boost` when it does not belong here at all.
     */
    fun factor(word: String, cls: FieldClass, personalCount: Int): Float {
        if (personalCount <= 0) return 1f
        val r = share(word, cls)
        if (r >= 1f) return 1f
        return boost(personalCount * r) / boost(personalCount.toFloat())
    }

    private fun boost(count: Float): Float = 1f + KineticaConstants.PERSONAL_BOOST * ln(1f + count)

    /** Rows `word|chat|email|search|code|other` joined by commas, then one `|...` row of class totals. */
    fun serialize(): String {
        val out = ArrayList<String>(rows.size + 1)
        out.add(FIELD + totals.joinToString(FIELD))
        for ((w, row) in rows.entries.sortedBy { it.key }) out.add(w + FIELD + row.joinToString(FIELD))
        return out.joinToString(ROW)
    }

    companion object {
        private val CLASSES = FieldClass.entries.size
        private const val ROW = ","
        private const val FIELD = "|"

        /** Whether the decoder reads the split (the pref, default off). */
        @Volatile @JvmField var enabled = false

        /** Commits added to both sides of the share, so a handful of commits says little. */
        const val PRIOR = 2f

        /** Words kept; the rarest quarter goes when a new word would pass it. */
        const val MAX_WORDS = 3000

        /** A word's commits in one class count no higher than this. */
        const val MAX_COUNT = 5000

        /** What [serialize] wrote. A row that does not parse is skipped. */
        fun parse(stored: String?): FieldWords {
            val out = FieldWords()
            if (stored.isNullOrEmpty()) return out
            var totals: IntArray? = null
            val sum = IntArray(CLASSES)
            for (line in stored.split(ROW)) {
                val f = line.split(FIELD)
                if (f.size != CLASSES + 1) continue
                val row = IntArray(CLASSES)
                var ok = true
                for (i in 0 until CLASSES) {
                    val n = f[i + 1].toIntOrNull()
                    if (n == null || n < 0) ok = false else row[i] = minOf(n, if (f[0].isEmpty()) Int.MAX_VALUE else MAX_COUNT)
                }
                if (!ok) continue
                if (f[0].isEmpty()) {
                    totals = row
                } else if (WordPenalties.storable(f[0]) && out.rows.size < MAX_WORDS) {
                    out.rows[f[0]] = row
                    for (i in 0 until CLASSES) sum[i] += row[i]
                }
            }
            // The stored totals count trimmed words too; never below what the rows hold.
            out.totals = IntArray(CLASSES) { maxOf(sum[it], totals?.get(it) ?: 0) }
            return out
        }
    }
}
