package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
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
}
