package com.kinetica.keyboard.ui

/**
 * Emoji for the word before the cursor ("pizza" offers 🍕), from the keywords and names in
 * `emoji_data.json`.
 *
 * Pure, because the JVM test runtime stubs `org.json` and has no glyph table: the service
 * parses the asset and filters what the device can draw, and hands the entries here.
 */
object EmojiSuggest {

    /** Most emoji the bar offers for one word: two cells keep the words their room. */
    const val MAX = 2

    /** Longest word looked up; the tail read before the cursor is this plus slack. */
    const val MAX_WORD = 24

    /** One asset entry, free of org.json. */
    class Entry(val ch: String, val name: String, val keywords: List<String>)

    class Index(entries: List<Entry>) {
        // Each word's emoji in the order they rank: an emoji named for the word first ("pizza"
        // is 🍕's name), then the ones that only list it as a keyword, each in asset order.
        private val byWord: Map<String, List<String>>

        init {
            val named = HashMap<String, MutableList<String>>()
            val tagged = HashMap<String, MutableList<String>>()
            for (e in entries) {
                for (w in nameWords(e.name)) named.getOrPut(w) { ArrayList() }.add(e.ch)
                for (k in e.keywords) tagged.getOrPut(k.lowercase()) { ArrayList() }.add(e.ch)
            }
            val all = HashMap<String, List<String>>()
            for (w in named.keys + tagged.keys) {
                all[w] = (named[w].orEmpty() + tagged[w].orEmpty()).distinct()
            }
            byWord = all
        }

        /**
         * Up to [limit] emoji for [word], the user's most used first ([uses], emoji to count),
         * then in the asset's ranking. A plural falls back to its singular: "dogs" offers 🐶.
         */
        fun lookup(word: String, uses: Map<String, Int> = emptyMap(), limit: Int = MAX): List<String> {
            val w = word.lowercase()
            val found = byWord[w]
                ?: w.removeSuffix("s").takeIf { it != w && it.length >= 2 }?.let { byWord[it] }
                ?: w.removeSuffix("es").takeIf { it != w && it.length >= 2 }?.let { byWord[it] }
                ?: return emptyList()
            if (uses.isEmpty()) return found.take(limit)
            // Stable: equal counts keep the asset's ranking.
            return found.sortedByDescending { uses[it] ?: 0 }.take(limit)
        }
    }

    /**
     * The words an entry's name offers it for: the whole name when it is one word, and the
     * word before "face" ("dog face" is the dog). Other multi-word names ("grinning face big
     * eyes") would offer every face for "face" or "eyes", which the keywords do better.
     */
    internal fun nameWords(name: String): List<String> {
        val parts = name.lowercase().split(' ').filter { it.isNotEmpty() }
        return when {
            parts.size == 1 -> parts
            parts.size == 2 && parts[1] == "face" -> listOf(parts[0])
            else -> emptyList()
        }
    }

    /**
     * The word just before the cursor in [before], which may end in one space (a committed
     * word and its autospace), or null when the cursor is not right after a word. Letters and
     * inner apostrophes count as the word; anything else before it must not be a letter or a
     * digit, so the tail of "abc123pizza" is no word.
     */
    fun wordBeforeCursor(before: CharSequence): String? {
        var end = before.length
        if (end > 0 && before[end - 1] == ' ') end--
        var start = end
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
        while (start < end && before[start] == '\'') start++
        if (start == end || end - start > MAX_WORD) return null
        if (start > 0 && before[start - 1].isLetterOrDigit()) return null
        return before.subSequence(start, end).toString().trimEnd('\'')
    }
}
