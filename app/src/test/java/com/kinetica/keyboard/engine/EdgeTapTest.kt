package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A tap near the edge it shares with a neighbour also reads as that neighbour, even when the
 * letters as tapped spell a word and the heap is full. From batch 9: a left thumb low on t
 * spelled "go" where "to" was meant, and "to" was never scored.
 */
class EdgeTapTest {

    private val g = TestData.qwertyGeometry()

    private fun asset(name: String) = listOf(Paths.get("src/main/assets/dictionaries/$name"), Paths.get("app/src/main/assets/dictionaries/$name"))
        .firstOrNull { Files.exists(it) }.also { assumeTrue("$name not found", it != null) }!!

    private fun lead(edgeGap: Float, vararg taps: TapToken): String? {
        val d = Files.newBufferedReader(asset("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(asset("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        val w = WordPredictor(d.trie, b, g, d.forms, language = "en", edgeTapGapKw = edgeGap)
        return w.decode(taps.toList(), listOf("need")).firstOrNull()?.word
    }

    private fun tap(ch: Char, dx: Float, dy: Float, t: Long, s: StreamId): TapToken {
        val c = ch - 'a'
        return TapToken(s, c, g.centerX(c) + dx, g.centerY(c) + dy, false, t, t + 60)
    }

    // On g, 0.6 kw up and a quarter key left: just inside g, nearly as close to t.
    private val lowT = tap('g', -0.25f, -0.6f, 0, StreamId.LEFT)
    private val o = tap('o', 0f, 0f, 150, StreamId.RIGHT)

    @Test
    fun aTapNearTheEdgeReadsAsItsNeighbourInContext() {
        assertEquals("to", lead(KineticaConstants.EDGE_TAP_GAP_KW, lowT, o))
    }

    @Test
    fun offTheLiteralWins() {
        assertNotEquals("to", lead(0f, lowT, o))
    }

    @Test
    fun aCentredTapKeepsItsKeyEvenInContext() {
        assertEquals("go", lead(KineticaConstants.EDGE_TAP_GAP_KW, tap('g', 0f, 0f, 0, StreamId.LEFT), o))
    }
}
