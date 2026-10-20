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
 * [KineticaConstants.PECK_SPLIT_RATIO] times over.
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
     * the rest as [rest]'s first, decoded after it.
     */
    class Split(val head: Int, val tap: List<WordCandidate>, val rest: List<WordCandidate>)

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
        ratio: Float = KineticaConstants.PECK_SPLIT_RATIO,
    ): Split? {
        val sorted = tokens.sortedBy { it.tStart }
        val head = tapHead(sorted)
        if (head == 0) return null
        val tapList = predictor.decode(sorted.subList(0, head), context)
        // The tapped word as its own decode reads it; a completion is a guess at a longer word.
        val tap = tapList.firstOrNull { it.source != WordCandidate.Source.COMPLETION } ?: return null
        if (tap.word.length == 1 && !oneLetterWord(tap.word[0])) return null
        val restList = predictor.decode(sorted.subList(head, sorted.size), context.takeLast(1) + tap.word)
        val rest = restList.firstOrNull() ?: return null
        val one = joined.firstOrNull()?.score ?: 0f
        val yes = tap.score * rest.score > ratio * one
        DecodeTrace.log {
            "  peck split ${if (yes) "yes" else "no"} tap=${tap.word}:${tap.score} " +
                "rest=${rest.word}:${rest.score} joined=${joined.firstOrNull()?.word}:$one"
        }
        return if (yes) Split(head, tapList, restList) else null
    }
}
