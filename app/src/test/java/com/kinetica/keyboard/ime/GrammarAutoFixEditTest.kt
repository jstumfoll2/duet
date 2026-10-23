package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.GrammarAutoFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The grammar auto-fix's edit of the editor text, read off the text before the cursor. */
class GrammarAutoFixEditTest {

    private val lex = object : GrammarAutoFix.Lexicon {
        val words = setOf("to", "be", "tried", "go", "today")
        override fun isWord(word: String) = word in words
        override fun frequencyByte(word: String) = if (word == "today") 200 else 50
        override fun pairByte(prev: String, next: String) = 200
        override fun learned(word: String) = false
    }

    private fun fix(tail: String, word: String) = grammarAutoFix(tail, word, lex)?.let { Triple(it.span, it.shown, it.text) }

    @Test
    fun aDoubledWordLosesItsSecondCopy() {
        assertEquals(Triple(5, "to to", "to"), fix("it seems to to", "to"))
        assertEquals(Triple(7, "The the", "The"), fix("The the", "the"))
        assertNull(fix("it seems to, to", "to"))
    }

    @Test
    fun aSplitContractionIsJoinedInTheCaseTyped() {
        assertEquals(Triple(5, "Isn t", "Isn't"), fix("Isn t", "t"))
        assertEquals(Triple(3, "I m", "I'm"), fix("so I m", "m"))
    }

    @Test
    fun iAreIsIAm() {
        assertEquals(Triple(3, "are", "am"), fix("I are", "are"))
        assertNull(fix("you and I are", "are"))
    }

    @Test
    fun toRunIntoAWordIsCut() {
        assertEquals(Triple(4, "tobe", "to be"), fix("it seems tobe", "tobe"))
        assertEquals(Triple(7, "triedto", "tried to"), fix("triedto", "triedto"))
        assertNull(fix("today", "today"))
    }
}
