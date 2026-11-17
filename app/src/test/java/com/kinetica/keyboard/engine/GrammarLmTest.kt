package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The grammar pass's confusion sets on the shipped English tables ([GrammarCheck.Lm], #108), and
 * the next-word bar on the web pairs. Made-up sentences only.
 */
class GrammarLmTest {

    private val none = GrammarCheck.Pairs { _, _ -> 0 }

    private fun fix(before: String?, prev: String, next: String): String? =
        GrammarCheck.fixPrevious(before, prev, next, none, p.grammarLm())

    @Test
    fun fixesTheClearCases() {
        assertEquals("you're", fix("i", "your", "going"))
        assertEquals("it's", fix("but", "its", "a"))
        assertEquals("it's", fix(null, "its", "like"))
        assertEquals("its", fix("on", "it's", "own"))
    }

    @Test
    fun leavesWordsThatRead() {
        assertNull(fix("i", "threw", "a"))
        assertNull(fix("she", "said", "your"))
        assertNull(fix("on", "its", "own"))
        assertNull(fix("i", "think", "their"))
        // A word typed twice is no confusion.
        assertNull(fix("off", "off", "the"))
    }

    @Test
    fun neverRewritesAWordTheDictionaryCannotSpell() {
        val lm = object : GrammarCheck.Lm {
            override fun logp(word: String, prev: String?) = if (word == "ill") -1f else -20f
            override fun isWord(word: String) = word != "i'll"
        }
        assertNull(GrammarCheck.fixPrevious("so", "i'll", "be", none, lm))
        assertNull(GrammarCheck.fixPrevious("so", "ill", "be", none, lm))
    }

    @Test
    fun nextWordsReadTheWebPairs() {
        val was = KineticaConstants.NEXT_WORD_WIDE
        try {
            KineticaConstants.NEXT_WORD_WIDE = true
            val words = p.nextWords("kind", 3, personal = false).map { it.word }
            assertEquals("of", words.first())
        } finally {
            KineticaConstants.NEXT_WORD_WIDE = was
        }
    }

    @Test
    fun peckedContractionsDecodeWithTheMark() {
        // A tapped apostrophe joins the pecked word and marks it (#164): the taps' letters decode
        // to the contraction, even where the list keeps it out (i'd, we're).
        val g = TestData.qwertyGeometry()
        val want = listOf("don't", "can't", "i'm", "i'd", "i'll", "we're", "it's", "didn't", "you're", "isn't")
        val missed = want.filter { c ->
            val taps = c.filter { it != '\'' }.mapIndexed { i, ch -> TestData.tap(ch, g, i * 150L) }
            p.decode(taps, listOf("so"), apostrophe = true).firstOrNull()?.word?.lowercase() != c
        }
        assertEquals(emptyList<String>(), missed)
        assertEquals(true, ApostropheJoin.joins("don", "en") { p.isLivePrefix(it) })
        assertEquals(true, ApostropheJoin.joins("I", "en") { p.isLivePrefix(it) })
        assertEquals(false, ApostropheJoin.joins("dogs", "en") { p.isLivePrefix(it) })
    }

    companion object {
        private lateinit var p: WordPredictor

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir: Path? = listOf("src/main/assets/dictionaries", "app/src/main/assets/dictionaries")
                .map { Paths.get(it) }.firstOrNull { Files.exists(it.resolve("en_pairs.bin")) }
            assumeTrue("assets not found", dir != null)
            val d = Files.newBufferedReader(dir!!.resolve("en_wordlist.txt")).use { DictionaryLoader.load(it) }
            val b = Files.newBufferedReader(dir.resolve("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
                .let { t -> Files.newInputStream(dir.resolve("en_pairs.bin")).use { t.withContext(PairFile.read(it, d.trie)) } }
            p = WordPredictor(d.trie, b, TestData.qwertyGeometry(), d.forms, language = "en")
        }
    }
}
