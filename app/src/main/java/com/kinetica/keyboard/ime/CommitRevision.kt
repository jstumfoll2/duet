package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.PreviousWordFix
import com.kinetica.keyboard.engine.SentenceFix
import com.kinetica.keyboard.engine.models.WordCandidate

/** A decoded commit's ranked readings, for the previous-word fix ([PreviousWordFix]). */
internal class Revisable(val word: String, val readings: List<PreviousWordFix.Reading>)

/**
 * The commits the keyboard may still revise after the fact (moved out of KineticaIME, #184):
 * the last decoded commit for the previous-word fix, and the sentence so far for "fix this
 * sentence" ([SentenceFix], #139). Only a decoded commit carries readings: a word the user
 * typed, picked or corrected is not the decoder's to revise. In memory only.
 */
internal class CommitRevision(private val kept: Int = SENTENCE_WORDS_KEPT) {
    /** The last decoded commit, while the previous-word fix may still revise it. */
    var revisable: Revisable? = null

    /**
     * Bumped by every commit, so a revision decided for one commit never lands after another.
     * Read on the decode thread.
     */
    @Volatile
    var generation = 0
        private set

    /** The words committed lately, oldest first, at most [kept]. */
    val sentence = ArrayList<SentenceFix.Word>()

    /**
     * Records the commit of [word] from [candidates], the decode's ranked list. [deliberate] is
     * a word the user chose. Returns the commit before it, if that one is still revisable.
     */
    fun onCommit(
        word: String,
        candidates: List<WordCandidate>,
        deliberate: Boolean,
        previousWordFix: Boolean,
    ): Revisable? {
        val before = revisable
        val decoded = !deliberate && word.isNotEmpty() &&
            candidates.firstOrNull()?.word.equals(word, ignoreCase = true)
        val readings = if (decoded) candidates.map { PreviousWordFix.Reading(it.word, it.score) } else emptyList()
        revisable = if (previousWordFix && decoded) Revisable(word.lowercase(), readings) else null
        generation++
        if (word.isNotEmpty()) {
            sentence.add(SentenceFix.Word(word, readings))
            if (sentence.size > kept) sentence.removeAt(0)
        }
        return before
    }

    companion object {
        const val SENTENCE_WORDS_KEPT = 24
    }
}
