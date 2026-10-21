package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A word an automatic space ended, reopened when a letter followed, read as that word and what
 * came after it once the next word is there ([PeckSplit.readRejoin]). Owner report 2026-10-21:
 * `to` then `a` became `toa`; in that day's trace 15 of 26 reopened words swallowed the next
 * word (`to` and a swiped `this` read as `tianjin`) and were backspaced.
 */
class RejoinSplitTest {

    private val g = TestData.qwertyGeometry()

    private fun asset(name: String) = listOf(Paths.get("src/main/assets/dictionaries/$name"), Paths.get("app/src/main/assets/dictionaries/$name"))
        .firstOrNull { Files.exists(it) }.also { assumeTrue("$name not found", it != null) }!!

    private val english: WordPredictor by lazy {
        val d = Files.newBufferedReader(asset("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(asset("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        WordPredictor(d.trie, b, g, d.forms, language = "en")
    }

    private val oneLetter = { c: Char -> c == 'a' || c == 'i' }
    private val context = listOf("i", "want")

    // The reopened word as the IME seeds it: key centres, 1 ms apart, before what follows.
    private fun seeded(word: String, before: Long): List<InputToken> =
        word.mapIndexed { i, c -> TestData.tap(c, g, before - 10 + i) }

    private fun taps(letters: String, start: Long): List<InputToken> =
        letters.mapIndexed { i, c -> TestData.tap(c, g, start + 180L * i, if (i % 2 == 0) StreamId.RIGHT else StreamId.LEFT) }

    private fun read(head: String, rest: List<InputToken>): PeckSplit.Split? {
        val tokens = seeded(head, rest.minOf { it.tStart }) + rest
        return PeckSplit.readRejoin(english, tokens, head.length, context, english.decode(tokens.sortedBy { it.tStart }, context), oneLetter)
    }

    @Test
    fun aSwipedWordAfterTheReopenedOneSplits() {
        val s = read("to", listOf(TestData.swipe("help", g, 1_000, 300)))!!
        assertTrue(s.chosen)
        assertEquals("help", s.restWord)
    }

    @Test
    fun aTappedWordAfterTheReopenedOneSplits() {
        val s = read("to", taps("say", 1_000))!!
        assertTrue(s.chosen)
        assertEquals("say", s.restWord)
    }

    @Test
    fun theRestOfALongerWordJoins() {
        assertFalse(read("the", taps("y", 1_000))!!.chosen)
        assertFalse(read("due", taps("t", 1_000))!!.chosen)
    }

    @Test
    fun aRestThatIsNoWordJoins() {
        // `ad` then `d`: `d` stands alone in no language.
        assertFalse(read("ad", taps("d", 1_000))!!.chosen)
    }

    @Test
    fun nothingAfterTheHeadReadsNothing() {
        val head = seeded("to", 1_000)
        assertNull(PeckSplit.readRejoin(english, head, 2, context, english.decode(head, context), oneLetter))
    }
}
