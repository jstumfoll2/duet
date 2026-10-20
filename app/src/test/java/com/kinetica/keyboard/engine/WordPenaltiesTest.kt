package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPenaltiesTest {

    @Test
    fun roundTripsThroughItsPreferenceString() {
        val p = WordPenalties.EMPTY.with("The", "en", 2).with("che", "it", 1)
        val back = WordPenalties.parse(p.serialize())
        assertEquals(2, back.steps("the", "en"))
        assertEquals(1, back.steps("CHE", "it"))
        assertEquals(0, back.steps("the", "it"))
    }

    @Test
    fun zeroStepsRemoveTheWordAndStepsAreCapped() {
        val p = WordPenalties.EMPTY.with("the", "en", 99)
        assertEquals(KineticaConstants.DOWNRANK_MAX_STEPS, p.steps("the", "en"))
        assertEquals("", p.with("the", "en", 0).serialize())
    }

    @Test
    fun malformedRowsAreDropped() {
        val p = WordPenalties.parse("en|the|2,garbage,en||3,en|and|x,|a|1")
        assertEquals(2, p.steps("the", "en"))
        assertEquals("en|the|2", p.serialize())
    }

    @Test
    fun separatorsCannotBeStored() {
        assertFalse(WordPenalties.storable("a,b"))
        assertFalse(WordPenalties.storable("a|b"))
        assertTrue(WordPenalties.storable("don't"))
    }

    @Test
    fun aStepHalvesTheScore() {
        assertEquals(1f, WordPenalties.factor(0), 0f)
        assertEquals(0.25f, WordPenalties.factor(2), 1e-6f)
    }

    @Test
    fun suggestLessPutsACommonWordBelowARarerOne() {
        // The personal count only adds, so before the downrank a frequent word could not be
        // pushed under a rarer one with the same shape.
        val g = TestData.qwertyGeometry()
        val trie = Trie.build(listOf("thee" to 20_000, "the" to 60_000, "a" to 50_000))
        val input = listOf(TestData.swipe("the", g, t0 = 0, durMs = 300, stream = StreamId.LEFT))
        val p = WordPredictor(trie, BigramTable.EMPTY, g)
        assertEquals("the", p.decode(input, emptyList()).first().word)
        p.penalties = WordPenalties.EMPTY.with("the", "", 6).forLanguage("")
        val words = p.decode(input, emptyList()).map { it.word }
        assertEquals("the should drop under thee: $words", "thee", words.first())
    }
}
