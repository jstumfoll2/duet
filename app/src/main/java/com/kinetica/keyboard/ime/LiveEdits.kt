package com.kinetica.keyboard.ime

/*
 * Two upstream Kinetica fixes (2.1.3 and 2.2.0), ported to Duet's single-file IME:
 * edits act on the editor's live selection, and a dictionary install waits for the word in
 * progress to end.
 */

/** Stands in for a selected character the editor would not hand over. */
internal const val UNREADABLE_CHAR = "·"

/**
 * Whether the editor's selection [live] is still the span a backspace slide staged.
 * [unreadableTail] characters at the end were shown as placeholders, so only their count is
 * compared. An editor that reports no selected text keeps the slide's own highlight.
 */
internal fun stagedStillSelected(live: String?, staged: String, unreadableTail: Int): Boolean {
    if (live == null) return true
    // Equal heads of equal drops: the lengths agree too.
    return live.dropLast(unreadableTail) == staged.dropLast(unreadableTail)
}

/**
 * Whether a parsed dictionary must wait before it is installed: a word is on screen or in the
 * composer. The install replaces the composer, and the word loses its tokens and its space.
 */
internal fun holdsDictionaryInstall(tentativeLength: Int, composerPending: Boolean): Boolean =
    tentativeLength > 0 || composerPending

/**
 * A dictionary install kept until the word in progress ends.
 *
 * The reload's guard looks at fire time only, and the parse is long enough for a word to start
 * under it. Only the newest load is kept: an older one would install stale rows.
 */
internal class HeldInstall {
    private var held: (() -> Unit)? = null

    val isHeld: Boolean get() = held != null

    /** Runs [install] now, or keeps it while [wordPending]. True when kept. */
    fun offer(wordPending: Boolean, install: () -> Unit): Boolean {
        if (wordPending) {
            held = install
            return true
        }
        held = null
        install()
        return false
    }

    /** Runs the kept install unless [wordPending]. True when it ran. */
    fun release(wordPending: Boolean): Boolean {
        val install = held ?: return false
        if (wordPending) return false
        held = null
        install()
        return true
    }
}
