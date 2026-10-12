package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrigramTableTest {

    private val trie = DictionaryLoader.loadWordlist(
        "want\t100\nto\t100\ngo\t90\nbe\t95\nhave\t80\nunseen\t10\n".reader().buffered(),
    )

    private fun id(w: String) = trie.nodeFor(w)

    @Test
    fun eachContextNormalisesAgainstItsOwnTopContinuation() {
        val t = DictionaryLoader.loadTrigrams(
            "want\tto\tgo\t100\nwant\tto\tbe\t50\nhave\tto\tbe\t7\n".reader().buffered(), trie,
        )
        assertEquals(3, t.size)
        assertEquals(255, t.boostByte(id("want"), id("to"), id("go")))
        assertEquals(128, t.boostByte(id("want"), id("to"), id("be")))
        // A context with one tiny count still earns the full boost for its top word.
        assertEquals(255, t.boostByte(id("have"), id("to"), id("be")))
        assertEquals(1.5f, t.multiplier(id("want"), id("to"), id("go"), 0.5f), 1e-6f)
    }

    @Test
    fun unseenTriplesAndUnknownWordsAreNeutral() {
        val t = DictionaryLoader.loadTrigrams(
            "want\tto\tgo\t100\nwant\tto\tzzz\t500\n".reader().buffered(), trie,
        )
        // The out-of-dictionary row is dropped, so it cannot squash "go"'s byte.
        assertEquals(1, t.size)
        assertEquals(255, t.boostByte(id("want"), id("to"), id("go")))
        assertEquals(1f, t.multiplier(id("want"), id("to"), id("unseen")), 0f)
        assertEquals(1f, t.multiplier(-1, id("to"), id("go")), 0f)
        assertEquals(1f, TrigramTable.EMPTY.multiplier(id("want"), id("to"), id("go")), 0f)
    }

    @Test
    fun duplicateTriplesSumBeforeNormalising() {
        val t = TrigramTable.build(
            listOf(intArrayOf(1, 2, 3), intArrayOf(1, 2, 4), intArrayOf(1, 2, 3)),
            longArrayOf(30, 50, 30),
        )
        assertEquals(2, t.size)
        assertEquals(255, t.boostByte(1, 2, 3))
        assertTrue(t.boostByte(1, 2, 4) in 210..215)
    }
}
