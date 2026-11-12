package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a line cut takes: the line the cursor is on and one newline, the one after or the one
 * before as chosen, the other side's when the chosen one is missing. Counted from the cursor in
 * text read in the same call, so no remembered offset can move it.
 */
class LineCutTest {

    private val read = 50

    /** The text left once [cut] is taken out around the selection, which sits between the two halves. */
    private fun left(before: String, selected: String, after: String, cut: LineCut): String {
        assertEquals(before.takeLast(cut.back) + selected + after.take(cut.ahead), cut.text)
        return before.dropLast(cut.back) + after.drop(cut.ahead)
    }

    @Test
    fun theLineGoesWithTheNewlineAfterIt() {
        val cut = lineCut("one\ntw", "", "o\nthree", read, newlineAfter = true)!!
        assertEquals("two\n", cut.text)
        assertEquals("one\nthree", left("one\ntw", "", "o\nthree", cut))
    }

    @Test
    fun theLineGoesWithTheNewlineBeforeIt() {
        val cut = lineCut("one\ntw", "", "o\nthree", read, newlineAfter = false)!!
        assertEquals("\ntwo", cut.text)
        assertEquals("one\nthree", left("one\ntw", "", "o\nthree", cut))
    }

    @Test
    fun theLastLineHasNoNewlineAfterSoItTakesTheOneBefore() {
        val cut = lineCut("one\ntw", "", "o", read, newlineAfter = true)!!
        assertEquals("\ntwo", cut.text)
        assertEquals("one", left("one\ntw", "", "o", cut))
    }

    @Test
    fun theFirstLineHasNoNewlineBeforeSoItTakesTheOneAfter() {
        val cut = lineCut("o", "", "ne\ntwo", read, newlineAfter = false)!!
        assertEquals("one\n", cut.text)
        assertEquals("two", left("o", "", "ne\ntwo", cut))
    }

    @Test
    fun aOneLineFieldIsCutWhole() {
        for (after in listOf(true, false)) {
            val cut = lineCut("only ", "", "line", read, after)!!
            assertEquals("only line", cut.text)
            assertEquals("", left("only ", "", "line", cut))
        }
    }

    @Test
    fun anEmptyLineIsTakenOutWithItsNewline() {
        val down = lineCut("a\n", "", "\nb", read, newlineAfter = true)!!
        assertEquals("\n", down.text)
        assertEquals("a\nb", left("a\n", "", "\nb", down))
        val up = lineCut("a\n", "", "\nb", read, newlineAfter = false)!!
        assertEquals("a\nb", left("a\n", "", "\nb", up))
    }

    @Test
    fun anEmptyFieldHasNothingToCut() {
        assertNull(lineCut("", "", "", read, newlineAfter = true))
        assertNull(lineCut("", "", "", read, newlineAfter = false))
    }

    @Test
    fun aSelectionInsideTheLineGoesWithIt() {
        val cut = lineCut("a\nh", "ell", "o\nb", read, newlineAfter = true)!!
        assertEquals("hello\n", cut.text)
        assertEquals("a\nb", left("a\nh", "ell", "o\nb", cut))
    }

    @Test
    fun aSelectionAcrossLinesIsRefused() {
        assertNull(lineCut("a\nh", "ell\nx", "o", read, newlineAfter = true))
        assertNull(lineCut("a\nh", "ell\nx", "o", read, newlineAfter = false))
    }

    @Test
    fun aLineRunningPastTheReadIsRefused() {
        val full = "x".repeat(read)
        assertNull(lineCut(full, "", "end", read, newlineAfter = true))
        assertNull(lineCut("start", "", full, read, newlineAfter = false))
        // A newline inside the read means the line's end was seen.
        assertEquals("\ny", lineCut("x".repeat(read - 2) + "\ny", "", "", read, newlineAfter = true)?.text)
    }

    @Test
    fun aWindowsNewlineGoesWhole() {
        val down = lineCut("one\r\ntw", "", "o\r\nthree", read, newlineAfter = true)!!
        assertEquals("two\r\n", down.text)
        assertEquals("one\r\nthree", left("one\r\ntw", "", "o\r\nthree", down))
        val up = lineCut("one\r\ntw", "", "o\r\nthree", read, newlineAfter = false)!!
        assertEquals("\r\ntwo", up.text)
        assertEquals("one\r\nthree", left("one\r\ntw", "", "o\r\nthree", up))
    }

    @Test
    fun aParagraphBreakReadAsTwoNewlinesIsTakenWholeOnRetry() {
        // A WebView editor (Notesnook) reads a paragraph break as two newlines and snaps a span
        // ending between them back one: both cuts there were once refused as stale.
        val after = lineCut("one\n\n", "", "two\n\nthree", read, newlineAfter = true)!!
        assertEquals("two\n", after.text)
        val wideAfter = widenedAcrossBreak("one\n\n", "", "two\n\nthree", after)!!
        assertEquals("two\n\n", wideAfter.text)
        assertEquals("one\n\nthree", left("one\n\n", "", "two\n\nthree", wideAfter))
        val before = lineCut("one\n\n", "", "two\n\nthree", read, newlineAfter = false)!!
        val wideBefore = widenedAcrossBreak("one\n\n", "", "two\n\nthree", before)!!
        assertEquals("\n\ntwo", wideBefore.text)
        assertEquals("one\n\nthree", left("one\n\n", "", "two\n\nthree", wideBefore))
    }

    @Test
    fun aSingleNewlineIsNeverWidened() {
        // A plain field: the first span holds, and a single break has nothing to add.
        val cut = lineCut("one\ntw", "", "o\nthree", read, newlineAfter = true)!!
        assertNull(widenedAcrossBreak("one\ntw", "", "o\nthree", cut))
        val back = lineCut("one\ntw", "", "o\nthree", read, newlineAfter = false)!!
        assertNull(widenedAcrossBreak("one\ntw", "", "o\nthree", back))
        val whole = lineCut("", "", "only", read, newlineAfter = true)!!
        assertNull(widenedAcrossBreak("", "", "only", whole))
    }

    @Test
    fun bothCutsAreActionsWithTheirOwnSymbol() {
        val after = EditorAction.of("action:cut_line_after")
        val before = EditorAction.of("action:cut_line_before")
        assertEquals(EditorAction.CUT_LINE_AFTER, after)
        assertEquals(EditorAction.CUT_LINE_BEFORE, before)
        assertTrue(ActionRow.ALL.containsAll(listOf(EditorAction.CUT_LINE_AFTER, EditorAction.CUT_LINE_BEFORE)))
        assertEquals(EditorAction.CUT_LINE_AFTER, ActionRow.actionForGlyph(ActionRow.glyph(EditorAction.CUT_LINE_AFTER)))
    }
}
