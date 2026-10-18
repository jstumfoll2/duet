package com.kinetica.keyboard.engine

/**
 * Notices words typed in a language other than the active one, so the keyboard can say it is
 * in the wrong language. A third of batch 7's misses (2026-10-18) were English typed with
 * Spanish left active and Mix off: nothing on screen said so but the small spacebar code.
 *
 * Fed one verdict per commit. A word only another enabled language holds counts toward that
 * language; a word the active language holds ends the run; a word no dictionary holds (a name,
 * a typo) or one both hold says nothing. The run fires once, at [threshold], and not again until
 * it is broken.
 */
class LanguageStreak(private val threshold: Int = KineticaConstants.WRONG_LANGUAGE_STREAK) {

    private var language: String? = null
    private var count = 0

    /**
     * Records one commit. [foreign] is the other language that alone holds the word, or null;
     * [activeHolds] is whether the active language holds it. Returns the language to suggest
     * when this commit completes a run, else null.
     */
    fun onCommit(foreign: String?, activeHolds: Boolean): String? {
        if (activeHolds) {
            reset()
            return null
        }
        if (foreign == null) return null
        if (foreign != language) {
            language = foreign
            count = 0
        }
        count++
        return if (count == threshold) foreign else null
    }

    fun reset() {
        language = null
        count = 0
    }
}
