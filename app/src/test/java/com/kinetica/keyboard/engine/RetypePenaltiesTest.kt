package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.ScoreTerms
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RetypePenaltiesTest {

    @After
    fun reset() {
        RetypePenalties.enabled = false
    }

    @Test
    fun offByDefault() {
        assertTrue(!RetypePenalties.enabled)
    }

    @Test
    fun aHabitIsPenalisedOnlyBesideTheWordItWasMeantAs() {
        var s = RetypePenalties.EMPTY
        repeat(4) {
            s = s.withCommit("en", "god")
            s = s.with("en", "God", "good")
        }
        assertEquals(4, s.count("en", "god", "good"))
        assertEquals(4, s.seen("en", "god"))
        val c = s.forLanguage("en")
        assertTrue(c.factorFor("god", listOf("god", "good")) < 0.5f)
        assertEquals(1f, c.factorFor("god", listOf("god", "gods")), 0f)
        assertEquals(1f, c.factorFor("good", listOf("god", "good")), 0f)
        assertTrue(s.forLanguage("es").isEmpty)
    }

    @Test
    fun aCommonWordThatMostlyStandsIsLeftAlone() {
        var s = RetypePenalties.EMPTY.with("en", "the", "three")
        val once = s.forLanguage("en").factorFor("the", listOf("the", "three"))
        repeat(300) { s = s.withCommit("en", "the") }
        repeat(4) { s = s.with("en", "the", "three") }
        val later = s.forLanguage("en").factorFor("the", listOf("the", "three"))
        assertTrue(once < later)
        assertTrue("the keeps nearly all of its score", later > 0.95f)
    }

    @Test
    fun theOppositeMistakeCancels() {
        val s = RetypePenalties.EMPTY.with("en", "bit", "but").with("en", "but", "bit")
        val c = s.forLanguage("en")
        assertEquals(1f, c.factorFor("bit", listOf("bit", "but")), 0f)
        assertEquals(1f, c.factorFor("but", listOf("bit", "but")), 0f)
    }

    @Test
    fun commitsAreCountedOnlyForWordsWithAPair() {
        val s = RetypePenalties.EMPTY.with("en", "god", "good")
        assertSame(s, s.withCommit("en", "hello"))
        assertSame(s, s.with("en", "same", "SAME"))
        assertSame(s, s.with("en", "a|b", "c"))
        assertEquals(2, s.withCommit("en", "god").seen("en", "god"))
    }

    @Test
    fun roundTripsThroughThePreference() {
        var s = RetypePenalties.EMPTY.with("en", "god", "good").with("en", "god", "good").with("es", "si", "su")
        s = s.withCommit("en", "god")
        val back = RetypePenalties.parse(s.serialize())
        assertEquals(s.serialize(), back.serialize())
        assertEquals(2, back.count("en", "god", "good"))
        assertEquals(2, back.seen("en", "god"))
        assertEquals(1, back.count("es", "si", "su"))
        assertEquals(0, RetypePenalties.parse("junk,en|x,en|a|b|zero,|a|b|1").size)
        assertEquals(0, RetypePenalties.parse(null).size)
    }

    @Test
    fun forgettingAWordDropsItsPairsBothWays() {
        val s = RetypePenalties.EMPTY.with("en", "god", "good").with("en", "food", "good").with("en", "bit", "but")
        val f = s.without("en", "good")
        assertEquals(1, f.size)
        assertEquals(0, f.seen("en", "god"))
        assertEquals(1, f.count("en", "bit", "but"))
    }

    @Test
    fun theStoreStaysSmall() {
        var s = RetypePenalties.EMPTY
        repeat(3) { s = s.with("en", "god", "good") }
        for (i in 0 until RetypePenalties.MAX_ROWS + 50) s = s.with("en", "w$i", "x$i")
        assertTrue(s.size <= RetypePenalties.MAX_ROWS)
        assertEquals("the most counted pair survives", 3, s.count("en", "god", "good"))
    }

    @Test
    fun aRetypeIsACloseWordNotALongerOne() {
        assertTrue(RetypePenalties.closeRetype("go", "to"))
        assertTrue(RetypePenalties.closeRetype("gels", "Tells"))
        assertTrue(!RetypePenalties.closeRetype("ca", "can"))
        assertTrue(!RetypePenalties.closeRetype("the", "the"))
        assertTrue(!RetypePenalties.closeRetype("j", "the"))
        assertTrue(!RetypePenalties.closeRetype("morning", "the"))
    }

    @Test
    fun theDecoderScoresThePenalisedWordDownOnlyWhenSwitchedOn() {
        val dir = Paths.get("src/main/assets/dictionaries")
        assumeTrue(Files.exists(dir.resolve("en_wordlist.txt")))
        val d = Files.newBufferedReader(dir.resolve("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(dir.resolve("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        val g = TestData.qwertyGeometry()
        val p = WordPredictor(d.trie, b, g, d.forms)
        val tokens = listOf(TestData.sloppySwipe("tones", g, 1000, 500, 0.3f))
        val plain = p.decode(tokens, emptyList())
        assumeTrue(plain.size >= 2)
        val lead = plain[0].word.lowercase()
        val second = plain[1].word.lowercase()
        var s = RetypePenalties.EMPTY
        repeat(3) { s = s.with("en", lead, second) }
        p.retypeCounts = s.forLanguage("en")
        assertEquals("off: no change", plain.map { it.word }, p.decode(tokens, emptyList()).map { it.word })
        RetypePenalties.enabled = true
        val out = p.decode(tokens, emptyList())
        val y = out.first { it.word.lowercase() == lead }
        val f = s.forLanguage("en").factorFor(lead, listOf(lead, second))
        assertEquals(plain[0].score * f, y.score, plain[0].score * 1e-4f)
        assertTrue(y.terms[ScoreTerms.Term.RETYPE] < 0f)
        assertEquals(0f, out.first { it.word.lowercase() == second }.terms[ScoreTerms.Term.RETYPE], 0f)
        assertEquals(out.map { it.score }.sortedDescending(), out.map { it.score })
    }
}
