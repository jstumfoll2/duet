package com.kinetica.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class ScoreModelTest {

    @After
    fun reset() {
        ScoreModel.mode = ScoreModel.Mode.LEGACY
    }

    @Test
    fun legacyIsTheDefault() {
        assertEquals(ScoreModel.Mode.LEGACY, ScoreModel.mode)
    }

    @Test
    fun shapeIsOneAtAPerfectFitAndNeverFlat() {
        assertEquals(1f, ScoreModel.shapeFactor(0f), 1e-6f)
        var last = 1f
        for (i in 1..40) {
            val f = ScoreModel.shapeFactor(i * 0.1f)
            assertTrue("shape must keep falling at d=${i * 0.1f}", f < last)
            last = f
        }
    }

    @Test
    fun maxDInvertsTheShapeTerm() {
        for (num in listOf(1f, 0.3f, 0.05f)) {
            for (min in listOf(0.01f, 0.001f, 1e-5f)) {
                val d = ScoreModel.maxD(num, min)
                if (d == 0f) {
                    assertTrue(num <= min)
                    continue
                }
                assertEquals(min, num * ScoreModel.shapeFactor(d), min * 1e-3f)
            }
        }
        assertEquals(Float.POSITIVE_INFINITY, ScoreModel.maxD(1f, 0f))
        assertEquals(0f, ScoreModel.maxD(0.1f, 0.2f))
    }

    @Test
    fun bigramLogProbabilitiesAreNormalisedPerContext() {
        val table = BigramTable.build(
            listOf(Triple(1, 2, 300L), Triple(1, 3, 100L), Triple(4, 2, 5L)),
        )
        assertEquals(ln(0.75f), table.logCond(1, 2), BigramTable.LOGP_STEP)
        assertEquals(ln(0.25f), table.logCond(1, 3), BigramTable.LOGP_STEP)
        assertEquals(0f, table.logCond(4, 2), BigramTable.LOGP_STEP)
        assertTrue(table.logCond(1, 4).isNaN())
    }

    @Test
    fun trigramLogProbabilitiesAreNormalisedPerContext() {
        val table = TrigramTable.build(
            listOf(intArrayOf(1, 2, 3), intArrayOf(1, 2, 4)),
            longArrayOf(10L, 30L),
        )
        assertEquals(ln(0.25f), table.logCond(1, 2, 3), BigramTable.LOGP_STEP)
        assertEquals(ln(0.75f), table.logCond(1, 2, 4), BigramTable.LOGP_STEP)
        assertTrue(table.logCond(2, 2, 4).isNaN())
    }

    @Test
    fun unigramLogProbabilityRanksByFrequency() {
        val trie = Trie.build(listOf("the" to 255, "cat" to 120, "zax" to 1))
        val the = trie.logProb(255)
        val cat = trie.logProb(120)
        val zax = trie.logProb(1)
        assertTrue(the < 0f)
        assertTrue(the > cat && cat > zax)
    }
}
