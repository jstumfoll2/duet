package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsTest {

    @Test fun countsWordsPicksAndRetypes() {
        val s = UsageStats.EMPTY
            .withCommit(100, 5, -1, picked = false, retyped = false)
            .withCommit(100, 3, 900, picked = true, retyped = false)
            .withCommit(100, 4, 1200, picked = false, retyped = true)
            .withCorrection(100)
        val d = s.day(100)
        assertEquals(3, d.words)
        assertEquals(1, d.picked)
        assertEquals(1, d.retyped)
        assertEquals(1, d.corrected)
        assertEquals(1f / 3, d.pickShare, 1e-6f)
        assertEquals(2f / 3, d.fixShare, 1e-6f)
    }

    @Test fun onlyWordsTypedWithoutAPauseAreTimed() {
        // The first word of a field and the word after a long pause carry no time.
        val s = UsageStats.EMPTY
            .withCommit(1, 5, -1, false, false)
            .withCommit(1, 4, 1000, false, false)
            .withCommit(1, 4, UsageStats.PAUSE_MS + 1, false, false)
        assertEquals(5, s.day(1).timedChars)
        assertEquals(1000L, s.day(1).activeMs)
    }

    @Test fun wordsPerMinuteNeedsEnoughTimedTyping() {
        var s = UsageStats.EMPTY
        // 4 letters and a space every 1.5 s: one five-character word each, 40 a minute.
        repeat(10) { s = s.withCommit(1, 4, 1500, false, false) }
        assertEquals(0f, s.day(1).wpm, 0f)
        repeat(20) { s = s.withCommit(1, 4, 1500, false, false) }
        assertEquals(40f, s.day(1).wpm, 0.01f)
    }

    @Test fun addsUpARangeOfDays() {
        val s = UsageStats.EMPTY
            .withCommit(10, 3, -1, false, false)
            .withCommit(12, 3, -1, true, false)
            .withCommit(20, 3, -1, false, false)
        assertEquals(2, s.last(7, 14).words)
        assertEquals(1, s.last(1, 20).words)
        assertEquals(3, s.last(30, 20).words)
    }

    @Test fun roundTripsAndDropsBadRows() {
        val s = UsageStats.EMPTY.withCommit(5, 6, 800, true, true).withCorrection(6)
        val back = UsageStats.parse(s.serialize())
        assertEquals(s.day(5), back.day(5))
        assertEquals(s.day(6), back.day(6))
        assertEquals(listOf(7), UsageStats.parse("x|1,7|1|0|0|0|0|0,8|-1|0|0|0|0|0,9|1").heldDays())
        assertSame(UsageStats.EMPTY, UsageStats.parse(null))
        assertSame(UsageStats.EMPTY, UsageStats.parse("junk"))
    }

    @Test fun keepsOnlyTheNewestDays() {
        var s = UsageStats.EMPTY
        for (d in 1..UsageStats.MAX_DAYS + 5) s = s.withCommit(d, 3, -1, false, false)
        assertEquals(UsageStats.MAX_DAYS, s.heldDays().size)
        assertEquals(6, s.heldDays().first())
        assertTrue(UsageStats.parse(s.serialize()).heldDays().size == UsageStats.MAX_DAYS)
    }

    @Test fun anEmptyCommitChangesNothing() {
        assertSame(UsageStats.EMPTY, UsageStats.EMPTY.withCommit(1, 0, 100, false, false))
    }
}
