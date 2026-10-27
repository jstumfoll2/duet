package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A word made of two (`to` tapped, `feel` swiped, read as `toddle`): where the slide stops first. */
class JoinedCommitTest {

    private val joined = JoinedCommit("toddle", head = 2, end = 17)

    @Test
    fun rightAfterTheWordTheTailGoes() {
        assertEquals(4, joined.cutBefore("it became toddle", 17))
    }

    @Test
    fun afterItsSpaceTheSpaceGoesToo() {
        assertEquals(5, joined.cutBefore("it became toddle ", 18))
    }

    @Test
    fun anywhereElseOrOnOtherTextNothingChanges() {
        assertEquals(0, joined.cutBefore("it became toddle  ", 19))
        assertEquals(0, joined.cutBefore("it became waddle", 17))
        assertEquals(0, JoinedCommit("to", head = 2, end = 5).cutBefore("to to", 5))
    }

    @Test
    fun aBackspaceRightAfterTheWordPutsTheTwoWordsBack() {
        val j = JoinedCommit("toddle", head = 2, end = 17, rest = "feel")
        assertEquals(6 to "to feel", j.splitBefore("it became toddle", 17))
        assertEquals("the space after it stays", 7 to "to feel ", j.splitBefore("it became toddle ", 18))
        assertEquals("the case of the head is kept", 7 to "To feel ", JoinedCommit("Toddle", 2, 6, "feel").splitBefore("Toddle ", 7))
        assertEquals("caps lock", 6 to "TO FEEL", JoinedCommit("TODDLE", 2, 6, "feel").splitBefore("TODDLE", 6))
    }

    @Test
    fun noSplitWithoutTheSwipesOwnReadingOrOnOtherText() {
        assertNull(joined.splitBefore("it became toddle", 17))
        val j = JoinedCommit("toddle", head = 2, end = 17, rest = "feel")
        assertNull(j.splitBefore("it became toddle  ", 19))
        assertNull(j.splitBefore("it became waddle", 17))
        assertNull("an apostrophe word is one word", JoinedCommit("it's", 1, 4, "ted").splitBefore("it's", 4))
    }

    @Test
    fun aSwipedWordTheNextSwipeRewroteSplitsBack() {
        // `changes` swiped, `the` swiped before its automatic space, read as `cheated`.
        val j = JoinedCommit("cheated", head = 0, end = 15, rest = "the", lead = "changes")
        assertEquals(7 to "changes the", j.splitBefore("it cheated", 15))
        assertEquals(8 to "changes the ", j.splitBefore("it cheated ", 16))
        assertEquals("the commit's capital", 7 to "Changes the", JoinedCommit("Cheated", 0, 7, "the", "changes").splitBefore("Cheated", 7))
        assertEquals("caps lock", 7 to "CHANGES THE", JoinedCommit("CHEATED", 0, 7, "the", "changes").splitBefore("CHEATED", 7))
        assertNull("the same word is no join", JoinedCommit("the", 0, 3, "e", "the").splitBefore("the", 3))
        assertEquals("the slide has no join to stop at", 0, j.cutBefore("it cheated", 15))
    }
}
