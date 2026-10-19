package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatPlacementTest {

    @Test
    fun theKeyboardNeverFloatsOffTheTopOrBelowTheBottom() {
        // A 2400 px screen keeps 15% free at the top: a 900 px keyboard rises at most 1140 px.
        assertEquals(1140, FloatPlacement.maxLift(2400, 900))
        assertEquals(1140, FloatPlacement.clampLift(5000, 1140))
        assertEquals(0, FloatPlacement.clampLift(-30, 1140))
        // A keyboard taller than the room has none to rise.
        assertEquals(0, FloatPlacement.maxLift(1000, 950))
        assertEquals(0, FloatPlacement.clampLift(200, -5))
    }

    @Test
    fun theWidthStaysWideEnoughForTwoThumbs() {
        assertEquals(0.8f, FloatPlacement.widthFraction(80), 1e-6f)
        assertEquals(FloatPlacement.MIN_WIDTH, FloatPlacement.widthFraction(10), 1e-6f)
        assertEquals(1f, FloatPlacement.widthFraction(150), 1e-6f)
    }
}
