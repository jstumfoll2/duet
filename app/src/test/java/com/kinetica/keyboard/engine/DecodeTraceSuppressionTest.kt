package com.kinetica.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A private field (password, incognito) leaves no line in the DEV build's decode trace. */
class DecodeTraceSuppressionTest {

    @After
    fun reset() {
        DecodeTrace.sink = null
        DecodeTrace.suppressed = false
    }

    @Test
    fun suppressedDropsLoggedHeldAndWrittenLines() {
        val out = ArrayList<String>()
        DecodeTrace.sink = { out.add(it) }

        DecodeTrace.log { "before" }
        DecodeTrace.suppressed = true
        DecodeTrace.log { "secret" }
        val (_, held) = DecodeTrace.holding { DecodeTrace.log { "held secret" } }
        DecodeTrace.write(held + "written secret")
        DecodeTrace.suppressed = false
        DecodeTrace.log { "after" }

        assertEquals(listOf("before", "after"), out)
    }

    @Test
    fun suppressedKeepsEnabledSoPredictorsStillTraceLater() {
        DecodeTrace.sink = { }
        DecodeTrace.suppressed = true
        assertEquals(true, DecodeTrace.enabled)
    }
}
