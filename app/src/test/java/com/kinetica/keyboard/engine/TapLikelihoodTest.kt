package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Continuous tap likelihood (ScoreModel.taps, issue #127): every pass reads a tap as each key
 * near it at the Gaussian charge, not only the fuzzy passes. Same batch 9 case as EdgeTapTest,
 * with the edge pass turned off so only the likelihood can reach "to".
 */
class TapLikelihoodTest {

    private val g = TestData.qwertyGeometry()

    @After
    fun reset() {
        ScoreModel.taps = false
    }

    private fun asset(name: String) = listOf(Paths.get("src/main/assets/dictionaries/$name"), Paths.get("app/src/main/assets/dictionaries/$name"))
        .firstOrNull { Files.exists(it) }.also { assumeTrue("$name not found", it != null) }!!

    private fun lead(vararg taps: TapToken): String? {
        val d = Files.newBufferedReader(asset("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(asset("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        val w = WordPredictor(d.trie, b, g, d.forms, language = "en", edgeTapGapKw = 0f)
        return w.decode(taps.toList(), listOf("need")).firstOrNull()?.word
    }

    private fun tap(ch: Char, dx: Float, dy: Float, t: Long, s: StreamId): TapToken {
        val c = ch - 'a'
        return TapToken(s, c, g.centerX(c) + dx, g.centerY(c) + dy, false, t, t + 60)
    }

    private val lowT = tap('g', -0.25f, -0.6f, 0, StreamId.LEFT)
    private val o = tap('o', 0f, 0f, 150, StreamId.RIGHT)

    @Test
    fun offByDefault() {
        assertFalse(ScoreModel.taps)
    }

    @Test
    fun aTapNearTheEdgeReadsAsItsNeighbourInEveryPass() {
        ScoreModel.taps = true
        assertEquals("to", lead(lowT, o))
    }

    @Test
    fun aCentredTapKeepsItsKey() {
        ScoreModel.taps = true
        assertEquals("go", lead(tap('g', 0f, 0f, 0, StreamId.LEFT), o))
    }
}
