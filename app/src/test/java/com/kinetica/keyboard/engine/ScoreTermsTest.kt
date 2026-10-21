package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.ScoreTerms
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The score ledger (#66) closes: on real decodes every listed candidate's terms sum to
 * ln(score), so a replay can name the factor a word lost on without re-deriving it.
 */
class ScoreTermsTest {

    private val g = TestData.qwertyGeometry()

    private fun decode(tokens: List<InputToken>, ctx: List<String>, counts: Map<String, Int> = emptyMap()): List<WordCandidate> {
        val (dict, bigrams) = EN
        return WordPredictor(dict.trie, bigrams, g, dict.forms, counts).decode(tokens, ctx)
    }

    private fun assertCloses(list: List<WordCandidate>, label: String) {
        assertTrue("$label: empty decode", list.isNotEmpty())
        for (c in list) {
            assertNotEquals("$label: ${c.word} has no ledger", ScoreTerms.NONE, c.terms)
            assertEquals("$label: ${c.word} ${c.terms}", ln(c.score), c.terms.total, 1e-3f)
        }
    }

    @Test
    fun swipeLedgerClosesWithContextAndPersonalCounts() {
        val tokens = listOf<InputToken>(TestData.swipe("hgfrer", g, 0, 600L, StreamId.RIGHT))
        assertCloses(decode(tokens, listOf("to", "see"), mapOf("here" to 4, "her" to 2)), "here/her")
    }

    @Test
    fun tapLedgerCloses() {
        val tokens = "tje".mapIndexed { i, ch -> TestData.tap(ch, g, 150L * i) }
        assertCloses(decode(tokens, listOf("i", "saw")), "tje")
    }

    @Test
    fun ledgerArithmetic() {
        val t = ScoreTerms.of(freq = 0.5f, geo = 0.25f, bigram = 2f)
            .times(ScoreTerms.Term.HELD_DOUBLE, 2f)
            .with(ScoreTerms.Term.FREQ, 1f)
        assertEquals(0f, t[ScoreTerms.Term.FREQ], 0f)
        assertEquals(2f, t.factor(ScoreTerms.Term.HELD_DOUBLE), 1e-6f)
        assertEquals(ln(0.25f * 2f * 2f), t.total, 1e-6f)
        assertTrue(abs(ScoreTerms.NONE.total) == 0f)
        assertEquals(t, t.plusLog(ScoreTerms.Term.CTC, 0f))
    }

    private companion object {
        private fun asset(name: String): Path {
            val direct = Paths.get("src/main/assets/dictionaries/$name")
            return if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/dictionaries/$name")
        }

        val EN by lazy {
            val w = asset("en_wordlist.txt")
            val b = asset("en_bigrams.txt")
            assumeTrue("en assets not found", Files.exists(w) && Files.exists(b))
            val dict = Files.newBufferedReader(w).use { DictionaryLoader.load(it) }
            dict to Files.newBufferedReader(b).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        }
    }
}
