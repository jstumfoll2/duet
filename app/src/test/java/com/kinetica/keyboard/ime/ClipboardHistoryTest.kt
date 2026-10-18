package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clips the bar offers back. */
class ClipboardHistoryTest {

    @Test
    fun newestFirstARepeatMovesToTheFront() {
        val h = ClipboardHistory()
        h.add("one", 0)
        h.add("two", 10)
        h.add("one", 20)
        assertEquals(listOf("one", "two"), h.recent(30).map { it.text })
    }

    @Test
    fun sensitiveAndBlankClipsAreNotKept() {
        val h = ClipboardHistory()
        h.add("hunter2", 0, sensitive = true)
        h.add("   ", 1)
        h.add(null, 2)
        assertTrue(h.recent(3).isEmpty())
    }

    @Test
    fun oldClipsGoAndTheListIsCapped() {
        val h = ClipboardHistory(capacity = 3, keepMs = 1000)
        for (i in 0 until 5) h.add("c$i", i * 10L)
        assertEquals(listOf("c4", "c3", "c2"), h.recent(50).map { it.text })
        assertTrue(h.recent(2000).isEmpty())
    }

    @Test
    fun aFreshClipIsOfferedOnceAndOnlyWhileFresh() {
        val h = ClipboardHistory()
        h.add("address", 1000)
        assertEquals("address", h.fresh(2000)?.text)
        assertNull(h.fresh(1000 + ClipboardHistory.FRESH_MS + 1))
        h.markFreshUsed()
        assertNull(h.fresh(2000))
        h.add("phone", 3000)
        assertEquals("phone", h.fresh(3000)?.text)
    }

    @Test
    fun thePreviewIsOneShortLine() {
        assertEquals("a b c", ClipboardHistory.preview("a\n b\t\tc "))
        val long = ClipboardHistory.preview("x".repeat(100))
        assertEquals(ClipboardHistory.PREVIEW_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }
}
