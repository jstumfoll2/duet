package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.AccentFolder

/**
 * Emoji for the word before the cursor ("pizza" offers 🍕), from the names and search words of
 * the bundled set (`assets/emoji/`, read by [EmojiAssets]) in the user's languages.
 *
 * Pure, because the JVM test runtime has no glyph table: the service reads the set, which has
 * already dropped what the device cannot draw, and hands it here.
 */
object EmojiSuggest {

    /** Most emoji the bar offers for one word: two cells keep the words their room. */
    const val MAX = 2

    /** Longest word looked up; the tail read before the cursor is this plus slack. */
    const val MAX_WORD = 24

    /**
     * A CLDR keyword ([Entry.other]) is too generic to offer when this many emoji or more are
     * named with it: "face", "hand", "person", Spanish "de". Feelings ("happy", "amor") are
     * keywords, rarely names, so they stay.
     */
    const val MAX_NAMES_PER_OTHER_WORD = 12

    /** Nor when more emoji than this list it as a keyword ("love" is on 32). */
    const val MAX_PER_OTHER_WORD = 40

    /**
     * One emoji's words in one language: its [name], the [keywords] written for this project,
     * and the [other] CLDR keywords, which rank last and are dropped when too generic.
     */
    class Entry(val ch: String, val name: String, val keywords: List<String>, val other: List<String> = emptyList())

    class Index(entries: List<Entry>) {
        // Each word's emoji in the order they rank: an emoji named for the word first ("pizza"
        // is 🍕's name), then the ones that list it as a hand-written keyword, then the CLDR
        // keywords, each in the set's order. Words are folded: "corazón" is "corazon".
        private val byWord: Map<String, List<String>>

        init {
            val named = HashMap<String, MutableList<String>>()
            val tagged = HashMap<String, MutableList<String>>()
            val other = HashMap<String, MutableList<String>>()
            val inNames = HashMap<String, MutableSet<String>>()
            for (e in entries) {
                for (w in e.name.split(' ')) if (w.isNotEmpty()) inNames.getOrPut(fold(w)) { HashSet() }.add(e.ch)
                for (w in nameWords(e.name)) named.getOrPut(fold(w)) { ArrayList() }.add(e.ch)
                for (k in e.keywords) tagged.getOrPut(fold(k)) { ArrayList() }.add(e.ch)
                for (k in e.other) other.getOrPut(fold(k)) { ArrayList() }.add(e.ch)
            }
            val all = HashMap<String, List<String>>()
            for (w in named.keys + tagged.keys + other.keys) {
                val broad = other[w].orEmpty().distinct().takeIf {
                    it.size <= MAX_PER_OTHER_WORD && (inNames[w]?.size ?: 0) < MAX_NAMES_PER_OTHER_WORD
                }.orEmpty()
                val list = (named[w].orEmpty() + tagged[w].orEmpty() + broad).distinct()
                if (list.isNotEmpty()) all[w] = list
            }
            byWord = all
        }

        /**
         * Up to [limit] emoji for [word], the user's most used first ([uses], emoji to count),
         * then in the asset's ranking. A plural falls back to its singular: "dogs" offers 🐶.
         */
        fun lookup(word: String, uses: Map<String, Int> = emptyMap(), limit: Int = MAX): List<String> {
            val w = fold(word)
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
     * The index for the bundled set: one [Entry] per emoji and language in [langs] (each list
     * indexed by [EmojiCatalog.Entry.index], as [EmojiSearch.rank] takes them). The words come
     * folded from [EmojiCatalog.parseWords].
     */
    fun fromCatalog(entries: List<EmojiCatalog.Entry>, langs: List<List<EmojiCatalog.Words>>): Index {
        val out = ArrayList<Entry>(entries.size * langs.size)
        for (e in entries) {
            for (lang in langs) {
                val w = lang.getOrNull(e.index) ?: continue
                out.add(Entry(e.ch, w.name.joinToString(" "), w.hand, w.other))
            }
        }
        return Index(out)
    }

    private fun fold(w: String): String = AccentFolder.fold(w.lowercase())

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
