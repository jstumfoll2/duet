package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from upstream Kinetica's LiveEditTest and UserDictReloadTest (2.1.3, 2.2.0). */
class LiveEditsTest {

    @Test
    fun theSlideDeletesOnlyTheSpanItShowed() {
        assertTrue(stagedStillSelected("is a ", "is a ", unreadableTail = 0))
        // The selection moved or shrank since the highlight: nothing is deleted.
        assertFalse(stagedStillSelected("other", "is a ", unreadableTail = 0))
        assertFalse(stagedStillSelected("is a", "is a ", unreadableTail = 0))
        assertFalse(stagedStillSelected("", "is a ", unreadableTail = 0))
    }

    @Test
    fun anUnreadableSelectionIsComparedByItsCount() {
        val dots = UNREADABLE_CHAR.repeat(3)
        assertTrue(stagedStillSelected("word abc", "word $dots", unreadableTail = 3))
        assertFalse(stagedStillSelected("ward abc", "word $dots", unreadableTail = 3))
        assertFalse(stagedStillSelected("word ab", "word $dots", unreadableTail = 3))
    }

    @Test
    fun anEditorThatReportsNoTextKeepsItsOwnHighlight() {
        assertTrue(stagedStillSelected(null, "is a ", unreadableTail = 0))
    }

    @Test
    fun aWordOnScreenHoldsTheInstall() {
        assertTrue(holdsDictionaryInstall(tentativeLength = 4, composerPending = true))
        assertTrue(holdsDictionaryInstall(tentativeLength = 4, composerPending = false))
        assertTrue(holdsDictionaryInstall(tentativeLength = 0, composerPending = true))
        assertFalse(holdsDictionaryInstall(tentativeLength = 0, composerPending = false))
    }

    @Test
    fun aLoadReadyUnderATypedWordInstallsAtItsEnd() {
        val held = HeldInstall()
        var installs = 0
        assertTrue(held.offer(wordPending = true) { installs++ })
        assertEquals(0, installs)
        assertFalse(held.release(wordPending = true))
        assertEquals(0, installs)
        assertTrue(held.release(wordPending = false))
        assertEquals(1, installs)
        assertFalse(held.isHeld)
        assertFalse(held.release(wordPending = false))
        assertEquals(1, installs)
    }

    @Test
    fun aLoadReadyBetweenWordsInstallsAtOnce() {
        val held = HeldInstall()
        var installs = 0
        assertFalse(held.offer(wordPending = false) { installs++ })
        assertEquals(1, installs)
        assertFalse(held.isHeld)
    }

    @Test
    fun theNewestHeldLoadIsTheOneInstalled() {
        val held = HeldInstall()
        val ran = mutableListOf<String>()
        held.offer(wordPending = true) { ran += "old" }
        held.offer(wordPending = true) { ran += "new" }
        held.release(wordPending = false)
        assertEquals(listOf("new"), ran)
    }

    @Test
    fun aLoadReadyBetweenWordsDropsTheHeldOne() {
        val held = HeldInstall()
        val ran = mutableListOf<String>()
        held.offer(wordPending = true) { ran += "old" }
        held.offer(wordPending = false) { ran += "new" }
        held.release(wordPending = false)
        assertEquals(listOf("new"), ran)
    }
}
