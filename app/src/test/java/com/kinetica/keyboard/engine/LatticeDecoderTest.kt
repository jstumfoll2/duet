package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LatticeDecoderTest {

    private val g = TestData.qwertyGeometry()

    @After
    fun reset() {
        ScoreModel.mode = ScoreModel.Mode.LEGACY
    }

    @Test
    fun offByDefault() {
        assertFalse(LatticeDecoder.enabled)
    }

    @Test
    fun aWordMayEndOnlyWhereEveryFingerIsUp() {
        val a = TestData.swipe("the", g, 1000, 300, StreamId.LEFT)   // 1000..1300
        val b = TestData.swipe("lip", g, 1200, 300, StreamId.RIGHT)  // overlaps a
        val c = TestData.swipe("cat", g, 1700, 300, StreamId.LEFT)   // after both lifted
        assertEquals(listOf(2), LatticeDecoder.boundaries(listOf(a, b, c)))
        assertEquals(c.tStart - b.tEnd, LatticeDecoder.gapBefore(listOf(a, b, c), 2))
        assertTrue(LatticeDecoder.boundaries(listOf(a, b)).isEmpty())
    }

    @Test
    fun onlyTheLongestLiftsAreTried() {
        var t = 1000L
        val taps = "abcdefgh".map { ch -> TestData.tap(ch, g, t).also { t = it.tEnd + (if (ch == 'c' || ch == 'f') 400 else 50) } }
        val cuts = LatticeDecoder.boundaries(taps)
        assertEquals(LatticeDecoder.MAX_BOUNDARIES, cuts.size)
        assertTrue(3 in cuts && 6 in cuts)
        assertEquals(cuts.sorted(), cuts)
    }

    @Test
    fun aLongerLiftMakesABreakLikelier() {
        val a = TestData.swipe("the", g, 1000, 300)
        val soon = TestData.swipe("cat", g, a.tEnd + 30, 300)
        val late = TestData.swipe("cat", g, a.tEnd + 600, 300)
        assertTrue(LatticeDecoder.breakScore(listOf(a, late), 1) > LatticeDecoder.breakScore(listOf(a, soon), 1))
    }

    private fun predictor(): WordPredictor? {
        val dir = Paths.get("src/main/assets/dictionaries")
        if (!Files.exists(dir.resolve("en_wordlist.txt"))) return null
        val d = Files.newBufferedReader(dir.resolve("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(dir.resolve("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        return WordPredictor(d.trie, b, g, d.forms)
    }

    private val oneLetter = { ch: Char -> ch == 'a' || ch == 'i' }

    @Test
    fun twoSwipedWordsWithAPauseBetweenReadAsTwoWords() {
        val p = predictor()
        assumeTrue(p != null)
        ScoreModel.mode = ScoreModel.Mode.LOGLINEAR
        val first = TestData.swipe("changes", g, 1000, 700)
        val second = TestData.swipe("the", g, first.tEnd + 500, 300)
        val r = LatticeDecoder.decode(p!!, listOf(first, second), emptyList(), null, oneLetter)
        assertTrue(r != null && r.split)
        // The words are the ordinary decoder's; the test is that there are two, cut at the lift.
        val words = r!!.best.words.map { it.lowercase() }
        assertEquals(2, words.size)
        assertTrue(words[0].startsWith("chang"))
        assertEquals("the", words[1])
        assertEquals(listOf(0, 1), r.best.arcs.map { it.from })
    }

    @Test
    fun aSecondStrokeStraightAfterStaysOneWord() {
        val p = predictor()
        assumeTrue(p != null)
        ScoreModel.mode = ScoreModel.Mode.LOGLINEAR
        // `thank` drawn as two strokes with almost no lift: `t` is no word, so the only path is one word.
        val first = TestData.tap('t', g, 1000)
        val second = TestData.swipe("hank", g, first.tEnd + 20, 400)
        val r = LatticeDecoder.decode(p!!, listOf(first, second), emptyList(), null, oneLetter)
        assertTrue(r == null || !r.split)
    }

    @Test
    fun aBufferWithNoLiftHasNothingToDecide() {
        val p = predictor()
        assumeTrue(p != null)
        ScoreModel.mode = ScoreModel.Mode.LOGLINEAR
        assertNull(LatticeDecoder.decode(p!!, listOf(TestData.swipe("hello", g, 1000, 500)), emptyList(), null, oneLetter))
    }
}
