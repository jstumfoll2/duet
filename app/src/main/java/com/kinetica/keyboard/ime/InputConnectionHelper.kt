package com.kinetica.keyboard.ime

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.kinetica.keyboard.keys.KeyCombo

/**
 * All editor mutations go through here. The InputConnection is re-fetched per
 * call and every operation no-ops on null: FLAG_SECURE windows, multi-window
 * focus loss, and rotation races must never crash the service.
 */
class InputConnectionHelper(
    private val connection: () -> InputConnection?,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {

    /**
     * The text before the cursor as last read, so the several reads one key press makes cost
     * one call into the app (audit #181: each is a blocking binder call to the app's UI thread,
     * up to 2 s in a busy one). Dropped by every edit made through here, by [invalidate] (the
     * service calls it on each selection report and field change) and after [CACHE_MS], so an
     * edit the app makes on its own is seen by the next key press at the latest.
     */
    private var before: String? = null

    /** True when [before] is all the text there is before the cursor (the field's start). */
    private var beforeIsAll = false
    private var beforeConnection: InputConnection? = null
    private var beforeAt = 0L

    /** Forgets the cached text: the editor's content or cursor may have changed. */
    fun invalidate() {
        before = null
        beforeConnection = null
    }

    /** The connection for an edit: the cached text is stale from here on. */
    private fun writer(): InputConnection? {
        invalidate()
        return connection()
    }

    fun commitText(text: CharSequence): Boolean =
        writer()?.commitText(text, 1) ?: false

    /** One key pressed and released with [metaState] held, as a hardware keyboard sends it. */
    fun sendKey(keyCode: Int, metaState: Int): Boolean {
        val ic = writer() ?: return false
        val now = SystemClock.uptimeMillis()
        for (s in KeyCombo.strokes(keyCode, metaState)) {
            val action = if (s.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            ic.sendKeyEvent(KeyEvent(now, now, action, s.keyCode, 0, s.metaState))
        }
        return true
    }

    fun deleteBeforeCursor(count: Int): Boolean =
        writer()?.deleteSurroundingText(count, 0) ?: false

    fun textBeforeCursor(count: Int): CharSequence? {
        val ic = connection() ?: return null
        if (count <= 0) return ""
        val now = clock()
        val cached = before
        if (cached != null && ic === beforeConnection && now - beforeAt <= CACHE_MS) {
            if (cached.length >= count) return cached.substring(cached.length - count)
            if (beforeIsAll) return cached
        }
        val asked = maxOf(count, CACHE_CHARS)
        val read = ic.getTextBeforeCursor(asked, 0) ?: run {
            invalidate()
            return null
        }
        val text = read.toString()
        before = text
        beforeIsAll = text.length < asked
        beforeConnection = ic
        beforeAt = now
        return if (text.length > count) text.substring(text.length - count) else text
    }

    fun textAfterCursor(count: Int): CharSequence? =
        connection()?.getTextAfterCursor(count, 0)

    fun selectedText(): CharSequence? = connection()?.getSelectedText(0)

    /**
     * Moves the selection to [start]..[end] in absolute offsets, so a staged backspace span
     * shows as a highlight in the editor; [start] == [end] collapses it back to a cursor.
     */
    fun setSelection(start: Int, end: Int): Boolean =
        writer()?.setSelection(start, end) ?: false

    /**
     * The selection as the editor holds it now, in absolute offsets, or null when it cannot
     * say. Read fresh for the few edits that need a position: the offsets cached from
     * onUpdateSelection lag the editor, and an edit placed by them lands elsewhere.
     * Ported from upstream Kinetica 2.2.0.
     */
    fun liveSelection(): Pair<Int, Int>? {
        val ic = connection() ?: return null
        // One character asked for: only the offsets are wanted, not the field's text.
        val request = ExtractedTextRequest().apply { hintMaxChars = 1 }
        val et = ic.getExtractedText(request, 0) ?: return null
        if (et.selectionStart < 0 || et.selectionEnd < 0) return null
        val a = et.startOffset + et.selectionStart
        val b = et.startOffset + et.selectionEnd
        return minOf(a, b) to maxOf(a, b)
    }

    /**
     * Deletes the editor's own selection, wherever it is now, and returns its length.
     * Written over with nothing, so no offset is involved; 0 when the editor reports none.
     */
    fun deleteLiveSelection(): Int {
        val ic = writer() ?: return 0
        val selected = ic.getSelectedText(0)?.length ?: 0
        if (selected == 0) return 0
        ic.commitText("", 1)
        return selected
    }

    /** Batch-edit replacement of the last [deleteCount] chars with [text]. */
    fun replaceBeforeCursor(deleteCount: Int, text: CharSequence): Boolean {
        val ic = writer() ?: return false
        ic.beginBatchEdit()
        if (deleteCount > 0) ic.deleteSurroundingText(deleteCount, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        return true
    }

    /**
     * Batch-edit replacement of [beforeCount] chars before the cursor and [afterCount] after
     * it with [head] and [tail], leaving the cursor between the two.
     *
     * [tail] is committed with newCursorPosition 0, the start of the inserted text, so the
     * cursor needs no absolute offset and no cached selection can be stale.
     */
    fun replaceAroundCursor(
        beforeCount: Int,
        afterCount: Int,
        head: CharSequence,
        tail: CharSequence,
    ): Boolean {
        val ic = writer() ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(beforeCount, afterCount)
        ic.commitText(head, 1)
        // A pick mid-word writes no tail: the cursor ends after the word.
        if (tail.isNotEmpty()) ic.commitText(tail, 0)
        ic.endBatchEdit()
        return true
    }

    /**
     * Replaces the selection with [text] and selects the result again from [start], in one batch
     * edit, so the editor reports a single selection change.
     */
    fun replaceSelection(start: Int, text: CharSequence): Boolean {
        val ic = writer() ?: return false
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        ic.setSelection(start, start + text.length)
        ic.endBatchEdit()
        return true
    }

    fun performEditorAction(actionId: Int): Boolean =
        writer()?.performEditorAction(actionId) ?: false

    /** Context-menu editor actions (android.R.id.paste / selectAll / ...). */
    fun performContextMenuAction(id: Int): Boolean =
        writer()?.performContextMenuAction(id) ?: false

    companion object {
        /** How long a read is reused when nothing invalidated it. */
        const val CACHE_MS = 100L

        /** Read at least this much, so the next, longer read is usually served too. */
        const val CACHE_CHARS = 64
    }
}
