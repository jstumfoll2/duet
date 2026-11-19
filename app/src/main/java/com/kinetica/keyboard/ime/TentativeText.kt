package com.kinetica.keyboard.ime

/**
 * The word in progress as written into the field, before it is committed (split out of
 * KineticaIME, #184): how many characters before the cursor are the keyboard's own, and what
 * they say. Main thread only.
 */
class TentativeText {

    /** Characters before the cursor that the keyboard wrote for the word in hand; 0 for none. */
    var length = 0

    /** What it wrote; matches [length] unless an edit replaced only part of it. */
    var word = ""

    /** Nothing in hand. */
    fun clear() {
        length = 0
        word = ""
    }

    /** The keyboard now has [text] before the cursor as the word in hand. */
    fun set(text: String) {
        length = text.length
        word = text
    }

    /**
     * Whether the field still holds the word before the cursor, read by [before] (null when the
     * editor does not answer). The editor reports edits asynchronously, so the app may have
     * changed the text since it went in (a chat app turning `:)` into an emoji, a mention chip,
     * a formatter); replacing [length] characters then would delete the user's own text (audit
     * M6). Case is ignored, since an app may capitalize; an editor that does not answer is
     * trusted.
     */
    fun stillInField(before: (Int) -> CharSequence?): Boolean {
        if (length == 0 || word.length != length) return true
        val seen = before(length) ?: return true
        return seen.toString().equals(word, ignoreCase = true)
    }
}
