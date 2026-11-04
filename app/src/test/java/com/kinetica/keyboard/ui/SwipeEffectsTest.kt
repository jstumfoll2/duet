package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class SwipeEffectsTest {

    @Test
    fun unknownPrefsKeepTodaysLook() {
        assertEquals(TrailStyle.CLASSIC, TrailStyle.fromPref(null))
        assertEquals(TrailStyle.CLASSIC, TrailStyle.fromPref("lasers"))
        assertEquals(TrailStyle.COMET, TrailStyle.fromPref("comet"))
        assertEquals(PressEffect.BURST, PressEffect.fromPref(null))
        assertEquals(PressEffect.SPARKS, PressEffect.fromPref("sparks"))
    }

    @Test
    fun theBurstKeepsItsEightyMilliseconds() {
        assertEquals(80L, PressEffect.BURST.lifeMs)
        assertEquals(0L, PressEffect.NONE.lifeMs)
    }

    @Test
    fun progressIsClamped() {
        assertEquals(0f, EffectShapes.progress(-5, 100), 0f)
        assertEquals(0.5f, EffectShapes.progress(50, 100), 1e-6f)
        assertEquals(1f, EffectShapes.progress(500, 100), 0f)
        assertEquals(1f, EffectShapes.progress(0, 0), 0f)
    }

    @Test
    fun aRippleGrowsAndThins() {
        assertTrue(EffectShapes.rippleRadiusDp(1f) > EffectShapes.rippleRadiusDp(0f))
        assertTrue(EffectShapes.rippleStrokeDp(1f) < EffectShapes.rippleStrokeDp(0f))
    }

    @Test
    fun sparksFlyOutEvenlyAndTheSeedTurnsTheStar() {
        val angles = (0 until EffectShapes.PRESS_SPARKS).map { EffectShapes.sparkAngle(it, 0) }
        val step = angles[1] - angles[0]
        for (i in 1 until angles.size) assertEquals(step, angles[i] - angles[i - 1], 1e-9)
        assertNotEquals(EffectShapes.sparkAngle(0, 0), EffectShapes.sparkAngle(0, 90), 1e-6)
        val (dx, dy) = EffectShapes.sparkOffsetDp(angles[0], 34f, 1f)
        assertEquals(34f, hypot(dx, dy), 1e-3f)
        val (sx, sy) = EffectShapes.sparkOffsetDp(angles[0], 34f, 0f)
        assertTrue(abs(sx) < 1e-6f && abs(sy) < 1e-6f)
    }

    @Test
    fun theGlowHaloSitsWiderThanItsCore() {
        for (f in listOf(0f, 0.5f, 1f)) {
            assertTrue(EffectShapes.glowHaloDp(f) > EffectShapes.glowCoreDp(f))
        }
    }
}
