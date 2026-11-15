package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.PreviousWordFix.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PreviousWordFixTest {

    // `tot` led `to` narrowly; after `to`, `the` is far likelier than after `tot`.
    private val previous = listOf(Reading("tot", 0.010f), Reading("to", 0.008f), Reading("toy", 0.0001f))
    private val afterTot = listOf(Reading("he", 0.002f), Reading("the", 0.001f))
    private val afterTo = listOf(Reading("the", 0.02f), Reading("he", 0.001f))

    @Test
    fun offByDefault() {
        assertFalse(PreviousWordFix.enabled)
    }

    @Test
    fun onlyCloseRunnersUpAreTried() {
        assertEquals(listOf("to"), PreviousWordFix.candidates(previous, "tot").map { it.word })
        // A word the user chose that the decoder never listed is not ours to revise.
        assertEquals(emptyList<Reading>(), PreviousWordFix.candidates(previous, "tattoo"))
    }

    @Test
    fun theNextWordFlipsAPreviousWordItFitsMuchBetter() {
        assertEquals("to", PreviousWordFix.decide(previous, "tot", afterTot, mapOf("to" to afterTo)))
    }

    @Test
    fun aSmallEdgeIsNotEnough() {
        val barelyBetter = listOf(Reading("the", 0.003f))
        assertNull(PreviousWordFix.decide(previous, "tot", afterTot, mapOf("to" to barelyBetter)))
    }

    @Test
    fun theCommittedWordStandsWhenItsPairIsBest() {
        assertNull(PreviousWordFix.decide(previous, "tot", afterTo, mapOf("to" to afterTot)))
        assertNull(PreviousWordFix.decide(previous, "tot", afterTot, emptyMap()))
    }
}
