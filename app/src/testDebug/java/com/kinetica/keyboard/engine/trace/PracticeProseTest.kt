package com.kinetica.keyboard.engine.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PracticeProseTest {

    private val passage = PracticeProse.words("It's too late, so I'll call you.")

    @Test
    fun labelsAreTheWordAsCommitted() {
        assertEquals(listOf("it's", "too", "late", "so", "i'll", "call", "you"), passage.map { it.label })
        assertEquals("late,", passage[2].shown)
        assertEquals("don't", PracticeProse.labelOf("Don’t"))
        assertEquals("hello", PracticeProse.labelOf("\"Hello!\""))
    }

    @Test
    fun aWordWithNoLettersIsNotAWord() {
        assertEquals(listOf("wait", "what"), PracticeProse.words("Wait - what?").map { it.label })
    }

    @Test
    fun theWordBeingTypedIsTheFirstOneNotFollowedByAnything() {
        // Empty field: the first word.
        assertEquals(0, PracticeProse.progress(passage, "").current)
        // The keyboard has put "It's" in but no space yet: still the first word, so its line
        // (written as the next swipe starts) is labelled with it.
        assertEquals(0, PracticeProse.progress(passage, "It's").current)
        val p = PracticeProse.progress(passage, "It's to")
        assertEquals(listOf(true), p.finished)
        assertEquals(1, p.current)
        // Punctuation straight after the word finishes it, as a space does.
        assertEquals(3, PracticeProse.progress(passage, "It's too late,").current)
        assertEquals(3, PracticeProse.progress(passage, "It's too late, ").current)
    }

    @Test
    fun finishedWordsSayWhetherTheyCameOutAsShown() {
        val p = PracticeProse.progress(passage, "its to late so ")
        assertEquals(listOf(false, false, true, true), p.finished)
        assertEquals(2, p.hits)
        assertEquals(4, p.current)
    }

    @Test
    fun aLonePunctuationMarkIsNotAWord() {
        assertEquals(3, PracticeProse.progress(passage, "It's too late , ").current)
    }

    @Test
    fun theLastWordFinishesThePassage() {
        assertEquals(6, PracticeProse.progress(passage, "It's too late, so I'll call you").current)
        val done = PracticeProse.progress(passage, "It's too late, so I'll call you.")
        assertNull(done.current)
        assertEquals(7, done.finished.size)
        assertEquals(7, done.hits)
        // Extra words past the end change nothing.
        assertEquals(7, PracticeProse.progress(passage, "It's too late, so I'll call you. Bye now ").finished.size)
    }

    @Test
    fun everyPassageHasWordsToType() {
        for (p in PracticeProse.PASSAGES) {
            val w = PracticeProse.words(p)
            assertTrue(p, w.size >= 8)
            for (word in w) assertTrue("$p: ${word.label}", word.label.all { it in 'a'..'z' || it == '\'' })
        }
    }

    @Test
    fun theSamePassageNeverComesTwiceInARow() {
        val rnd = Random(5)
        var last = PracticeProse.pick(rnd)
        val seen = HashSet<Int>()
        repeat(2000) {
            val next = PracticeProse.pick(rnd, last)
            assertNotEquals(last, next)
            assertTrue(next in PracticeProse.PASSAGES.indices)
            seen.add(next)
            last = next
        }
        assertEquals(PracticeProse.PASSAGES.size, seen.size)
    }
}
