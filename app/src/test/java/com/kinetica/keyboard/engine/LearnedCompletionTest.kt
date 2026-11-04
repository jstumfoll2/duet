package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A learned word completes from its first letters past COMPLETION_MAX_EXTRA (upstream Kinetica
 * 2.1.2). Engine-only, so it runs in tools/replay too; CompletionTest imports IME code.
 */
class LearnedCompletionTest {

    private val g = TestData.qwertyGeometry()

    private fun taps(word: String) =
        word.mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }

    /**
     * `fakecompanyname` committed [count] times with its capital settled, beside dictionary words
     * that share its start; [learned] off holds it as a dictionary word nobody typed.
     */
    private fun longWord(count: Int, learned: Boolean = true): WordPredictor {
        val list = "fake\t900\nfakes\t300\nfaked\t200\ncompany\t800\n" +
            if (learned) "" else "fakecompanyname\t50\n"
        val dict = DictionaryLoader.load(
            list.reader().buffered(),
            extraWords = if (learned) listOf("fakecompanyname" to count) else emptyList(),
            cases = mapOf("fakecompanyname" to DictionaryLoader.LearnedCase("FakeCompanyName", 2)),
        )
        val counts = if (learned) mapOf("fakecompanyname" to count) else emptyMap()
        return WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms, personalCounts = counts)
    }

    @Test
    fun aLearnedWordCompletesPastTheCap() {
        // Nine letters past `fakeco`, one more than a completion reaches: offered anyway.
        val result = longWord(count = 2).decode(taps("fakeco"), emptyList())
        assertTrue(
            "no FakeCompanyName in ${result.map { it.word }}",
            result.any { it.word.equals("fakecompanyname", true) && it.source == WordCandidate.Source.COMPLETION },
        )
    }

    @Test
    fun anUnlearnedLongWordStillStopsAtTheCap() {
        val dictionary = longWord(count = 0, learned = false)
        assertTrue(dictionary.decode(taps("fakeco"), emptyList()).none { it.word.equals("fakecompanyname", true) })
        assertTrue(dictionary.decode(taps("fakecom"), emptyList()).any { it.word.equals("fakecompanyname", true) })
        // Typed once is below PERSONAL_MERGE_MIN_COUNT.
        val once = longWord(count = 1)
        assertTrue(once.decode(taps("fakeco"), emptyList()).none { it.word.equals("fakecompanyname", true) })
    }

    @Test
    fun aLearnedCompletionNeverAutocorrects() {
        val p = longWord(count = 5)
        val result = p.decode(taps("fakeco"), emptyList())
        val best = result.first { it.word.equals("fakecompanyname", true) }
        assertEquals(WordCandidate.Source.COMPLETION, best.source)
        assertNull(p.autocorrectTarget("fakeco", listOf(best), 0.5f))
    }
}
