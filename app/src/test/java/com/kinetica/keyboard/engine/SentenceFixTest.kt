package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.PreviousWordFix.Reading
import com.kinetica.keyboard.engine.SentenceFix.Change
import com.kinetica.keyboard.engine.SentenceFix.Word
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceFixTest {

    /** A toy model: the listed pairs are likely, every other pair is not. */
    private val good = setOf("went to", "to the", "the shop", "i went", "see you", "you there")
    private val lm = { w: String, prev: String? -> if (prev != null && "${prev.lowercase()} ${w.lowercase()}" in good) -1f else -8f }

    private fun word(committed: String, vararg others: Pair<String, Float>) =
        Word(committed, listOf(Reading(committed, 1f)) + others.map { Reading(it.first, exp(-it.second)) })

    private fun fix(words: List<Word>, before: String? = null) = SentenceFix.fix(words, before, 1f, lm)

    @Test fun changesTheWordsThatReadBetterTogether() {
        // "went tot he shop": each of "to" and "the" was a close second.
        val words = listOf(word("went"), word("tot", "to" to 0.5f), word("he", "the" to 0.5f), word("shop"))
        assertEquals(listOf(Change(1, "tot", "to"), Change(2, "he", "the")), fix(words))
    }

    @Test fun leavesASentenceThatAlreadyReadsBest() {
        val words = listOf(word("went", "want" to 0.5f), word("to", "tot" to 0.5f), word("the", "he" to 0.5f), word("shop"))
        assertEquals(emptyList<Change>(), fix(words))
        assertEquals(emptyList<Change>(), fix(emptyList()))
        assertEquals(emptyList<Change>(), fix(listOf(word("went"), word("to"))))
    }

    @Test fun aSmallGainIsNotWorthAChange() {
        // "you" reads a little better after "see", but its own score is 2 nats behind (the gap)
        // and a change must clear the margin.
        val lmWeak = { w: String, prev: String? -> if (prev == "see" && w == "you") -7.5f else -8f }
        val words = listOf(word("see"), word("your", "you" to 0.2f))
        assertEquals(emptyList<Change>(), SentenceFix.fix(words, null, 1f, lmWeak))
    }

    @Test fun aDistantRunnerUpIsNotOffered() {
        val words = listOf(word("went"), word("tot", "to" to SentenceFix.GAP + 0.1f), word("the"))
        assertEquals(emptyList<Change>(), fix(words))
    }

    @Test fun aWordTheUserChoseIsContextOnly() {
        // No readings: typed or picked. It is never changed, and it is evidence for its neighbour.
        val words = listOf(Word("went"), word("tot", "to" to 0.5f), Word("he"), word("shop"))
        // "to he" reads no better than "tot he", and the fixed "he" cannot become "the": a
        // reading's own score already holds how well it follows "went", so nothing pays.
        assertEquals(emptyList<Change>(), fix(words))
        val picked = listOf(word("went"), Word("tot", listOf(Reading("other", 1f), Reading("to", 0.9f))), word("the"))
        assertEquals(emptyList<Change>(), fix(picked))
    }

    @Test fun theWordBeforeTheSentenceCounts() {
        val words = listOf(word("tot", "to" to 0.5f), word("the"))
        assertEquals(listOf(Change(0, "tot", "to")), fix(words))
        // Both readings follow "went" as badly when the word before is unknown; "to the" still wins.
        assertEquals(listOf(Change(0, "tot", "to")), fix(words, "went"))
    }

    @Test fun plansTheEditOfTheTextAsItStands() {
        val history = listOf(word("I"), word("went"), word("tot", "to" to 0.5f), word("he", "the" to 0.5f), word("shop"))
        val p = SentenceFix.plan("Hello there. I went tot he shop ", history, 1f, lm)!!
        assertEquals("tot he shop ", p.span)
        assertEquals("to the shop ", p.text)
        assertEquals(listOf(2, 1), p.backs)
        assertEquals("tot → to, he → the", p.label)
    }

    @Test fun keepsCapitalsAndMarks() {
        val history = listOf(word("Tot", "to" to 0.5f), word("the"), word("shop"))
        val p = SentenceFix.plan("ok.\nTot the, shop", history, 1f, lm)!!
        assertEquals("Tot the, shop", p.span)
        assertEquals("To the, shop", p.text)
    }

    @Test fun readsOnlyTheWordsThatStillStand() {
        val history = listOf(word("went"), word("tot", "to" to 0.5f), word("he", "the" to 0.5f), word("shop"))
        // "he" was edited to "her" since: only "shop" still matches, and one word is no sentence.
        assertNull(SentenceFix.plan("went tot her shop", history, 1f, lm))
        // The sentence started after "tot": that word is not touched.
        val p = SentenceFix.plan("went tot. he shop", history, 1f, lm)!!
        assertEquals("he shop", p.span)
        assertEquals("the shop", p.text)
        // Two spaces: the run stops there.
        assertNull(SentenceFix.plan("went tot  he shop  ", history, 1f, lm))
        assertNull(SentenceFix.plan("", history, 1f, lm))
        assertNull(SentenceFix.plan("went tot he shop", emptyList(), 1f, lm))
    }
}
