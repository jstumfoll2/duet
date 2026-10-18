package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Short tapped words one key off (`going ti` for `going to`, `this s` for `this a`) and numbers-row
 * digits inside a word (`t9` for `to`), against the real English list and pairs.
 */
class ShortSlipsTest {

    private val g = TestData.qwertyGeometry()
    private val english = setOf('a', 'i')

    private fun slip(typed: String, prev: String?, counts: Map<String, Int> = emptyMap()): String? {
        val p = if (counts.isEmpty()) predictor else predictor(counts)
        return ShortSlips.correction(p, g, typed, prev, english)
    }

    @Test
    fun aShortWordOneKeyOffIsCorrectedByTheWordBeforeIt() {
        assertEquals("to", slip("ti", "going"))
        assertEquals("to", slip("ti", "need"))
        assertEquals("the", slip("tbe", "in"))
        assertEquals("of", slip("og", "instead"))
    }

    @Test
    fun aLoneLetterBesideAOneLetterWordBecomesIt() {
        assertEquals("a", slip("s", "this"))
        assertEquals("a", slip("s", "have"))
    }

    @Test
    fun nothingChangesWithoutContext() {
        assertNull(slip("ti", null))
        assertNull(slip("s", null))
        assertNull(slip("ti", ""))
    }

    @Test
    fun commonWordsAndOneLetterWordsAreKept() {
        for ((typed, prev) in listOf("so" to "and", "in" to "going", "on" to "going", "a" to "have", "i" to "and", "it" to "is")) {
            assertNull("$prev $typed", slip(typed, prev))
        }
    }

    @Test
    fun chatShorthandAndLettersWithNoWordBesideThemAreKept() {
        // `u` is chat for `you`, `k` for `ok`; no one-letter word touches `t`.
        assertNull(slip("u", "that"))
        assertNull(slip("k", "the"))
        assertNull(slip("t", "this"))
    }

    @Test
    fun anApostropheSpellingIsNotATypo() {
        assertNull(slip("im", "my"))
    }

    @Test
    fun lettersTheUserCommitsAreKept() {
        assertNull(slip("ti", "going", mapOf("ti" to KineticaConstants.PERSONAL_MERGE_MIN_COUNT)))
    }

    @Test
    fun theNeighboursAreTheKeysThatTouch() {
        assertEquals(setOf('r', 'y'), ShortSlips.neighbours(g, 't').filter { it in "qwertyuiop" }.toSet())
        assertEquals(setOf('q', 'w', 's'), ShortSlips.neighbours(g, 'a').toSet())
        assertEquals(setOf('u', 'o', 'j', 'k'), ShortSlips.neighbours(g, 'i').toSet())
    }

    @Test
    fun aDigitInsideAWordReadsAsTheLetterBelowIt() {
        val below = "1234567890".zip("qwertyuiop").toMap()
        assertEquals("to", DigitSlips.correction(predictor, "t9", below))
        assertEquals("of", DigitSlips.correction(predictor, "9f", below))
        assertEquals("with", DigitSlips.correction(predictor, "w8th", below))
        assertEquals("To", DigitSlips.correction(predictor, "T9", below))
        for (kept in listOf("mp3", "4k", "b2b", "3d", "covid19", "2nd", "1080p", "12", "9")) {
            assertNull(kept, DigitSlips.correction(predictor, kept, below))
        }
    }

    companion object {
        private lateinit var predictor: WordPredictor
        private var trie: Trie? = null
        private var pairs: BigramTable = BigramTable.EMPTY

        private fun assetPath(name: String): Path {
            val direct = Paths.get("src/main/assets/dictionaries/$name")
            if (Files.exists(direct)) return direct
            return Paths.get("app/src/main/assets/dictionaries/$name")
        }

        fun predictor(counts: Map<String, Int>): WordPredictor {
            val t = trie
            assumeTrue("wordlist asset not found", t != null)
            return WordPredictor(t!!, pairs, TestData.qwertyGeometry(), personalCounts = counts)
        }

        @BeforeClass
        @JvmStatic
        fun load() {
            val words = assetPath("en_wordlist.txt")
            val bigrams = assetPath("en_bigrams.txt")
            if (!Files.exists(words) || !Files.exists(bigrams)) return
            val t = Files.newBufferedReader(words).use { DictionaryLoader.loadWordlist(it) }
            trie = t
            pairs = Files.newBufferedReader(bigrams).use { DictionaryLoader.loadBigrams(it, t) }
            predictor = predictor(emptyMap())
        }
    }
}
