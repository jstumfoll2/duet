package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isOwnSpelling] and [isPronounCapital]: what keeps a pecked word of the user's own together,
 * and when the pronoun's capital goes back.
 */
class OwnSpellingTest {
    private val bundled = setOf("space", "bar", "how", "to", "into")
    private val counts = mapOf("spacebar" to 4, "howto" to 1, "into" to 30, "ab" to 9, "fdroid" to 2)
    private fun own(letters: String, whole: Boolean) = isOwnSpelling(counts, letters, whole) { it in bundled }

    @Test fun `a word typed before and in no list is the user's own`() {
        assertTrue(own("spacebar", whole = true))
        assertTrue(own("Spacebar", whole = true))
        assertTrue(own("fdroid", whole = true))
    }

    @Test fun `its beginnings are on the way to it`() {
        assertTrue(own("spaceb", whole = false))
        assertTrue(own("fdr", whole = false))
        assertFalse(own("spacebar", whole = false))
        assertFalse(own("spacex", whole = false))
    }

    @Test fun `one commit is not enough, since a glued pair is a spelling too`() {
        assertFalse(own("howto", whole = true))
        assertFalse(own("howt", whole = false))
    }

    @Test fun `a dictionary word is not the user's own however often typed`() {
        assertFalse(own("into", whole = true))
        assertFalse(own("int", whole = false))
    }

    @Test fun `two letters decide nothing`() {
        assertFalse(own("ab", whole = true))
        assertFalse(own("fd", whole = false))
    }

    @Test fun `only a lower-case i the rule capitalized loses its capital`() {
        assertTrue(isPronounCapital("i", "I"))
        assertFalse(isPronounCapital("I", "I"))
        assertFalse(isPronounCapital("i'm", "I'm"))
        assertFalse(isPronounCapital("in", "in"))
    }
}
