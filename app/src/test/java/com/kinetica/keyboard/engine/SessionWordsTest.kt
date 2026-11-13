package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.ScoreTerms
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SessionWordsTest {

    @Test
    fun keepsTheLatestWordsFolded() {
        val s = SessionWords(capacity = 3)
        for (w in listOf("Café", "one", "two", "three")) s.add(w)
        assertEquals(setOf("one", "two", "three"), s.snapshot())
        s.add("cafe")
        assertTrue("cafe" in s.snapshot())
        s.remove("THREE")
        assertFalse("three" in s.snapshot())
        assertEquals(2, s.size)
        s.clear()
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun removeTakesBackOnlyTheLatestCommit() {
        val s = SessionWords()
        s.add("kolby"); s.add("said"); s.add("kolby")
        s.remove("kolby")
        assertTrue("kolby" in s.snapshot())
        s.remove("kolby")
        assertFalse("kolby" in s.snapshot())
    }

    @Test
    fun aRarerRecentWordIsScoredUpAndCommonOnesAreNot() {
        val dir = Paths.get("src/main/assets/dictionaries")
        assumeTrue(Files.exists(dir.resolve("en_wordlist.txt")))
        val d = Files.newBufferedReader(dir.resolve("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(dir.resolve("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        val g = TestData.qwertyGeometry()
        val p = WordPredictor(d.trie, b, g, d.forms)
        val tokens = listOf(TestData.sloppySwipe("tones", g, 1000, 500, 0.3f))
        val plain = p.decode(tokens, emptyList())
        assumeTrue(plain.size >= 2)
        val maxFw = kotlin.math.exp(KineticaConstants.SESSION_MAX_LN_FREQ)
        val rare = plain.first { it.frequencyWeight <= maxFw && it.word.length >= KineticaConstants.SESSION_MIN_LEN }
        val common = plain.firstOrNull { it.frequencyWeight > maxFw }
        p.sessionWords = setOfNotNull(rare.word, common?.word)
        val boosted = p.decode(tokens, emptyList())
        val r = boosted.first { it.word == rare.word }
        assertEquals(ln(KineticaConstants.SESSION_BOOST), r.terms[ScoreTerms.Term.SESSION], 1e-4f)
        assertEquals(rare.score * KineticaConstants.SESSION_BOOST, r.score, 1e-6f)
        common?.let { c -> assertEquals(0f, boosted.first { it.word == c.word }.terms[ScoreTerms.Term.SESSION], 0f) }
        assertEquals(boosted.map { it.score }.sortedDescending(), boosted.map { it.score })
        p.sessionWords = emptySet()
        assertEquals(plain.map { it.word }, p.decode(tokens, emptyList()).map { it.word })
    }
}
