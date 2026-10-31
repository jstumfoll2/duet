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
 * A tap's [TapToken.hints] (keys the user's touch spread says it may have meant) are read in
 * the fuzzy passes even past their radius. From batches 14-15: in the split layout a right
 * thumb low on `h` landed on `b`, 1.2 kw from h's centre, and "the" came out "ten".
 */
class HintedTapTest {

    private val g = TestData.qwertyGeometry()

    private fun asset(name: String) = listOf(Paths.get("src/main/assets/dictionaries/$name"), Paths.get("app/src/main/assets/dictionaries/$name"))
        .firstOrNull { Files.exists(it) }.also { assumeTrue("$name not found", it != null) }!!

    private fun lead(vararg taps: TapToken): String? {
        val d = Files.newBufferedReader(asset("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(asset("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        val w = WordPredictor(d.trie, b, g, d.forms, language = "en")
        return w.decode(taps.toList(), listOf("for")).firstOrNull()?.word
    }

    private fun tap(ch: Char, t: Long, s: StreamId, hints: Long = 0L): TapToken {
        val c = ch - 'a'
        return TapToken(s, c, g.centerX(c), g.centerY(c), false, t, t + 60, hints = hints)
    }

    private val t = tap('t', 0, StreamId.LEFT)
    private val e = tap('e', 300, StreamId.LEFT)

    @Test
    fun aHintedKeyIsReadPastTheFuzzyRadius() {
        val h = 'h' - 'a'
        assertEquals("the", lead(t, tap('b', 150, StreamId.RIGHT, hints = 1L shl h), e))
    }

    @Test
    fun withoutTheHintTheFarKeyIsNeverRead() {
        assertNotEquals("the", lead(t, tap('b', 150, StreamId.RIGHT), e))
    }
}
