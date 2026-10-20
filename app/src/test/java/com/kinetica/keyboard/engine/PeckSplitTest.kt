package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A pecked word followed by a swipe with no space between (owner report, 2026-10-19: `to`, `it`,
 * `of` and `hi` get no automatic space, and swiping on should end them). Replayed on ten batches,
 * 371 of 495 such pairs split right; 4 of 597 real tap-then-swipe words were split by mistake.
 */
class PeckSplitTest {

    private val g = TestData.qwertyGeometry()

    private fun asset(name: String) = listOf(Paths.get("src/main/assets/dictionaries/$name"), Paths.get("app/src/main/assets/dictionaries/$name"))
        .firstOrNull { Files.exists(it) }.also { assumeTrue("$name not found", it != null) }!!

    private val english: WordPredictor by lazy {
        val d = Files.newBufferedReader(asset("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(asset("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        WordPredictor(d.trie, b, g, d.forms, language = "en")
    }

    private val oneLetter = { c: Char -> c == 'a' || c == 'i' }

    private fun split(vararg tokens: com.kinetica.keyboard.engine.models.InputToken): PeckSplit.Split? {
        val list = tokens.toList()
        return PeckSplit.split(english, list, listOf("i", "want"), english.decode(list, listOf("i", "want")), oneLetter)
    }

    @Test
    fun theHeadIsTheTapsBeforeASwipeThatStartsAfterThem() {
        val t = TestData.tap('t', g, 0)
        val o = TestData.tap('o', g, 150, StreamId.RIGHT)
        assertEquals(2, PeckSplit.tapHead(listOf(t, o, TestData.swipe("help", g, 400, 300))))
        assertEquals("a swipe first", 0, PeckSplit.tapHead(listOf(TestData.swipe("help", g, 0, 300), t)))
        assertEquals("taps only", 0, PeckSplit.tapHead(listOf(t, o)))
        assertEquals("the swipe began before the last tap lifted", 0, PeckSplit.tapHead(listOf(t, o, TestData.swipe("help", g, 180, 300))))
    }

    @Test
    fun aPeckedWordThenASwipedWordSplits() {
        val s = split(TestData.tap('t', g, 0), TestData.tap('o', g, 150, StreamId.RIGHT), TestData.swipe("help", g, 450, 300))
        assertNotNull(s)
        assertEquals(2, s!!.head)
        assertEquals("to", s.tap.first { it.source != WordCandidate.Source.COMPLETION }.word)
        assertEquals("help", s.rest.first().word)
    }

    @Test
    fun aWordStartedWithTapsAndFinishedWithASwipeStaysOneWord() {
        // m, e tapped, then r-g-e swiped: `merge`, which batch 10 shows typed this way.
        assertNull(split(TestData.tap('m', g, 0, StreamId.RIGHT), TestData.tap('e', g, 150), TestData.swipe("erge", g, 450, 300, StreamId.LEFT)))
    }

    @Test
    fun aTappedAThenASwipedTailStaysOneWord() {
        // Batch 11: `a` tapped and `llow` swiped read as `a low` three times out of three. The
        // tapped word's score is capped, so a frequent `a` no longer outweighs `allow`. Real
        // words begun with `a` tapped run on into their swipe within about 100 ms.
        assertNull(split(TestData.tap('a', g, 0), TestData.swipe("llow", g, 120, 300)))
    }

    @Test
    fun aStrokeMarkedForTheApostropheNeverSplits() {
        // `I've`: i struck to the apostrophe key is a marked tap, then v-e swiped.
        val i = TestData.tap('i', g, 0, StreamId.RIGHT).copy(apostrophe = true)
        assertNull(split(i, TestData.swipe("be", g, 300, 300)))
    }

    @Test
    fun aLoneLetterThatIsNoWordNeverSplits() {
        assertNull(split(TestData.tap('p', g, 0), TestData.swipe("help", g, 300, 300)))
    }

    @Test
    fun theComposerHoldsTheSplitUntilTheWordEnds() {
        val direct = Executor { it.run() }
        var last: List<WordCandidate> = emptyList()
        val c = WordComposer(english, direct, direct, object : WordComposer.Callbacks {
            override fun onCandidates(candidates: List<WordCandidate>, tentative: WordCandidate?, literal: String, generation: Int) {
                last = candidates
            }
        })
        c.oneLetterWord = oneLetter
        c.peckSplit = true
        c.onToken(TestData.tap('t', g, 0))
        c.onToken(TestData.tap('o', g, 150, StreamId.RIGHT))
        c.onToken(TestData.swipe("help", g, 450, 300))
        val s = c.splitHead()
        assertNotNull(s)
        assertEquals("to", c.literal())
        c.commitWord("to")
        c.rejoinTail(s!!)
        assertEquals("help", last.first().word)
        assertEquals(1, c.tokenCount)
        assertEquals(listOf("to"), c.contextSnapshot())
    }

    @Test
    fun offTheComposerNeverSplits() {
        val direct = Executor { it.run() }
        val c = WordComposer(english, direct, direct, object : WordComposer.Callbacks {
            override fun onCandidates(candidates: List<WordCandidate>, tentative: WordCandidate?, literal: String, generation: Int) = Unit
        })
        c.onToken(TestData.tap('t', g, 0))
        c.onToken(TestData.tap('o', g, 150, StreamId.RIGHT))
        c.onToken(TestData.swipe("help", g, 450, 300))
        assertNull(c.splitHead())
    }

    @Test
    fun aSwipeThatFollowsTheTapsAtOnceNeedsAClearerCase() {
        assertEquals(KineticaConstants.PECK_SPLIT_QUICK_RATIO, PeckSplit.ratioFor(40))
        assertEquals(KineticaConstants.PECK_SPLIT_RATIO, PeckSplit.ratioFor(KineticaConstants.PECK_SPLIT_QUICK_GAP_MS))
    }

    @Test
    fun aReadingThatLostIsStillKeptForTheBackspace() {
        val t = listOf(TestData.tap('a', g, 0), TestData.swipe("llow", g, 120, 300))
        val r = PeckSplit.read(english, t, listOf("i", "want"), english.decode(t, listOf("i", "want")), oneLetter)
        assertNotNull(r)
        assertEquals(false, r!!.chosen)
        assertEquals("a", r.tapWord)
    }
}
