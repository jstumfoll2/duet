package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slips the keyboard fixes on its own as a word lands. */
class GrammarAutoFixTest {

    private class Lex(
        val freq: Map<String, Int>,
        val pairs: Map<Pair<String, String>, Int> = emptyMap(),
        val mine: Set<String> = emptySet(),
    ) : GrammarAutoFix.Lexicon {
        override fun isWord(word: String) = word in freq
        override fun frequencyByte(word: String) = freq[word] ?: -1
        override fun pairByte(prev: String, next: String) = pairs[prev to next] ?: 0
        override fun learned(word: String) = word in mine
    }

    private val lex = Lex(
        freq = mapOf(
            "to" to 250, "be" to 230, "go" to 220, "tried" to 180, "feel" to 190, "how" to 220,
            "tobe" to 85, "today" to 210, "token" to 140, "can" to 240, "canto" to 76, "ken" to 100,
            "day" to 210, "me" to 240, "tome" to 80,
        ),
        pairs = mapOf(
            ("to" to "be") to 238, ("tried" to "to") to 220, ("to" to "feel") to 180,
            ("how" to "to") to 230, ("to" to "day") to 120, ("can" to "to") to 155,
            ("to" to "ken") to 10, ("to" to "me") to 230,
        ),
    )

    @Test
    fun functionWordsTypedTwiceAreSlips() {
        assertTrue(GrammarAutoFix.doubled("to", "to"))
        assertTrue(GrammarAutoFix.doubled("The", "the"))
        assertTrue(GrammarAutoFix.doubled("and", "and"))
        assertTrue(GrammarAutoFix.doubled("I've", "I've"))
        // English doubles, and words a user repeats on purpose.
        assertFalse(GrammarAutoFix.doubled("had", "had"))
        assertFalse(GrammarAutoFix.doubled("that", "that"))
        assertFalse(GrammarAutoFix.doubled("is", "is"))
        assertFalse(GrammarAutoFix.doubled("so", "so"))
        assertFalse(GrammarAutoFix.doubled("very", "very"))
        assertFalse(GrammarAutoFix.doubled("to", "too"))
    }

    @Test
    fun aContractionSplitAtItsApostropheIsJoined() {
        assertEquals("isn't", GrammarAutoFix.contraction("Isn", "t"))
        assertEquals("haven't", GrammarAutoFix.contraction("haven", "t"))
        assertEquals("i'm", GrammarAutoFix.contraction("I", "m"))
        assertEquals("she'll", GrammarAutoFix.contraction("she", "ll"))
        assertEquals("we're", GrammarAutoFix.contraction("we", "re"))
        assertEquals("can't", GrammarAutoFix.contraction("can'", "t"))
        assertEquals("don't", GrammarAutoFix.contraction("don", "'t"))
        // A plural, a name's possessive, or a letter typed after a word.
        assertNull(GrammarAutoFix.contraction("take", "s"))
        assertNull(GrammarAutoFix.contraction("Lou", "s"))
        assertNull(GrammarAutoFix.contraction("going", "s"))
        assertNull(GrammarAutoFix.contraction("fiz", "z"))
        assertNull(GrammarAutoFix.contraction("a", "t"))
    }

    @Test
    fun iTakesAm() {
        assertEquals("am", GrammarAutoFix.afterI(null, "I", "are"))
        assertEquals("am", GrammarAutoFix.afterI("team", "i", "is"))
        assertNull(GrammarAutoFix.afterI("and", "I", "are"))
        assertNull(GrammarAutoFix.afterI("or", "I", "are"))
        assertNull(GrammarAutoFix.afterI(null, "I", "was"))
        assertNull(GrammarAutoFix.afterI(null, "we", "are"))
    }

    @Test
    fun toRunIntoTheWordBesideItIsCut() {
        assertEquals("to" to "be", GrammarAutoFix.joinedTo("tobe", lex))
        assertEquals("tried" to "to", GrammarAutoFix.joinedTo("triedto", lex))
        assertEquals("how" to "to", GrammarAutoFix.joinedTo("howto", lex))
        assertEquals("to" to "feel", GrammarAutoFix.joinedTo("tofeel", lex))
        assertEquals("to" to "me", GrammarAutoFix.joinedTo("tome", lex))
        assertEquals("To" to "be", GrammarAutoFix.joinedTo("Tobe", lex))
    }

    @Test
    fun aRealWordOrOneTheUserWritesStays() {
        // Common words, a word ending in `to`, and a cut the pairs do not hold.
        assertNull(GrammarAutoFix.joinedTo("today", lex))
        assertNull(GrammarAutoFix.joinedTo("canto", lex))
        assertNull(GrammarAutoFix.joinedTo("token", lex))
        assertNull(GrammarAutoFix.joinedTo("toxyz", lex))
        assertNull(GrammarAutoFix.joinedTo("tobe", Lex(lex.freq, lex.pairs, mine = setOf("tobe"))))
    }
}
