package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A word reopened from the editor keeps the real tap times it was typed with (#64). */
class SwipeTraceRecorderTest {

    private val g = TestData.qwertyGeometry()
    private val lines = ArrayList<String>()
    private val recorder = SwipeTraceRecorder({ SwipeTrace.Config("en") }, { lines.add(it) }).also {
        it.onGeometry(g, 20f)
    }

    private fun tap(pointer: Int, c: Char, down: Long, up: Long): TapToken {
        val code = Alphabet.codeOf(c)
        val x = g.centerX(code) * g.keyWidthPx
        val y = g.centerY(code) * g.keyWidthPx
        val t = TapToken(StreamId.LEFT, code, g.centerX(code), g.centerY(code), false, down, up)
        recorder.onDown(pointer, StreamId.LEFT, x, y, down)
        recorder.onUp(pointer, x, y, up, t)
        return t
    }

    private fun seeded(c: Char, t: Long): TapToken {
        val code = Alphabet.codeOf(c)
        return TapToken(StreamId.LEFT, code, g.centerX(code), g.centerY(code), false, t, t + 1)
    }

    private fun end(tokens: List<InputToken>, word: String?) =
        recorder.onBufferEnd(tokens, emptyList(), emptyList(), 0, word, false)

    private fun literals(line: String) = SwipeTrace.decode(line).tokens.filterIsInstance<SwipeTrace.Literal>()

    @Test
    fun aReopenedTappedWordCarriesItsRealTapTimes() {
        end(listOf(tap(1, 's', 1000, 1060), tap(2, 'o', 1200, 1250)), "so")
        // The autospace is taken back by `i`: `so` comes back seeded, 1 ms apart.
        end(listOf(seeded('s', 1297), seeded('o', 1298), tap(3, 'i', 1300, 1340)), "soi")
        val lits = literals(lines.last())
        assertEquals(listOf(1000L to 1060L, 1200L to 1250L), lits.map { it.real })
        // The made-up times stay what the decode saw, so the line replays as before.
        assertEquals(1297L, lits[0].token.tStart)
    }

    @Test
    fun otherSeededWordsGetNoTimes() {
        end(listOf(tap(1, 's', 1000, 1060), tap(2, 'o', 1200, 1250)), "so")
        end(listOf(seeded('t', 1297), seeded('o', 1298)), "to")
        assertNull(literals(lines.last()).first().real)
    }

    @Test
    fun onlyWordLinesCountAsWordsAndADiscardTakesOneBack() {
        end(listOf(tap(1, 's', 1000, 1060), tap(2, 'o', 1200, 1250)), "so")
        assertEquals(1, SwipeTrace.wordDelta(lines.last()))
        assertEquals(-1, SwipeTrace.wordDelta(SwipeTrace.encodeDiscard()))
        assertEquals(0, SwipeTrace.wordDelta(SwipeTrace.encodeKey("space", 1300)))
        assertEquals(0, SwipeTrace.wordDelta(SwipeTrace.encodeCorrection("so", "do")))
    }
}
