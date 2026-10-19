package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.KeyCombo

// Reading the text around the cursor: the word under it, the word or trigger before it, its line,
// and which way a word step goes. Moved out of KineticaIME.kt unchanged; the service calls them,
// and the JVM tests reach them.

/** How far each side of the cursor [wordStepDirection] looks for a letter with a direction. */
internal const val WORD_STEP_LOOK_CHARS = 16

/**
 * The text-order direction of a word step the thumb made in [direction] (+1 right, -1 left).
 *
 * The spacebar's letter step sends DPAD keys, which the editor moves visually; the word step
 * sets the selection, which is text order. In right-to-left text those disagree, so the word step
 * is turned round when the nearest letter with a direction, on either side, is right-to-left.
 * Digits, spaces and punctuation have none and are looked past.
 */
internal fun wordStepDirection(direction: Int, before: CharSequence, after: CharSequence): Int {
    var i = before.length - 1
    var j = 0
    while (i >= 0 || j < after.length) {
        if (i >= 0) {
            rightToLeft(before[i])?.let { return if (it) -direction else direction }
            i--
        }
        if (j < after.length) {
            rightToLeft(after[j])?.let { return if (it) -direction else direction }
            j++
        }
    }
    return direction
}

private fun rightToLeft(c: Char): Boolean? = when (Character.getDirectionality(c)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
    else -> null
}

/**
 * The bar for a word the cursor sits inside: as written first, then the words its own bar had
 * when this keyboard committed it, then the decode of its letters.
 */
internal fun midWordWords(decoded: List<String>, history: CommitHistory.Record?, offer: WordAround?): List<String> {
    if (history == null || offer == null) return decoded
    val out = ArrayList<String>()
    fun add(w: String) {
        if (out.none { it.equals(w, ignoreCase = true) }) out.add(w)
    }
    add(offer.head + offer.tail)
    add(history.word)
    history.alternatives.forEach(::add)
    decoded.forEach(::add)
    return out
}

/** The word before the one whose first [head] characters end [before], lowercased; null at the start. */
internal fun wordBefore(before: CharSequence, head: String): String? {
    var i = before.length - head.length
    while (i > 0 && !isWordChar(before[i - 1])) i--
    var j = i
    while (j > 0 && isWordChar(before[j - 1])) j--
    return if (j < i) before.subSequence(j, i).toString().lowercase() else null
}

/** True when the word around the cursor is still the one [offer] was made for. */
internal fun midWordStillThere(before: CharSequence, after: CharSequence, offer: WordAround): Boolean =
    wordAroundCursor(before, after, KineticaConstants.MAX_WORD_LEN) == offer

/**
 * The run of letters (and apostrophes) ending at [end] in [before], or "" when the
 * character there is not one.
 *
 * One walk for the word the cursor ends ([KineticaIME.reloadWordUnderCursor]), the word an
 * automatic space would rejoin ([wordBeforeAutospace]) and the word a retype deletes
 * ([retypeSpan]), so they cannot disagree about where a word starts. The apostrophe counts:
 * `l'altro` and `don't` are one word to a reader.
 */
internal fun trailingLetterRun(before: CharSequence, end: Int = before.length): String {
    var start = end.coerceIn(0, before.length)
    val stop = start
    while (start > 0 && isWordChar(before[start - 1])) start--
    return before.subSequence(start, stop).toString()
}

/** What [trailingLetterRun] and [commitSpan] both count as belonging to a word. */
internal fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

/**
 * The expandify trigger sitting at the cursor, and how many characters it occupies there.
 *
 * Its own walk, not [trailingLetterRun], because a trigger is not a word: `.`, `^^` and
 * `(-.-)'` have no letters, so the only boundary is whitespace.
 *
 * One trailing space is skipped and counted in [span], since the autospace has usually
 * written one; the caller puts it back. [span] is measured from the text just handed in,
 * never remembered, so it cannot drift from the text.
 */
internal fun triggerAtCursor(before: CharSequence, maxLen: Int): TriggerSpan {
    var end = before.length
    // At most one, so `word\n\n` or a run of spaces is a boundary and not a licence to
    // reach back over it for something the user has finished with.
    if (end > 0 && before[end - 1] == ' ') end--
    var start = end
    while (start > 0 && end - start < maxLen && !before[start - 1].isWhitespace()) start--
    val trigger = before.subSequence(start, end).toString()
    if (trigger.isEmpty()) return TriggerSpan("", 0)
    return TriggerSpan(trigger, before.length - start)
}

/** [trigger] and the characters it occupies before the cursor, trailing space included. */
internal data class TriggerSpan(val trigger: String, val span: Int)

/**
 * Longest expandify trigger, shared by the walk and by the editor that validates one.
 *
 * Generous against the reporter's own, which run from one character to six, because a
 * trigger may also be a whole keyword standing in for a link. Cheap either way: it bounds
 * one editor read.
 */
const val MAX_TRIGGER_CHARS = 32

/**
 * A target written into a field that holds one line.
 *
 * `commitText` passes a newline straight through, and only the enter key asks whether
 * newlines are allowed. A single-line editor's handling of one is unpredictable: a framework
 * EditText filters it out, others keep it and break their own layout.
 */
internal fun expansionForField(target: String, multiline: Boolean): String =
    if (multiline) target else target.replace('\n', ' ').replace('\r', ' ')

/** What firing an expansion does: write text, run an action, or refuse. */
internal sealed interface ExpansionEffect {
    data class Text(val text: String) : ExpansionEffect
    data class Action(val action: EditorAction) : ExpansionEffect
    data class Combo(val combo: KeyCombo) : ExpansionEffect
    data class Refused(val why: String) : ExpansionEffect
}

/**
 * Reads an expansion's target (#19: "replace the trigger with text, or do an action").
 *
 * A target in the `action:` form runs that action, as chords and edge swipes already do.
 * [EditorAction.NOT_EXPANSION_TARGETS] and a misspelled action are refused, and refused
 * before the caller deletes anything: a typo in a stored target must not cost the trigger.
 * Anything else is text.
 */
internal fun expansionEffect(target: String): ExpansionEffect {
    // A key combination is a target, as it is for chords and edge swipes.
    KeyCombo.parse(target)?.let { return ExpansionEffect.Combo(it) }
    val action = EditorAction.of(target)
    return when {
        action != null && action in EditorAction.NOT_EXPANSION_TARGETS -> ExpansionEffect.Refused(action.name)
        action != null -> ExpansionEffect.Action(action)
        EditorAction.isUnknownAction(target) -> ExpansionEffect.Refused("unknown")
        else -> ExpansionEffect.Text(target)
    }
}

/**
 * The line the cursor is on, or null when it cannot be seen whole.
 *
 * [before] and [after] are what the editor returned for a read of [read] characters each
 * way, and [selected] is any selection between them. A side with no newline that filled
 * its read may continue past it, so the line is refused, not cut. A selection that crosses a
 * line is not one line.
 */
internal fun lineAroundCursor(
    before: CharSequence,
    selected: CharSequence,
    after: CharSequence,
    read: Int,
): String? {
    if (selected.contains('\n')) return null
    val start = before.lastIndexOf('\n')
    if (start < 0 && before.length >= read) return null
    val end = after.indexOf('\n')
    if (end < 0 && after.length >= read) return null
    val head = before.substring(start + 1)
    val tail = if (end < 0) after.toString() else after.substring(0, end)
    return head + selected + tail
}

/** True when a word character sits on both sides of the cursor. */
internal fun cursorInsideWord(before: CharSequence, after: CharSequence): Boolean =
    before.isNotEmpty() && isWordChar(before[before.length - 1]) &&
        after.isNotEmpty() && isWordChar(after[0])

/**
 * The word the cursor is parked inside, split at the cursor, or null.
 *
 * Both halves are walked out of text read from the editor in the same call, so there is no
 * remembered length to drift from. The caller reads [maxLen] + 1
 * characters each side: a word that runs past either read is longer than [maxLen] and is
 * refused, not half re-cased.
 */
internal fun wordAroundCursor(before: CharSequence, after: CharSequence, maxLen: Int): WordAround? {
    val head = trailingLetterRun(before)
    var end = 0
    while (end < after.length && isWordChar(after[end])) end++
    val tail = after.subSequence(0, end).toString()
    if (head.isEmpty() || tail.isEmpty() || head.length + tail.length > maxLen) return null
    return WordAround(head, tail)
}

/** A word split at the cursor: [head] before it, [tail] after it. */
internal data class WordAround(val head: String, val tail: String)

/**
 * True when a user-driven selection change parks a collapsed cursor somewhere a word
 * could be reopened.
 *
 * Only the shape of the selection is decided here; whether a word precedes the cursor is
 * [KineticaIME.reloadWordUnderCursor]'s question. A selection is never a reopen: the user is
 * acting on a range. Offset 0 is never one either, since nothing precedes it.
 */
internal fun reopensWordUnderCursor(selectionLength: Int, selStart: Int, selEnd: Int): Boolean =
    selectionLength == 0 && selStart == selEnd && selStart > 0

/**
 * How much one backspace removes with tidy spaces on: a run of spaces before the cursor
 * becomes one, so the next press takes the last; anything else is one character.
 */
internal fun backspaceSpan(before: CharSequence): Int {
    var n = 0
    while (n < before.length && before[before.length - 1 - n] == ' ') n++
    return if (n >= 2) n - 1 else 1
}

/**
 * The word a next-word prediction follows, from the text before the cursor, or null.
 *
 * Only after a finished word and its space: nothing mid-word, nothing after a sentence end or
 * other punctuation (there is no pair across it to read), and nothing inside a token such as
 * `name@mail.com`, whose last piece is not a word the user wrote.
 */
internal fun previousWordForPrediction(before: CharSequence?): String? {
    if (before.isNullOrEmpty() || before.last() != ' ') return null
    var end = before.length
    while (end > 0 && before[end - 1] == ' ') end--
    var start = end
    while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
    if (start == end) return null
    if (start > 0 && !before[start - 1].isWhitespace() && before[start - 1] !in PREDICT_OPENERS) return null
    val word = before.substring(start, end).trim('\'')
    return word.ifEmpty { null }
}

private const val PREDICT_OPENERS = "\"(«“‘"
