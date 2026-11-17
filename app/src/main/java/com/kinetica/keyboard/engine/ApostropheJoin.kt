package com.kinetica.keyboard.engine

/**
 * Whether a tapped apostrophe continues the pecked word before it instead of ending it (#164,
 * after upstream Kinetica 3.0.2): `I` `'` `d` is one word, `I'd`, for learning, autocorrect and
 * reopening, not `I'` and a new word `d`.
 *
 * Only where the word with an apostrophe after it can still become a word: the dictionary holds
 * one that starts that way (`don'` for `don't`, `i'` for `i'm`), or [MarkedContractions] offers
 * one (`i'll`, `we're`, which the list leaves out so they never compete with `ill` and `were`).
 * Anywhere else (`nell'`, a name, a quote) the apostrophe ends the word as before.
 *
 * Pure: the dictionary comes in as [isLivePrefix].
 */
object ApostropheJoin {

    /** True when [word], all letters, then `'` is the start of a word in [language]. */
    fun joins(word: String, language: String, isLivePrefix: (String) -> Boolean): Boolean {
        if (word.isEmpty() || word.length >= KineticaConstants.MAX_WORD_LEN || !word.all { it.isLetter() }) return false
        val head = word.lowercase() + "'"
        return isLivePrefix(head) || MarkedContractions.startsWith(language, head)
    }
}
