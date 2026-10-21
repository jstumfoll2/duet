package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * A tapped word followed by a swipe with no space between: `to` pecked, then `house` swiped.
 *
 * Short words are often pecked and the next word swiped straight after, and a tapped word gets
 * no automatic space, so the swipe used to join it and the pair decoded as one word ("to" plus
 * "help" read as `othello`). The same shape is also a real word started with taps and finished
 * with a swipe (`m`, `e` tapped, `rge` swiped), so the swipe ends the tapped word only when the
 * two words explain the buffer clearly better than one word does: the tapped word's own score
 * times the swipe's best reading after it, against the joined buffer's best,
 * [KineticaConstants.PECK_SPLIT_RATIO] times over, or [KineticaConstants.PECK_SPLIT_QUICK_RATIO]
 * times when the swipe began within [KineticaConstants.PECK_SPLIT_QUICK_GAP_MS] of the last tap's
 * lift: a word started with taps runs straight on into its swipe (half of them within 51 ms),
 * while a new word takes a moment to begin. The tapped word's score counts at most
 * [KineticaConstants.PECK_SPLIT_TAP_CAP]: `a` and `to` score 1.5 to 4 once the bigram and the
 * user's own counts are in, which let `a` plus a swiped `low` beat `allow` every time.
 */
object PeckSplit {
    /**
     * How many taps lead [tokens] when they are taps, all lifted, followed by a swipe that began
     * after the last of them; 0 for any other buffer.
     */
    fun tapHead(tokens: List<InputToken>): Int {
        val sorted = tokens.sortedBy { it.tStart }
        val first = sorted.indexOfFirst { it !is TapToken }
        if (first <= 0) return 0
        val lastLift = sorted.subList(0, first).maxOf { it.tEnd }
        return if (sorted[first].tStart >= lastLift) first else 0
    }

    /**
     * A buffer read as two words: the first [head] tokens, all taps, as [tap]'s first word, and
     * the rest as [rest]'s first, decoded after it. [chosen] when the two words beat the one
     * word; a reading that lost is kept so a backspace can still split the commit.
     */
    class Split(
        val head: Int,
        val tap: List<WordCandidate>,
        val rest: List<WordCandidate>,
        val chosen: Boolean = true,
    ) {
        /** The tapped word as its own decode read it; a completion is a guess at a longer word. */
        val tapWord: String? get() = tap.firstOrNull { it.source != WordCandidate.Source.COMPLETION }?.word
        val restWord: String? get() = rest.firstOrNull()?.word
    }

    /**
     * The two words [tokens] reads as when they beat one word, or null to keep the buffer one
     * word. [joined] is the buffer's own decode; [oneLetterWord] says which single letters stand
     * alone in the language (`a` and `I` in English), since every letter is in the wordlists.
     */
    fun split(
        predictor: WordPredictor,
        tokens: List<InputToken>,
        context: List<String>,
        joined: List<WordCandidate>,
        oneLetterWord: (Char) -> Boolean,
        ratio: Float? = null,
    ): Split? = read(predictor, tokens, context, joined, oneLetterWord, ratio)?.takeIf { it.chosen }

    /**
     * How many times better two words must read than one, for a swipe that began [gapMs] after
     * the last tap lifted.
     */
    fun ratioFor(gapMs: Long): Float =
        if (gapMs < KineticaConstants.PECK_SPLIT_QUICK_GAP_MS) KineticaConstants.PECK_SPLIT_QUICK_RATIO
        else KineticaConstants.PECK_SPLIT_RATIO

    /**
     * [tokens] read as two words whether or not they beat one ([Split.chosen] says which), or
     * null when the buffer has no tapped word at its head to read that way. [ratio] overrides
     * [ratioFor].
     */
    fun read(
        predictor: WordPredictor,
        tokens: List<InputToken>,
        context: List<String>,
        joined: List<WordCandidate>,
        oneLetterWord: (Char) -> Boolean,
        ratio: Float? = null,
    ): Split? {
        val sorted = tokens.sortedBy { it.tStart }
        val head = tapHead(sorted)
        if (head == 0) return null
        // A tap that was a stroke to the apostrophe key (`I've` as i, then ') starts a word.
        if (sorted.any { it is TapToken && it.apostrophe }) return null
        val tapList = predictor.decode(sorted.subList(0, head), context)
        // The tapped word as its own decode reads it; a completion is a guess at a longer word.
        val tap = tapList.firstOrNull { it.source != WordCandidate.Source.COMPLETION } ?: return null
        if (tap.word.length == 1 && !oneLetterWord(tap.word[0])) return null
        val restList = predictor.decode(sorted.subList(head, sorted.size), context.takeLast(1) + tap.word)
        val rest = restList.firstOrNull() ?: return null
        val one = joined.firstOrNull()?.score ?: 0f
        val gap = sorted[head].tStart - sorted.subList(0, head).maxOf { it.tEnd }
        val need = ratio ?: ratioFor(gap)
        val yes = minOf(tap.score, KineticaConstants.PECK_SPLIT_TAP_CAP) * rest.score > need * one
        DecodeTrace.log {
            "  peck split ${if (yes) "yes" else "no"} gap=$gap tap=${tap.word}:${tap.score} " +
                "rest=${rest.word}:${rest.score} joined=${joined.firstOrNull()?.word}:$one"
        }
        return Split(head, tapList, restList, chosen = yes)
    }

    /**
     * A word an automatic space ended and a letter then reopened (the retraction), read as that
     * word and what was typed after it: `to` and `a…` against `toad`. The first [head] tokens in
     * [tokens]' own order are the reopened word's taps (a reload seeds them first, with times of
     * its own making, so they are not sorted in); the rest is decoded after it. The space went
     * in because the user paused, so two words need only [ratio] times one word's score.
     *
     * A rest of taps alone must read as a word, not a completion, and one letter only as a word
     * that stands alone: `ad` then `d` keeps `add`, since `d` is no word.
     * Null when there is nothing after the head to read.
     */
    fun readRejoin(
        predictor: WordPredictor,
        tokens: List<InputToken>,
        head: Int,
        context: List<String>,
        joined: List<WordCandidate>,
        oneLetterWord: (Char) -> Boolean,
        ratio: Float = KineticaConstants.REJOIN_SPLIT_RATIO,
    ): Split? {
        if (head <= 0 || head >= tokens.size) return null
        val headTokens = tokens.subList(0, head)
        if (headTokens.any { it !is TapToken }) return null
        val restTokens = tokens.subList(head, tokens.size).sortedBy { it.tStart }
        if (restTokens.any { it is TapToken && it.apostrophe }) return null
        val tapList = predictor.decode(headTokens, context)
        val tap = tapList.firstOrNull { it.source != WordCandidate.Source.COMPLETION } ?: return null
        val restList = predictor.decode(restTokens, context.takeLast(1) + tap.word)
        val rest = restList.firstOrNull()
        val one = joined.firstOrNull()?.score ?: 0f
        val restSpelled = rest != null && (restTokens.any { it !is TapToken } || restIsWord(predictor, rest, oneLetterWord))
        val yes = rest != null && restSpelled &&
            minOf(tap.score, KineticaConstants.PECK_SPLIT_TAP_CAP) * rest.score > ratio * one
        DecodeTrace.log {
            "  rejoin ${if (yes) "split" else "join"} head=${tap.word}:${tap.score} " +
                "rest=${rest?.word}:${rest?.score} joined=${joined.firstOrNull()?.word}:$one"
        }
        if (rest == null) return null
        return Split(head, tapList, restList, chosen = yes)
    }

    // A tapped rest reads as a word of its own, a one-letter one only where letters stand alone.
    private fun restIsWord(predictor: WordPredictor, rest: WordCandidate, oneLetterWord: (Char) -> Boolean): Boolean {
        if (rest.source == WordCandidate.Source.COMPLETION) return false
        return if (rest.word.length == 1) oneLetterWord(rest.word[0].lowercaseChar()) else predictor.isWord(rest.word)
    }
}
