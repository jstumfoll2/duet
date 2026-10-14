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
    fun aWordTypedWithASwipeOrAMissTeachesNothing() {
        val m = TouchModel()
        val wrongLength = taps("th", 0f, 0f, Random(0), 0f)
        assertFalse(m.learn(Alphabet.encode("the")!!, wrongLength, g))
        val farOff = taps("the", 2f, 0f, Random(0), 0f)
        assertFalse(m.learn(Alphabet.encode("the")!!, farOff, g))
        assertEquals(0, m.learned)
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
        copy.restore("garbage")
        assertEquals(0, copy.learned)
    }
}
