package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseContinuationTest {

    private fun pairs(vararg rows: Pair<String, Int>): Map<String, Int> =
        rows.associate { (words, count) ->
            val (a, b) = words.split(' ')
            WordPredictor.pairKey(a, b) to count
        }

    @Test fun followsTheUsualLinks() {
        val p = pairs("my way" to 6, "way home" to 5, "home now" to 4, "my phone" to 2)
        assertEquals(listOf("way", "home", "now"), PhraseContinuation.after("my", p))
    }

    @Test fun stopsAtFourWords() {
        val p = pairs("a b" to 5, "b c" to 5, "c d" to 5, "d e" to 5, "e f" to 5)
        assertEquals(listOf("b", "c", "d", "e"), PhraseContinuation.after("a", p))
    }

    @Test fun oneWordIsNotAPhrase() {
        assertEquals(emptyList<String>(), PhraseContinuation.after("my", pairs("my way" to 9)))
    }

    @Test fun aRareLinkEndsThePhrase() {
        val p = pairs("my way" to 6, "way home" to 5, "home now" to 2)
        assertEquals(listOf("way", "home"), PhraseContinuation.after("my", p))
        assertEquals(emptyList<String>(), PhraseContinuation.after("my", pairs("my way" to 6, "way home" to 2)))
    }

    @Test fun aLinkWithRivalsEndsThePhrase() {
        // "way" goes on to "home" only half the time.
        val p = pairs("my way" to 6, "way home" to 5, "way out" to 5)
        assertEquals(emptyList<String>(), PhraseContinuation.after("my", p))
        val q = pairs("my way" to 6, "way home" to 6, "way out" to 3, "home now" to 3)
        assertEquals(listOf("way", "home", "now"), PhraseContinuation.after("my", q))
    }

    @Test fun neverLoops() {
        val p = pairs("ha ha" to 9)
        assertEquals(emptyList<String>(), PhraseContinuation.after("ha", p))
        val q = pairs("so very" to 5, "very so" to 5)
        assertEquals(emptyList<String>(), PhraseContinuation.after("so", q))
    }

    @Test fun aRefusedWordEndsThePhrase() {
        val p = pairs("my way" to 6, "way home" to 5, "home now" to 4)
        assertEquals(listOf("way", "home"), PhraseContinuation.after("my", p) { it != "now" })
        assertEquals(emptyList<String>(), PhraseContinuation.after("my", p) { it != "home" })
    }

    @Test fun readsThePreviousWordAsTheStoreKeysIt() {
        val p = pairs("perché no" to 4, "no grazie" to 4)
        assertEquals(listOf("no", "grazie"), PhraseContinuation.after("Perché", p))
    }

    @Test fun anEmptyStoreOffersNothing() {
        assertEquals(emptyList<String>(), PhraseContinuation.after("my", emptyMap()))
        assertEquals(emptyList<String>(), PhraseContinuation.after("", pairs("my way" to 6, "way home" to 5)))
    }
}
