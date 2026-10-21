package com.kinetica.keyboard.ui

/**
 * The emoji panel's search: what a typed query ("pi", "thumbs up", "happy") finds among the
 * asset's names and keywords, best first.
 *
 * Pure, for the same reason as [EmojiSuggest]: the panel parses the asset and filters what the
 * device can draw, and hands the entries here.
 */
class EmojiSearch(private val entries: List<EmojiSuggest.Entry>) {

    // Each entry's words, lowercased once: the name split on spaces, and the keywords whole.
    private val nameWords = entries.map { e -> e.name.lowercase().split(' ').filter { it.isNotEmpty() } }
    private val keywords = entries.map { e -> e.keywords.map { it.lowercase() } }

    /**
     * Up to [limit] emoji for [query], best first. Every word of the query must match the
     * entry, so "red heart" narrows "heart". A word scores most when it is a whole name word
     * or keyword, less as the start of one, least inside a longer name; ties go to the user's
     * most picked ([uses], emoji to count), then to the asset's order.
     */
    fun find(query: String, uses: Map<String, Int> = emptyMap(), limit: Int = MAX): List<String> {
        val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val scored = ArrayList<Triple<Int, Int, Int>>() // score, uses, asset index
        for (i in entries.indices) {
            var total = 0
            for (w in words) {
                val s = score(w, nameWords[i], keywords[i])
                if (s == 0) {
                    total = 0
                    break
                }
                total += s
            }
            if (total > 0) scored.add(Triple(total, uses[entries[i].ch] ?: 0, i))
        }
        return scored
            .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.first }.thenByDescending { it.second }.thenBy { it.third })
            .map { entries[it.third].ch }
            .distinct()
            .take(limit)
    }

    private fun score(w: String, name: List<String>, kw: List<String>): Int {
        // "dogs" finds the dog: a plural matches as its singular, a step below the word itself.
        val singular = if (w.length > 3 && w.endsWith('s')) w.dropLast(1) else null
        return when {
            w in name || w in kw -> EXACT
            singular != null && (singular in name || singular in kw) -> PLURAL
            name.any { it.startsWith(w) } -> NAME_PREFIX
            kw.any { it.startsWith(w) } -> KEYWORD_PREFIX
            w.length >= 3 && name.any { it.contains(w) } -> INSIDE
            else -> 0
        }
    }

    companion object {
        /** Results shown at most; a short query like "s" matches most of the asset. */
        const val MAX = 48

        private const val EXACT = 8
        private const val PLURAL = 6
        private const val NAME_PREFIX = 4
        private const val KEYWORD_PREFIX = 3
        private const val INSIDE = 1
    }
}
