package com.kinetica.keyboard.engine

/**
 * The words a user-dictionary entry or a contact name contributes to [DictionaryLoader.load]'s
 * vocabulary: "Mary-Jane O'Brien" gives Mary, Jane and O'Brien. A part the language's word
 * shape rejects (digits, an emoji, another script) is skipped, and so is one letter.
 */
object SystemWords {

    private const val MIN_LEN = 2
    private const val MAX_LEN = 20

    private val SEPARATORS = Regex("[\\s,.;:()\\[\\]/&+\"-]+")

    fun of(text: String, lang: String): List<String> {
        val shape = DictionaryMerger.wordPattern(lang)
        return text.split(SEPARATORS).map { it.trim('\'') }.filter { w ->
            w.length in MIN_LEN..MAX_LEN && shape.matches(w.lowercase())
        }
    }
}
