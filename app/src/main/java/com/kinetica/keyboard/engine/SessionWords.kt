package com.kinetica.keyboard.engine

/**
 * The words committed lately, oldest first, at most [capacity]: the cache of a cache language
 * model. A word typed a few sentences ago is far likelier than its frequency says (a name, a
 * topic word), so the decoder scores rarer words found here up ([WordPredictor.sessionWords]).
 *
 * Memory only: nothing is written anywhere, and the service feeds it only from commits it
 * would learn from, so incognito and password fields never reach it.
 */
class SessionWords(private val capacity: Int = KineticaConstants.SESSION_WINDOW) {
    private val words = ArrayDeque<String>()

    /** [word] committed: kept lowercase and accent-folded, as the trie spells it. */
    fun add(word: String) {
        val w = AccentFolder.fold(word.lowercase())
        if (w.isEmpty()) return
        words.addLast(w)
        while (words.size > capacity) words.removeFirst()
    }

    /** [word]'s latest commit taken back (deleted, retyped, replaced from the bar). */
    fun remove(word: String) {
        val w = AccentFolder.fold(word.lowercase())
        val i = words.lastIndexOf(w)
        if (i >= 0) words.removeAt(i)
    }

    fun clear() = words.clear()

    /** The distinct words held now, for [WordPredictor.sessionWords]. */
    fun snapshot(): Set<String> = words.toHashSet()

    val size: Int get() = words.size
}
