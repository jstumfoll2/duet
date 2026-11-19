package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a commit leaves for the previous-word fix and "fix this sentence" (#184: out of commitWordInternal). */
class CommitRevisionTest {

    private fun cand(word: String, score: Float) =
        WordCandidate(word, score, 0f, 1f, 1f, 0, WordCandidate.Source.SWIPE)

    private val heard = listOf(cand("heard", 0.9f), cand("hear", 0.5f))

    @Test
    fun aDecodedCommitIsRevisableWithItsReadings() {
        val r = CommitRevision()
        assertNull(r.onCommit("Heard", heard, deliberate = false, previousWordFix = true))
        val rev = r.revisable!!
        assertEquals("heard", rev.word)
        assertEquals(listOf("heard", "hear"), rev.readings.map { it.word })
        assertEquals(listOf("heard", "hear"), r.sentence.single().readings.map { it.word })
        // The next commit hands it back, for the fix to weigh against the new word.
        assertEquals("heard", r.onCommit("it", listOf(cand("it", 1f)), false, true)?.word)
    }

    @Test
    fun aWordTheUserChoseIsNotTheDecodersToRevise() {
        val r = CommitRevision()
        r.onCommit("hear", heard, deliberate = true, previousWordFix = true)
        assertNull(r.revisable)
        assertTrue(r.sentence.single().readings.isEmpty())
        // Not the decode's first reading: typed or picked, the same.
        r.onCommit("hear", heard, deliberate = false, previousWordFix = true)
        assertNull(r.revisable)
        assertTrue(r.sentence.last().readings.isEmpty())
    }

    @Test
    fun theFixSwitchedOffStillKeepsTheSentence() {
        val r = CommitRevision()
        r.onCommit("heard", heard, deliberate = false, previousWordFix = false)
        assertNull(r.revisable)
        assertEquals(2, r.sentence.single().readings.size)
    }

    @Test
    fun everyCommitMovesTheGenerationOn() {
        val r = CommitRevision()
        val g = r.generation
        r.onCommit("", emptyList(), deliberate = false, previousWordFix = true)
        assertNotEquals(g, r.generation)
        assertTrue("an empty commit adds no word", r.sentence.isEmpty())
    }

    @Test
    fun theSentenceKeepsOnlyTheNewestWords() {
        val r = CommitRevision(kept = 3)
        for (w in listOf("a", "b", "c", "d")) r.onCommit(w, emptyList(), deliberate = true, previousWordFix = true)
        assertEquals(listOf("b", "c", "d"), r.sentence.map { it.committed })
        assertNotNull(r.sentence)
    }
}
