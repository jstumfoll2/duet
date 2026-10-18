package com.kinetica.keyboard.ime

/**
 * The recent clips the keyboard offers back on the bar, newest first.
 *
 * Android keeps one clip; this keeps the last [capacity] the keyboard saw, so an address copied
 * before a phone number is still a tap away. In memory only: nothing is written to disk, and the
 * list goes with the keyboard process. A clip the copying app marks sensitive (a password, a
 * one-time code: ClipDescription.EXTRA_IS_SENSITIVE) is never kept, and a clip older than
 * [keepMs] is dropped when the list is read.
 *
 * Pure: the clipboard listener feeds it text and times.
 */
class ClipboardHistory(
    private val capacity: Int = CAPACITY,
    private val keepMs: Long = KEEP_MS,
) {
    data class Clip(val text: String, val atMs: Long)

    private val clips = ArrayList<Clip>()

    /** The newest clip, and whether a pick or a dismissal already used it. */
    private var freshUsed = false

    /** Records [text] copied at [nowMs]. A repeat moves to the front; blank and sensitive clips are not kept. */
    fun add(text: CharSequence?, nowMs: Long, sensitive: Boolean = false) {
        if (text == null || sensitive) return
        val t = text.toString()
        if (t.isBlank()) return
        val kept = if (t.length > MAX_CHARS) t.substring(0, MAX_CHARS) else t
        clips.removeAll { it.text == kept }
        clips.add(0, Clip(kept, nowMs))
        while (clips.size > capacity) clips.removeAt(clips.size - 1)
        freshUsed = false
    }

    /** The clips still kept at [nowMs], newest first. */
    fun recent(nowMs: Long): List<Clip> {
        clips.removeAll { nowMs - it.atMs > keepMs }
        return clips.toList()
    }

    /**
     * The clip copied within [FRESH_MS] of [nowMs] and not yet used, for the bar to offer by
     * itself, as a phone's keyboard offers what was just copied. Null otherwise.
     */
    fun fresh(nowMs: Long): Clip? {
        if (freshUsed) return null
        val c = clips.firstOrNull() ?: return null
        return c.takeIf { nowMs - it.atMs in 0..FRESH_MS }
    }

    /** The fresh clip was pasted or the bar moved on: it is not offered by itself again. */
    fun markFreshUsed() {
        freshUsed = true
    }

    fun clear() {
        clips.clear()
    }

    companion object {
        const val CAPACITY = 12
        /** An hour: long enough to come back to, short enough not to hoard what was copied. */
        const val KEEP_MS = 60 * 60 * 1000L
        /** How long a new clip is offered on the idle bar by itself. */
        const val FRESH_MS = 90 * 1000L
        /** Longer clips are cut, not refused: the start is what a paste is usually for. */
        const val MAX_CHARS = 5000
        /** Characters of a clip the bar shows. */
        const val PREVIEW_CHARS = 24

        /** One line of [text], at most [max] characters, ellipsised. */
        fun preview(text: String, max: Int = PREVIEW_CHARS): String {
            val line = text.trim().replace(Regex("\\s+"), " ")
            return if (line.length <= max) line else line.substring(0, max - 1).trimEnd() + "…"
        }
    }
}
