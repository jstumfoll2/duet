package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A stroke that dips to the spacebar between words ([SpaceDip]). The board is TestData's
 * QWERTY, whose letters end at y 4.5 kw, with a spacebar under them from x 2.5 to 7.5.
 */
class SpaceDipTest {

    private val letters = TestData.qwertyGeometry()
    private val board = letters.withSpace(floatArrayOf(2.5f, 4.5f, 7.5f, 6f))

    private fun at(c: Char) = floatArrayOf(letters.centerX(c - 'a'), letters.centerY(c - 'a'))
    private val dip = floatArrayOf(5f, 5.5f)

    private class Got : GestureEngine.Listener {
        val tokens = ArrayList<InputToken>()
        var dips = 0
        override fun onTokenFinalized(token: InputToken) { tokens.add(token) }
        override fun onKeyTransition(streamId: StreamId, code: Int) = Unit
        override fun onAllPointersUp() = Unit
        override fun onSpaceDip(words: List<InputToken>) {
            dips++
            tokens.addAll(words)
        }
    }

    /** One pointer through [stops] (kw), densified, through an engine with the split [on]. */
    private fun draw(g: KeyboardGeometry, on: Boolean, vararg stops: FloatArray): Got {
        val got = Got()
        val e = GestureEngine(got)
        e.spaceDip = on
        e.setGeometry(g, 0.3f * g.keyWidthPx)
        val kw = g.keyWidthPx
        var t = 0L
        assertTrue(e.onPointerDown(0, stops[0][0] * kw, stops[0][1] * kw, t))
        var x = stops[0][0]
        var y = stops[0][1]
        for (i in 1 until stops.size) {
            val a = stops[i - 1]
            val b = stops[i]
            for (k in 1..12) {
                val f = k / 12f
                t += 8
                x = a[0] + f * (b[0] - a[0])
                y = a[1] + f * (b[1] - a[1])
                if (i < stops.size - 1 || k < 12) e.onPointerMove(0, x * kw, y * kw, t)
            }
        }
        e.onPointerUp(0, x * kw, y * kw, t)
        return got
    }

    private fun near(p: FloatArray, x: Float, y: Float) = abs(p[0] - x) < 0.2f && abs(p[1] - y) < 0.2f

    @Test
    fun aDipSplitsTheStrokeAtTheLettersBesideIt() {
        val got = draw(board, true, at('t'), at('h'), at('e'), dip, at('c'), at('a'), at('t'))
        assertEquals(1, got.dips)
        assertEquals(2, got.tokens.size)
        val head = got.tokens[0] as SwipeToken
        val tail = got.tokens[1] as SwipeToken
        // The legs are gone: the first word ends on e and the second starts on c.
        assertTrue(near(at('e'), head.rawPath.last().x, head.rawPath.last().y))
        assertTrue(near(at('c'), tail.rawPath.first().x, tail.rawPath.first().y))
        assertTrue(head.rawPath.all { it.y <= 4.5f } && tail.rawPath.all { it.y <= 4.5f })
        assertTrue(head.tEnd <= tail.tStart)
        // The keys the legs crossed went with them.
        assertEquals('e', 'a' + head.keyContacts.last().code)
        assertEquals('c', 'a' + tail.keyContacts.first().code)
    }

    @Test
    fun offLeavesTheStrokeWhole() {
        val got = draw(board, false, at('t'), at('h'), at('e'), dip, at('c'), at('a'), at('t'))
        assertEquals(0, got.dips)
        assertEquals(1, got.tokens.size)
    }

    @Test
    fun aBoardWithNoSpacebarRectNeverSplits() {
        val got = draw(letters, true, at('t'), at('h'), at('e'), dip, at('c'), at('a'), at('t'))
        assertEquals(0, got.dips)
        assertEquals(1, got.tokens.size)
    }

    @Test
    fun aGrazeBelowTheBottomRowIsNotADip() {
        val shallow = floatArrayOf(5f, 4.5f + SpaceDip.MIN_DEPTH_KW - 0.1f)
        val got = draw(board, true, at('t'), at('h'), at('e'), shallow, at('c'), at('a'), at('t'))
        assertEquals(0, got.dips)
        assertEquals(1, got.tokens.size)
    }

    @Test
    fun aDipBesideTheSpacebarIsNotADip() {
        val got = draw(board, true, at('t'), at('h'), at('e'), floatArrayOf(1f, 5.5f), at('c'), at('a'), at('t'))
        assertEquals(0, got.dips)
    }

    @Test
    fun aLiftOnTheSpacebarIsNotADip() {
        val got = draw(board, true, at('t'), at('h'), at('e'), dip)
        assertEquals(0, got.dips)
        assertEquals(1, got.tokens.size)
    }

    @Test
    fun aLetterThenADipIsATapOnIt() {
        val got = draw(board, true, at('a'), dip, at('c'), at('a'), at('t'))
        assertEquals(1, got.dips)
        val head = got.tokens[0] as TapToken
        assertEquals('a' - 'a', head.code)
        assertTrue(got.tokens[1] is SwipeToken)
    }

    @Test
    fun twoDipsAreThreeWords() {
        val got = draw(
            board, true,
            at('t'), at('h'), at('e'), dip, at('c'), at('a'), at('t'), floatArrayOf(4f, 5.5f), at('s'), at('a'), at('t'),
        )
        assertEquals(1, got.dips)
        assertEquals(3, got.tokens.size)
    }

    @Test
    fun findIgnoresAPathThatStaysOnTheLetters() {
        val got = draw(board, true, at('q'), at('z'), at('m'), at('p'))
        assertEquals(0, got.dips)
        assertNull(SpaceDip.find((got.tokens[0] as SwipeToken).rawPath, floatArrayOf(2.5f, 4.5f, 7.5f, 6f), 4.5f))
    }

    @Test
    fun aListenerThatDoesNotKnowDipsGetsThePiecesAsTokens() {
        val seen = ArrayList<InputToken>()
        val l = object : GestureEngine.Listener {
            override fun onTokenFinalized(token: InputToken) { seen.add(token) }
            override fun onKeyTransition(streamId: StreamId, code: Int) = Unit
            override fun onAllPointersUp() = Unit
        }
        l.onSpaceDip(draw(board, true, at('t'), at('h'), at('e'), dip, at('c'), at('a'), at('t')).tokens)
        assertEquals(2, seen.size)
    }
}
