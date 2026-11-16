package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoFeaturesTest {

    private val g = TestData.qwertyGeometry()
    private val F = DuoFeatures.FRAMES
    private val D = DuoFeatures.IN_DIM
    private val P = DuoFeatures.PER_THUMB

    private fun stroke(thumb: Int, vararg xyt: Number): DuoFeatures.Stroke {
        val n = xyt.size / 3
        return DuoFeatures.Stroke(
            thumb,
            FloatArray(n) { xyt[3 * it].toFloat() },
            FloatArray(n) { xyt[3 * it + 1].toFloat() },
            LongArray(n) { xyt[3 * it + 2].toLong() },
        )
    }

    private fun down(x: FloatArray, f: Int, thumb: Int) = x[f * D + thumb * P + Alphabet.LETTERS + 2] == 1f

    @Test fun eachThumbFillsItsOwnHalfForTheTimeItIsDown() {
        // Left thumb down for the whole second, right thumb for the middle of it.
        val a = Alphabet.codeOf('a'); val l = Alphabet.codeOf('l')
        val x = DuoFeatures.of(
            listOf(
                stroke(0, g.centerX(a), g.centerY(a), 0, g.centerX(a), g.centerY(a), 1000),
                stroke(1, g.centerX(l), g.centerY(l), 400, g.centerX(l), g.centerY(l), 600),
            ),
            g,
        )
        assertTrue((0 until F).all { down(x, it, 0) })
        assertEquals(1f, x[10 * D + a], 1e-5f)
        assertTrue(!down(x, 0, 1) && !down(x, F - 1, 1) && down(x, F / 2, 1))
        assertEquals(1f, x[(F / 2) * D + P + l], 1e-5f)
        // Up is all zeros, the proximities too.
        assertTrue((0 until P).all { x[P + it] == 0f })
    }

    @Test fun aTapShorterThanAFrameIsStillSeen() {
        val q = Alphabet.codeOf('q'); val p = Alphabet.codeOf('p')
        val x = DuoFeatures.of(
            listOf(
                stroke(0, g.centerX(q), g.centerY(q), 0, g.centerX(q), g.centerY(q), 60),
                stroke(1, g.centerX(p), g.centerY(p), 1500, g.centerX(p), g.centerY(p), 1501),
                stroke(0, g.centerX(q), g.centerY(q), 3000, g.centerX(q), g.centerY(q), 3060),
            ),
            g,
        )
        assertEquals(1, (0 until F).count { down(x, it, 1) })
    }

    @Test fun theStepIsTheMoveSinceTheFrameBefore() {
        // One thumb moving right at a steady 6.3 kw over the buffer: 0.1 kw a frame.
        val x = DuoFeatures.of(listOf(stroke(0, 1f, 1f, 0, 7.3f, 1f, 630)), g)
        assertEquals(0f, x[Alphabet.LETTERS], 1e-5f)
        for (f in 1 until F) {
            assertEquals(0.1f, x[f * D + Alphabet.LETTERS], 1e-3f)
            assertEquals(0f, x[f * D + Alphabet.LETTERS + 1], 1e-5f)
        }
    }
}
