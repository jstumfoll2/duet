package com.kinetica.keyboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApostropheJoinTest {
    private val words = setOf("don't", "i'm", "it's", "can't")
    private fun live(s: String) = words.any { it.startsWith(s) }

    @Test
    fun joinsWhereAContractionCanFollow() {
        assertTrue(ApostropheJoin.joins("don", "en", ::live))
        assertTrue(ApostropheJoin.joins("I", "en", ::live))
        assertTrue(ApostropheJoin.joins("It", "en", ::live))
        // Left out of the list on purpose, offered only when asked for: we're, i'll.
        assertTrue(ApostropheJoin.joins("we", "en", ::live))
        assertTrue(ApostropheJoin.joins("she", "en", ::live))
    }

    @Test
    fun endsTheWordElsewhere() {
        assertFalse(ApostropheJoin.joins("nell", "it", ::live))
        assertFalse(ApostropheJoin.joins("dogs", "en", ::live))
        assertFalse(ApostropheJoin.joins("", "en", ::live))
        assertFalse(ApostropheJoin.joins("rock'n", "en", ::live))
        // Marked contractions are English only.
        assertFalse(ApostropheJoin.joins("we", "es", ::live))
    }
}
