package com.kinetica.keyboard.ime

// What one backspace right after a commit can take back, and what the user has already taken
// back. Moved out of KineticaIME (#184); the service holds one of each and clears them.

/** The last autocorrect, until anything else is typed: a backspace puts the letters back. */
internal class AutocorrectUndo(val typed: String, val corrected: String, val end: Int)

/**
 * The grammar pass's last rewrite: [shown] became [replacement] because [next] followed. The
 * next word is its only evidence, so when that word is deleted the rewrite goes back. Traces
 * held four rewrites, two of them `an` made `a` by a fragment the user then deleted.
 */
internal class GrammarFix(val shown: String, val replacement: String, val next: String)

/**
 * The last grammar auto-fix: [shown] became [fixed], ending at [end], with the composer's
 * [context] as it was before. A backspace right after puts [shown] back, as it does an
 * autocorrect.
 */
internal class AutoFixUndo(val shown: String, val fixed: String, val end: Int, val context: List<String>?)

/**
 * Spellings the user took back, lowercased, oldest first, at most [max]. Not offered or made
 * again while the keyboard runs, so an acronym undone once is not rewritten at every delimiter
 * after it. Taking one back again makes it the newest, so the oldest goes first.
 */
internal class RecentRejections(private val max: Int) {
    private val set = LinkedHashSet<String>()

    val size: Int get() = set.size

    fun add(text: String) {
        val t = text.lowercase()
        if (t.isEmpty()) return
        set.remove(t)
        set.add(t)
        while (set.size > max) set.remove(set.first())
    }

    operator fun contains(text: String): Boolean = text.lowercase() in set

    fun any(predicate: (String) -> Boolean): Boolean = set.any(predicate)
}
