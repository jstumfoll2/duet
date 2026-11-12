package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AimModelTest {

    private val g = TestData.qwertyGeometry()
    private val a = Alphabet.codeOf('a')

    /** One tap per letter of [word], each [dx], [dy] kw off its key's centre. */
    private fun taps(word: String, dx: Float, dy: Float): List<InputToken> =
        word.mapIndexed { i, ch ->
            val c = Alphabet.codeOf(ch)
            val x = g.centerX(c) + dx
            val y = g.centerY(c) + dy
            TapToken(StreamId.LEFT, g.keyAt(x, y).takeIf { it >= 0 } ?: c, x, y, false, 100L * i, 100L * i + 50)
        }

    private fun taught(dx: Float, dy: Float, times: Int = 200, strength: Float = 1f): AimModel {
        val m = AimModel().also { it.strength = strength }
        repeat(times) { m.learn(Alphabet.encode("was")!!, taps("was", dx, dy), g) }
        return m
    }

    @Test fun `nothing learned leaves the board as drawn`() {
        assertSame(g, AimModel().also { it.strength = 1f }.aimed(g))
    }

    @Test fun `strength zero is off`() {
        assertSame(g, taught(0.3f, 0f, strength = 0f).aimed(g))
    }

    @Test fun `keys move to where the taps land`() {
        val aimed = taught(0.3f, -0.2f).aimed(g)
        assertEquals(g.centerX(a) + 0.3f * 200f / 220f, aimed.centerX(a), 0.01f)
        assertEquals(g.centerY(a) - 0.2f * 200f / 220f, aimed.centerY(a), 0.01f)
        // A key the words never touched stays.
        val q = Alphabet.codeOf('q')
        assertEquals(g.centerX(q), aimed.centerX(q), 0f)
    }

    @Test fun `a few touches barely move a key`() {
        val aimed = taught(0.3f, 0f, times = 2).aimed(g)
        assertTrue(aimed.centerX(a) - g.centerX(a) < 0.05f)
    }

    @Test fun `no key moves farther than the cap`() {
        val aimed = taught(0.9f, 0f, times = 400).aimed(g)
        assertEquals(AimModel.MAX_OFFSET_KW, aimed.centerX(a) - g.centerX(a), 0.001f)
    }

    @Test fun `a touch far from its key teaches nothing`() {
        val m = AimModel().also { it.strength = 1f }
        assertFalse(m.learn(Alphabet.encode("was")!!, taps("was", 1.4f, 0f), g))
        assertSame(g, m.aimed(g))
    }

    @Test fun `a tap is keyed by the aimed key it lands on and keeps its drawn key as a hint`() {
        val aimed = taught(0.4f, 0f).aimed(g)
        // 0.6 kw right of a's centre: on the drawn s, and on the aimed a.
        val x = g.centerX(a) + 0.6f
        val y = g.centerY(a)
        val s = g.keyAt(x, y)
        assertEquals(Alphabet.codeOf('s'), s)
        val out = AimModel.rekey(listOf(TapToken(StreamId.LEFT, s, x, y, false, 0, 50)), aimed).single() as TapToken
        assertEquals(a, out.code)
        assertTrue(out.hints and (1L shl s) != 0L)
    }

    @Test fun `save and restore keep the offsets`() {
        val m = taught(0.3f, -0.2f)
        val r = AimModel().also { it.strength = 1f }
        r.restore(m.save())
        assertEquals(m.offset(a).first, r.offset(a).first, 1e-4f)
        assertEquals(m.offset(a).second, r.offset(a).second, 1e-4f)
        assertEquals(m.learned, r.learned)
    }

    @Test fun `switched off and on again the learned offsets come back`() {
        // The keyboard's switch sets strength 0 and back: nothing is lost in between.
        val m = taught(0.3f, 0f)
        val on = m.aimed(g).centerX(a)
        m.strength = 0f
        assertSame(g, m.aimed(g))
        m.strength = 1f
        assertEquals(on, m.aimed(g).centerX(a), 1e-6f)
    }

    @Test fun `reset forgets every offset`() {
        val m = taught(0.3f, -0.2f)
        m.reset()
        assertEquals(0, m.learned)
        assertSame(g, m.aimed(g))
    }
}
