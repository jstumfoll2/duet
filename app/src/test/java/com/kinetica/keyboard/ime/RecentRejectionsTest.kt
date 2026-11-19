package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spellings taken back stay taken back, newest kept (#184: one class for corrections and auto-fixes). */
class RecentRejectionsTest {

    @Test
    fun matchesIgnoreCase() {
        val r = RecentRejections(4)
        r.add("NASA")
        assertTrue("nasa" in r)
        assertTrue("Nasa" in r)
        assertTrue(r.any { it == "nasa" })
    }

    @Test
    fun theOldestGoesFirstAndATakeBackAgainIsNewest() {
        val r = RecentRejections(2)
        r.add("a")
        r.add("b")
        r.add("a")
        r.add("c")
        assertTrue("a" in r)
        assertFalse("b" in r)
        assertEquals(2, r.size)
    }

    @Test
    fun nothingEmptyIsKept() {
        val r = RecentRejections(2)
        r.add("")
        assertEquals(0, r.size)
    }
}
