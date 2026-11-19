package com.kinetica.keyboard.ime

/**
 * What the last commit taught, so that taking the commit back (a delete into it, a retype, a
 * strip correction) takes back exactly that, once. Moved out of KineticaIME (#184).
 *
 * A shared word can be filed away from its provenance, so a take-back reads the language the
 * word went to here rather than deriving it again.
 */
internal class LearnedTakeBack {
    /** The last commit, lowercased, while the +1 it earned can still be taken back by deleting it. */
    var commit: String? = null

    /** The word the last learning commit filed, lowercased, and the list it went to. */
    var word: String? = null
        private set
    var wordLanguage: String? = null
        private set

    private var pair: Pair<String, String>? = null
    private var pairLanguage: String? = null

    fun learnedWord(word: String, language: String) {
        this.word = word.lowercase()
        wordLanguage = language
    }

    /** The pair learned last, already lowercased. */
    fun learnedPair(prev: String, word: String, language: String) {
        pair = prev to word
        pairLanguage = language
    }

    /** The list [word] went to, when it is the word last learned. */
    fun languageOf(word: String): String? = wordLanguage?.takeIf { word.lowercase() == this.word }

    /** Whether the pair learned last ends in [word] (lowercased). */
    fun pairEndsWith(word: String): Boolean = pair?.second == word

    /** The pair learned last and its list, handed out once: a second retype takes nothing back. */
    fun takePair(): Pair<Pair<String, String>, String>? {
        val p = pair ?: return null
        val language = pairLanguage ?: return null
        pair = null
        pairLanguage = null
        return p to language
    }
}
