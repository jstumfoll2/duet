package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word backspaced into is the word in progress again, not the previous word of its own decode:
 * 2,135 of 3,912 reloaded words in the traces (to 2026-11-12) decoded with themselves as context.
 */
class ReopenContextTest {

    @Test
    fun theLastCommitBackspacedIntoIsReopened() {
        // "our budgets |": backspace takes the space and the s.
        assertTrue(reopensLastCommit(listOf("our", "budgets"), "budget", "our"))
        // Only the space: the whole word.
        assertTrue(reopensLastCommit(listOf("our", "budgets"), "budgets", "our"))
        assertTrue(reopensLastCommit(listOf("tool"), "too", null))
        // Case and apostrophes aside.
        assertTrue(reopensLastCommit(listOf("i", "don't"), "Dont", "I"))
    }

    @Test
    fun anotherWordIsNotTheLastCommit() {
        assertFalse(reopensLastCommit(listOf("our", "budgets"), "plan", "our"))
        assertFalse(reopensLastCommit(emptyList(), "plan", null))
        assertFalse(reopensLastCommit(listOf("budgets"), "", null))
        // The same spelling elsewhere in the text: the word before it is not the commit before.
        assertFalse(reopensLastCommit(listOf("our", "budgets"), "budget", "the"))
        assertFalse(reopensLastCommit(listOf("our", "budgets"), "budget", null))
    }
}
