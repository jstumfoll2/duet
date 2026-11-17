package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.ui.BarMark

// Ported from upstream Kinetica 3.0 (BarRules.kt / CommitRules.kt): the suggestion bar's
// optional word marks and space-word bold (#115). Pure, so the JVM suite pins them.

/** Any-letter (accented Italian included) with internal apostrophes: the learnable word shape. */
internal val WORD_RE = Regex("^\\p{L}+(?:'\\p{L}+)*$")

/** Letters a commit may learn: the learn's own shape gate, so `?` and a refused learn agree. */
internal fun learnable(w: String): Boolean =
    w.length <= KineticaConstants.MAX_WORD_LEN && WORD_RE.matches(w)

/**
 * A bar word's mark: none for a bundled spelling ([inList], any resident list), `C` for one the
 * user learned or added ([personal]), else `+` when committing it counts toward learning it and
 * `?` when it does not (a field that keeps nothing, letters no word is made of, a blocked word).
 */
internal fun wordMark(inList: Boolean, personal: Boolean, learnable: Boolean, teachesNothing: Boolean, blocked: Boolean): BarMark =
    when {
        inList -> BarMark.NONE
        personal -> BarMark.PERSONAL
        learnable && !teachesNothing && !blocked -> BarMark.LEARN
        else -> BarMark.UNKNOWN
    }

/**
 * The bar word to draw bold: the one [spaceWord] matches, case aside (the commit capitalizes after
 * it), or -1 when space writes a word the bar does not show, or nothing.
 */
internal fun boldIndex(words: List<String>, spaceWord: String?): Int =
    if (spaceWord.isNullOrEmpty()) -1 else words.indexOfFirst { it.equals(spaceWord, ignoreCase = true) }

/**
 * The word a space would write now, before its capital: Duet's finalizePendingWord read ahead. A
 * swipe writes the [tentative] already on screen. Tapped letters are written as typed unless the
 * commit may correct them ([mayCorrect]: letters to read, not a private field, not kept by hand)
 * and [correction] gives a word (autocorrect, a short slip, or a put-back space).
 */
internal fun spaceCommitWord(
    tentative: String,
    hasSwipe: Boolean,
    mayCorrect: Boolean,
    correction: () -> String?,
): String {
    if (hasSwipe || !mayCorrect) return tentative
    return correction() ?: tentative
}
