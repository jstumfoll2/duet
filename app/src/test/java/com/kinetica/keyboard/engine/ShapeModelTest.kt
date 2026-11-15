package com.kinetica.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeModelTest {

    @After
    fun reset() {
        ShapeModel.speed = false
    }

    private val n = KineticaConstants.RESAMPLE_N

    // Slow over the first half (20 ms a step), fast over the second (2 ms a step).
    private fun slowThenFast() = LongArray(n).also { t ->
        for (i in 1 until n) t[i] = t[i - 1] + if (i <= n / 2) 20 else 2
    }

    @Test
    fun offByDefaultAndThenNoWeights() {
        assertTrue(!ShapeModel.speed)
        assertNull(ShapeModel.weights(slowThenFast()))
    }

    @Test
    fun slowPointsWeighMoreAndTheMeanIsOne() {
        ShapeModel.speed = true
        val w = ShapeModel.weights(slowThenFast())!!
        assertEquals(1f, w.average().toFloat(), 1e-4f)
        assertTrue(w[4] > 1f)
        assertTrue(w[n - 4] < 1f)
        assertTrue(w.max() / w.min() <= ShapeModel.MAX_W * ShapeModel.MAX_W + 1e-3f)
    }

    @Test
    fun aPathWithNoTimingGetsNone() {
        ShapeModel.speed = true
        assertNull(ShapeModel.weights(LongArray(n)))
    }

    @Test
    fun evenSpeedLeavesTheDtwAsItWas() {
        ShapeModel.speed = true
        val w = ShapeModel.weights(LongArray(n) { it * 10L })!!
        val dtw = DtwMatcher()
        val a = FloatArray(2 * n) { if (it % 2 == 0) it / 2 * 0.1f else 0f }
        val b = FloatArray(2 * n) { if (it % 2 == 0) it / 2 * 0.1f else 0.3f }
        assertEquals(
            dtw.distanceAccum(a, b, Float.POSITIVE_INFINITY),
            dtw.distanceAccum(a, b, Float.POSITIVE_INFINITY, w),
            1e-3f,
        )
    }
}
