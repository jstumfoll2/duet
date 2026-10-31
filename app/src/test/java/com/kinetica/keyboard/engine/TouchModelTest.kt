package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TouchModelTest {

    private val g = TestData.qwertyGeometry()
    private val words = listOf("the", "and", "you", "that", "was", "for", "are", "with", "his", "they", "have", "this")

    /** Taps for [word], each landing at its letter's centre plus ([dx], [dy]) and some noise. */
    private fun taps(word: String, dx: Float, dy: Float, rnd: Random, noise: Float): List<InputToken> =
        word.mapIndexed { i, ch ->
            val c = Alphabet.codeOf(ch)
            val x = g.centerX(c) + dx + (rnd.nextFloat() - 0.5f) * noise
            val y = g.centerY(c) + dy + (rnd.nextFloat() - 0.5f) * noise
            val stream = if (x < g.midlinePx / g.keyWidthPx) StreamId.LEFT else StreamId.RIGHT
            val code = g.keyAt(x, y).takeIf { it >= 0 } ?: g.nearestKey(x, y)
            TapToken(stream, code, x, y, false, 100L * i, 100L * i + 50)
        }

    private fun teach(m: TouchModel, n: Int, dx: Float, dy: Float, noise: Float, seed: Int = 1) {
        val rnd = Random(seed)
        repeat(n) {
            val w = words[it % words.size]
            m.learn(Alphabet.encode(w)!!, taps(w, dx, dy, rnd, noise), g)
        }
    }

    @Test
    fun emptyModelLeavesTapsAlone() {
        val m = TouchModel()
        val t = taps("the", 0f, 0f, Random(0), 0f)
        assertTrue(m.apply(t, g) === t)
        assertNull(m.correct(StreamId.LEFT, 1f, 1f, g))
    }

    @Test
    fun learnsAConsistentOffsetAndTurnsItselfOn() {
        val m = TouchModel()
        // Taps land 0.7 kw low: past the key's lower edge, so the raw key is usually the row below.
        teach(m, 400, 0f, 0.7f, 0.3f)
        assertTrue("gain ${m.gain}", m.isActive)
        val (_, oy) = m.offset(StreamId.LEFT, Alphabet.codeOf('e'))
        assertTrue("offset $oy", oy > 0.3f)
        // A tap meant for `e` that landed on `d` comes back to `e`.
        val e = Alphabet.codeOf('e')
        val c = m.correct(StreamId.LEFT, g.centerX(e), g.centerY(e) + 0.7f, g)!!
        assertEquals(e, c.first)
    }

    @Test
    fun noiseWithoutHabitNeverSwitchesOn() {
        val m = TouchModel()
        teach(m, 400, 0f, 0f, 0.8f)
        assertFalse("gain ${m.gain}", m.isActive)
        val t = taps("was", 0f, 0f, Random(3), 0.8f)
        assertTrue(m.apply(t, g) === t)
    }

    @Test
    fun aWordTypedWithASwipeOrAMissNeverTeachesCorrection() {
        val m = TouchModel()
        // Two taps for three letters: each teaches its nearest letter's hint spread, never the offset.
        val wrongLength = taps("th", 0.2f, 0.3f, Random(0), 0f)
        assertTrue(m.learn(Alphabet.encode("the")!!, wrongLength, g))
        for (s in StreamId.values()) for (ch in "the") {
            assertEquals(0f to 0f, m.offset(s, Alphabet.codeOf(ch)))
        }
        val before = m.learned
        val farOff = taps("the", 2f, 0f, Random(0), 0f)
        assertFalse(m.learn(Alphabet.encode("the")!!, farOff, g))
        assertEquals(before, m.learned)
    }

    /** One tap near [ch], landing ([dx], [dy]) off its centre plus noise: a word with swipes in it. */
    private fun teachMixed(m: TouchModel, ch: Char, n: Int, dx: Float, dy: Float, noise: Float) {
        val rnd = Random(5)
        val c = Alphabet.codeOf(ch)
        repeat(n) {
            val x = g.centerX(c) + dx + (rnd.nextFloat() - 0.5f) * noise
            val y = g.centerY(c) + dy + (rnd.nextFloat() - 0.5f) * noise
            val code = g.keyAt(x, y).takeIf { it >= 0 } ?: g.nearestKey(x, y)
            m.learn(Alphabet.encode("the")!!, listOf(TapToken(StreamId.RIGHT, code, x, y, false, 0L, 50L)), g)
        }
    }

    private fun hintsAt(m: TouchModel, ch: Char, dx: Float, dy: Float): Long {
        val c = Alphabet.codeOf(ch)
        val x = g.centerX(c) + dx
        val y = g.centerY(c) + dy
        val code = g.keyAt(x, y).takeIf { it >= 0 } ?: g.nearestKey(x, y)
        return (m.hint(listOf(TapToken(StreamId.RIGHT, code, x, y, false, 0L, 50L)), g).single() as TapToken).hints
    }

    @Test
    fun aKeyMissedTheSameWayHintsItsLongTaps() {
        val m = TouchModel()
        // The developer's right thumb on h: 0.2 kw right and 0.35 low, taught by swiped words.
        teachMixed(m, 'h', 120, 0.2f, 0.35f, 0.8f)
        val h = 1L shl Alphabet.codeOf('h')
        // A tap past h's lower right corner, on another key: still plausibly h.
        assertTrue(hintsAt(m, 'h', 0.55f, 0.8f) and h != 0L)
        // Far beyond the learned spread, or on the opposite side, it is not.
        assertEquals(0L, hintsAt(m, 'h', 0f, -1.4f) and h)
        assertFalse("hints never switch correction on", m.isActive)
    }

    @Test
    fun aKeyHitOnCentreNeverHints() {
        val m = TouchModel()
        teachMixed(m, 'h', 120, 0f, 0f, 0.8f)
        assertEquals(0L, hintsAt(m, 'h', 0.55f, 0.8f))
    }

    @Test
    fun saveAndRestoreKeepTheModel() {
        val m = TouchModel()
        teach(m, 400, 0.1f, 0.7f, 0.3f)
        val copy = TouchModel()
        copy.restore(m.save())
        assertEquals(m.learned, copy.learned)
        assertEquals(m.isActive, copy.isActive)
        val c = Alphabet.codeOf('h')
        assertEquals(m.offset(StreamId.RIGHT, c).second, copy.offset(StreamId.RIGHT, c).second, 1e-4f)
        assertEquals(m.hint(taps("the", 0.4f, 0.8f, Random(9), 0f), g), copy.hint(taps("the", 0.4f, 0.8f, Random(9), 0f), g))
        // A model saved before hints still restores, its hint spread seeded from the offsets.
        val old = TouchModel()
        old.restore("v1;3,0,0,0;1,7,40,8,14")
        assertEquals(3, old.learned)
        assertTrue(hintsAt(old, 'h', 0.55f, 0.8f) and (1L shl Alphabet.codeOf('h')) != 0L)
        copy.restore("garbage")
        assertEquals(0, copy.learned)
    }
}
