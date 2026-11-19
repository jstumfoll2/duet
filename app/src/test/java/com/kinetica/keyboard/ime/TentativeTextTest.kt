package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word in hand is replaced only while the field still shows it (audit M6, #184). */
class TentativeTextTest {

    @Test
    fun aWordStillInTheFieldMayBeReplaced() {
        val t = TentativeText().apply { set("hello") }
        assertTrue(t.stillInField { "hello" })
        assertTrue("an app may capitalize", t.stillInField { "Hello" })
    }

    @Test
    fun aWordTheAppChangedIsLeftAlone() {
        val t = TentativeText().apply { set(":)") }
        assertFalse(t.stillInField { "🙂" })
    }

    @Test
    fun anEditorThatDoesNotAnswerIsTrusted() {
        val t = TentativeText().apply { set("word") }
        assertTrue(t.stillInField { null })
    }

    @Test
    fun nothingInHandNeedsNoRead() {
        val t = TentativeText()
        var asked = false
        assertTrue(t.stillInField { asked = true; "" })
        assertFalse(asked)
        t.set("abc")
        t.clear()
        assertEquals(0, t.length)
        assertEquals("", t.word)
    }

    @Test
    fun itReadsExactlyTheWordsLength() {
        val t = TentativeText().apply { set("four") }
        var n = -1
        t.stillInField { n = it; "four" }
        assertEquals(4, n)
    }
}
