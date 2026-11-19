package com.kinetica.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A decode reads the switches its predictor was built with, whatever they say later (#184). */
class DecoderConfigTest {

    @After
    fun reset() = DecoderConfig.set(DecoderConfig.CLASSIC)

    @Test
    fun theOldSwitchNamesReadAndWriteTheGlobalConfig() {
        ScoreModel.mode = ScoreModel.Mode.LOGLINEAR
        ScoreModel.taps = true
        assertTrue(DecoderConfig.global.logLinear)
        assertTrue(DecoderConfig.global.taps)
        assertFalse(DecoderConfig.global.speed)
        NeuralStackSwitches.all(true)
        assertEquals(DecoderConfig.NEW, DecoderConfig.global)
        assertTrue(BoundaryModel.decides)
    }

    @Test
    fun aThreadInsideADecodeSeesItsOwnConfig() {
        DecoderConfig.set(DecoderConfig.CLASSIC)
        val seen = DecoderConfig.using(DecoderConfig.NEW) {
            // A switch flipped mid-decode changes the global, not this decode.
            ScoreModel.mode = ScoreModel.Mode.LEGACY
            Triple(ScoreModel.logLinear, LatticeDecoder.enabled, ShapeModel.speed)
        }
        assertEquals(Triple(true, true, true), seen)
        assertFalse(ScoreModel.logLinear)
        assertFalse(LatticeDecoder.enabled)
    }

    @Test
    fun usingRestoresTheConfigBefore() {
        val inner = DecoderConfig.CLASSIC.copy(taps = true)
        DecoderConfig.using(DecoderConfig.NEW) {
            DecoderConfig.using(inner) { assertEquals(inner, DecoderConfig.current()) }
            assertEquals(DecoderConfig.NEW, DecoderConfig.current())
        }
        assertEquals(DecoderConfig.global, DecoderConfig.current())
    }

    /** NeuralStack lives in the app's ime package; this is its engine half, for a JVM test. */
    private object NeuralStackSwitches {
        fun all(on: Boolean) = DecoderConfig.set(if (on) DecoderConfig.NEW else DecoderConfig.CLASSIC)
    }
}
