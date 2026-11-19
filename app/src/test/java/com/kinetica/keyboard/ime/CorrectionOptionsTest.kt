package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The options a committed word offers back, in order (#184: moved out of commitWordInternal). */
class CorrectionOptionsTest {

    private val asIs: (String) -> String = { it }

    @Test
    fun theWordLeadsAndTheTypedLettersFollowIt() {
        val c = correctionOptions("tino", listOf("tino", "tin", "tine"), "tion", emptySet(), asIs)
        assertEquals(listOf("tino", "tion", "tin", "tine"), c.options)
        assertEquals("tion", c.typed)
    }

    @Test
    fun eachOptionOnceAsWritten() {
        val upper: (String) -> String = { it.replaceFirstChar { ch -> ch.uppercaseChar() } }
        val c = correctionOptions("The", listOf("the", "then"), "the", emptySet(), upper)
        assertEquals(listOf("The", "Then"), c.options)
        assertNull("the letters typed are the word: nothing to go back to", c.typed)
    }

    @Test
    fun aBlockedSpellingIsNeverOfferedBackButTheWordStays() {
        val c = correctionOptions("damn", listOf("damn", "darn", "dam"), "dsmn", setOf("damn", "dam", "dsmn"), asIs)
        assertEquals(listOf("damn", "darn"), c.options)
        assertNull(c.typed)
    }

    @Test
    fun noCandidatesNoLetters() {
        val c = correctionOptions("ok", emptyList(), "", emptySet(), asIs)
        assertEquals(listOf("ok"), c.options)
        assertNull(c.typed)
    }

    @Test
    fun theColumnPutsTheTypedLettersFirstAndFilesThemUnderTheWordsLanguage() {
        val c = correctionOptions("tino", listOf("tino", "tin", "tine"), "tion", emptySet(), asIs)
        assertEquals(listOf("tion", "tin", "tine"), c.alternatives())
        assertEquals(mapOf("tin" to "en", "tion" to "es"), c.languages(mapOf("tin" to "en"), "es"))
    }

    @Test
    fun withNoTypedLettersTheColumnIsTheOtherOptions() {
        val c = correctionOptions("the", listOf("the", "then"), "the", emptySet(), asIs)
        assertEquals(listOf("then"), c.alternatives())
        assertEquals(mapOf("then" to "en"), c.languages(mapOf("then" to "en"), "es"))
    }
}
