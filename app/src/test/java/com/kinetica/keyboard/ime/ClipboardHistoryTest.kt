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

    @Test
    fun anImageIsKeptBesideTextAndARepeatMovesToTheFront() {
        val h = ClipboardHistory()
        val pic = ClipboardHistory.Image("ab12.png", "image/png")
        h.addImage(pic, 0)
        h.add("caption", 10)
        h.addImage(pic, 20)
        assertEquals(listOf(pic, null), h.recent(30).map { it.image })
        assertEquals(setOf("ab12.png"), h.imageNames(30))
        assertEquals(pic, h.fresh(30)?.image)
    }

    @Test
    fun anEmptyTextClipDoesNotDropAnImage() {
        val h = ClipboardHistory()
        h.addImage(ClipboardHistory.Image("a.png", "image/png"), 0)
        h.add("x", 5)
        assertEquals(2, h.recent(10).size)
    }

    @Test
    fun anExpiredImageLeavesTheNamesToKeep() {
        val h = ClipboardHistory(keepMs = 1000)
        h.addImage(ClipboardHistory.Image("old.png", "image/png"), 0)
        assertTrue(h.imageNames(5000).isEmpty())
    }
}
