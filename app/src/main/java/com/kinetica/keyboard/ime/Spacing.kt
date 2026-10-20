package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.keys.StandaloneLetters
import com.kinetica.keyboard.settings.Prefs

// The automatic space and the sentence around it: when a word gets a space, when punctuation takes
// it back, and when the next letter is a capital. Moved out of KineticaIME.kt unchanged; the
// service calls them, and the JVM tests reach them.

/**
 * Whether picking a word from the bar writes a space after it.
 *
 * Not in a one-token field (an address, a URL, an expansion trigger): the same reason
 * [autospacesTappedWord] arms nothing there. A space in such a field is never wanted; a trigger
 * field strips it on save (#19).
 */
internal fun spacesAfterPick(addressField: Boolean): Boolean = !addressField

/**
 * True when a word typed entirely by tapping has earned an automatic space.
 *
 * A swipe ends with a lift, so its word is over; a tapped letter looks like the middle of a
 * longer word. The gates:
 * - [literalIsWord] is necessary and far from sufficient: of tap states spelling a real word,
 *   57% were mid-word. The delay filters most (the median gap inside a word is 168 ms), and
 *   about one fire in five is still premature at 300 ms, barely fewer at 800 ms. Rejected: a
 *   frequency floor removes good fires as fast as bad, and "top candidate equals the literal"
 *   removes 9% of misfires for 6% of good fires; the OpenSubtitles lists hold `ke`, `wh` and
 *   `whe`. The error is affordable because
 *   [retractsAutospace] takes the space back when another letter of the word follows.
 * - [literalIsStandaloneLetter] covers one letter: one-letter words were 12% of prose words
 *   (16 of 130), and every letter a-z is in every bundled list, so [StandaloneLetters] holds
 *   a curated set. One letter waits longer ([singleLetterDelayMs]); at 300 ms it spaces 17 of
 *   22 one-letter words with no premature fire on a finished word.
 * - [carriesNoToken]: a reload after a deleted space re-seeds the word as taps, and without
 *   this the timer put the space back. Scoped to one decode, since a flag that outlives its
 *   word silenced correct spaces.
 * - [addressField]: no automatic space in an email or URL field.
 *
 * All gates are read here so the whole decision stays in one testable place.
 */
internal fun autospacesTappedWord(
    enabled: Boolean,
    hasSwipeToken: Boolean,
    literal: String,
    literalIsWord: Boolean,
    literalIsStandaloneLetter: Boolean,
    addressField: Boolean,
    joinedToWhatPrecedes: Boolean,
    joinedTokenIsWord: Boolean,
    joinedByApostrophe: Boolean,
    carriesNoToken: Boolean,
): Boolean = enabled && !hasSwipeToken && !addressField && !carriesNoToken &&
    // A word that continues an earlier token takes no space unless the whole token is a
    // word: `don't` is, `automaticop` is not. An English contraction autospaces this way,
    // since its tail is too short for the length rule (see joinedTokenForAutospace).
    //
    // After an apostrophe the piece is judged on its own when the token is not a word:
    // Italian elisions (`dell'anno`, `d'accordo`, `un'ora`) are in no wordlist, while `anno`
    // and `accordo` are. Only the apostrophe: `log-12.com`, `example.com`, `name@mail.com`
    // and `notes_14.log` must keep refusing.
    //
    // The cost: `'quoted text'` gains a space, and a pause inside an elision can split it,
    // since `l'al` and `dell'ann` are entries. That was chosen over failing
    // elisions; retractsAutospace takes the space back at the next letter.
    if (joinedToWhatPrecedes) {
        joinedTokenIsWord || (joinedByApostrophe && literal.length >= 2 && literalIsWord)
    } else if (literal.length == 1) {
        // One letter: the only question is whether it is a word in this language (see
        // StandaloneLetters). After the joined branch, so the `a` of `dell'anno`, preceded
        // by an apostrophe, never reaches here.
        literalIsStandaloneLetter
    } else {
        literal.length >= 2 && literalIsWord
    }

/**
 * True when the character joining this word to what precedes it is an apostrophe.
 *
 * Separate from [joinsPrecedingToken], which asks whether the token is finished here, not
 * which joiner it is. Only the apostrophe lets [autospacesTappedWord] judge the piece after
 * it, because elision is a word boundary the dictionary cannot see. Both quote forms count:
 * the apostrophe key offers the typographic one as an alternate.
 *
 * [before] is the text preceding the word, so its last character is the one in question.
 */
internal fun joinedByApostrophe(before: CharSequence): Boolean =
    before.isNotEmpty() && (before[before.length - 1] == '\'' || before[before.length - 1] == '\u2019')

/**
 * The letters after the last apostrophe in [word], or "" when there is none.
 *
 * The retraction's second question: `dell'ann` + `o` must retract, but the elided form is
 * not in the trie, so the tail `anno` is asked. Separate from [wordBeforeAutospace], which
 * must return the whole run, or `it's` + `a` would fuse into `it'sa`.
 */
internal fun tailAfterLastApostrophe(word: String): String {
    val i = maxOf(word.lastIndexOf('\''), word.lastIndexOf('\u2019'))
    return if (i < 0) "" else word.substring(i + 1)
}

/**
 * True when a word containing at least one swipe token has earned an automatic space.
 *
 * One predicate for both call sites, so neither can miss a gate. A swipe earns its space
 * because the finger lifted and the decode landed; only two conditions remain.
 *
 * - No "user deleted a space" gate: a word swiped after a slide is a new word and earns its
 *   space, and such a flag outlives its word.
 * - [carriesNoToken] is a belt: `WordComposer.seed` replaces the token list with taps, so a
 *   reloaded word carries no swipe today. It keeps a reload that ever seeds a gesture right.
 * - [addressField]: no automatic space in an email or URL field.
 */
internal fun autospacesSwipedWord(
    hasSwipeToken: Boolean,
    addressField: Boolean,
    carriesNoToken: Boolean,
): Boolean = hasSwipeToken && !addressField && !carriesNoToken

/**
 * The whole editor token the word being typed belongs to, letters and joiners together, or ""
 * when there is nothing word-shaped there.
 *
 * A tapped apostrophe is not a letter to the composer: it routes to `onPunctuation`, which
 * finalizes the word, so `don't` reaches the composer as `don` and a one-letter `t`. Read back
 * from the editor, `don't`, `it's` and `can't` are words and space, while `log-12.com` and
 * `example.com` are not (`Alphabet.encode` rejects digits) and stay refused.
 *
 * Italian elisions are absent from the wordlist (its ten apostrophe entries are junk such as
 * `e'o` and `n'roll`), so [autospacesTappedWord] judges the piece after the apostrophe for
 * them. Generated elided forms would still let one be decoded as a single gesture.
 *
 * [before] is the text before the cursor with the word's own letters still on the end.
 */
internal fun joinedTokenForAutospace(before: CharSequence): String {
    var start = before.length
    while (start > 0) {
        val c = before[start - 1]
        // Digits are part of the token, so `log-12.com` is one thing, and they make the
        // lookup fail, since Alphabet.encode refuses them.
        if (c.isLetterOrDigit() || c in WORD_JOINERS) start-- else break
    }
    val token = before.subSequence(start, before.length).toString()
    // A run with no letter in it spells nothing to look up.
    return if (token.any { it.isLetter() }) token else ""
}

/**
 * True when the character before the word being typed joins it to what precedes, so the
 * word may be a fragment of a longer token.
 *
 * This only says the token is not finished here; whether it is finished at all is
 * [joinedTokenForAutospace]'s question, and [autospacesTappedWord] asks both. Decided from
 * the text, not the field type, so `notes_14.log` in a rename box, `e-mail`, `don't`,
 * `example.com` and `a/b` all refuse without knowing the editor.
 *
 * [before] is the text preceding the word, so its last character is the one in question.
 * Whitespace and opening punctuation allow the space, so `"hello world"` still spaces; an
 * empty read is the start of the field and allows it too.
 */
internal fun joinsPrecedingToken(before: CharSequence): Boolean {
    if (before.isEmpty()) return false
    val c = before[before.length - 1]
    if (c.isWhitespace()) return false
    if (c in SENTENCE_OPENERS || c in WORD_OPENERS) return false
    return c.isLetterOrDigit() || c in WORD_JOINERS
}

/**
 * True when an automatic space should be taken back because the word was not over.
 *
 * Only a space this keyboard put after a tapped word, and only while nothing else has
 * happened since. A swipe's autospace is never retracted: the gesture ended, so a following
 * letter starts a new word.
 *
 * [elapsedMs] is bounded because a space is provisional only while typing is in flow: `is`,
 * a break, then `land` must not produce `island`.
 *
 * [fusedIsPrefix] retracts only when the fused form could still become a word: `autom` can,
 * `automaticop` cannot. Over 67 captured word pairs it refuses 70% of would-be fusions, and
 * no first word of six letters or more fuses, the class that produced empty decodes. Short
 * words stay ambiguous, which the window covers.
 */
internal fun retractsAutospace(
    fromTappedWord: Boolean,
    tentativeLength: Int,
    elapsedMs: Long,
    windowMs: Long,
    fusedIsPrefix: Boolean,
): Boolean = fromTappedWord && tentativeLength == 0 && elapsedMs <= windowMs && fusedIsPrefix

/**
 * The word an automatic space would be taken back into: the letter run right before the space
 * at the cursor, or "" when there is no such space or word, and the retraction refuses.
 *
 * Split out from [retractAutospace] so the string half can be tested; the dictionary half is
 * one [WordPredictor.isLivePrefix] call at the site.
 */
internal fun wordBeforeAutospace(before: CharSequence): String {
    if (before.isEmpty() || before[before.length - 1] != ' ') return ""
    return trailingLetterRun(before, before.length - 1)
}

/**
 * How long the autospace timer still waits: [delay] counted from [liftAt], the last thumb's lift,
 * not from [now], when the decode landed. Counted from the decode, a 10 ms setting would wait
 * tens of milliseconds longer than it says. Never negative; a lift time unknown (-1) or in the future
 * waits the whole delay.
 */
internal fun autospaceWait(delay: Long, liftAt: Long, now: Long): Long {
    if (liftAt < 0 || liftAt > now) return delay
    return (delay - (now - liftAt)).coerceIn(0L, delay)
}

/**
 * Whether [text] is punctuation that sits against the word before it, so an automatic space
 * in front of it should go.
 *
 * Sentence and clause punctuation and closing brackets hug: "Hi" + "!" is "Hi!". An opening
 * bracket, a dash, a digit or a letter keep the space, as in "a (b)". Listed explicitly, not
 * derived from a character class: an em dash is punctuation and takes a space, and an
 * apostrophe hugs but never arrives here.
 */
internal fun hugsPreviousWord(text: String, hugging: String = Prefs.DEFAULT_NO_SPACE_BEFORE): Boolean =
    text.length == 1 && text[0] in hugging

/**
 * Whether the text before the cursor ends a sentence, so the next letter is capitalized.
 * [before] is at most `CAPS_LOOKBACK_CHARS` of the editor's text; empty is the start of the
 * field.
 *
 * `InputConnection.getCursorCapsMode` cannot answer this: `TextUtils.getCapsMode` reports
 * CAP_MODE_SENTENCES only once whitespace follows the terminator, and CAP_MODE_WORDS at a
 * paragraph start, which a CAP_SENTENCES field masks away. Those are the moments this
 * keyboard asks, since the space before a word is written with that word's commit.
 *
 * - A newline starts a paragraph and so a sentence.
 * - Closing punctuation is skipped, so 'he said "hi."' still ends one.
 * - A lone period inside its own word is an abbreviation, the platform's rule, so "e.g. "
 *   stays lower-case; a run of marks is a terminator, so "Wait..." is one.
 * - Accepted cost: "example.com" typed in a prose field reads "example.Com", as on stock
 *   keyboards; a URL field asks for no sentence caps.
 */
internal fun startsNewSentence(before: CharSequence): Boolean {
    var i = before.length
    while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) i--
    val spaced = i < before.length
    if (i == 0) return true
    if (before[i - 1] == '\n') return true
    while (i > 0 && before[i - 1] in SENTENCE_CLOSERS) i--
    if (i == 0) return true
    val last = before[i - 1]
    if (last in SENTENCE_OPENERS) return true
    if (last !in SENTENCE_TERMINATORS) return false
    var run = i
    while (run > 0 && before[run - 1] in SENTENCE_TERMINATORS) run--
    if (i - run > 1 || last != '.') return true
    // One letter and a period with no space yet is an initial, the `e.` of `e.g.`;
    // once a space follows it may end a sentence, as `Plan A. ` does.
    if (!spaced && run >= 1 && before[run - 1].isLetter() &&
        (run == 1 || before[run - 2].isWhitespace())
    ) return false
    var j = run
    while (j > 0) {
        val c = before[j - 1]
        if (c == ' ' || c == '\t' || c == '\n') break
        if (c == '.') return false
        j--
    }
    return true
}

/** What a space tap does with tidy spaces on. */
internal enum class SpaceTap { WRITE, SWALLOW, SENTENCE_END }

/**
 * A space tapped straight after a space: with the double-space full stop on and a word before
 * it, the pair ends the sentence as a double tap would; otherwise the tap is dropped, because
 * the space is already there. [before] is the two characters before the cursor.
 */
internal fun tidySpaceTap(before: CharSequence, doubleSpacePeriod: Boolean): SpaceTap = when {
    before.isEmpty() || before[before.length - 1] != ' ' -> SpaceTap.WRITE
    doubleSpacePeriod && doubleSpaceEndsSentence(before) -> SpaceTap.SENTENCE_END
    else -> SpaceTap.SWALLOW
}

/**
 * Whether the automatic space after a word should be written: with tidy spaces on, not when the
 * editor already has whitespace, a closing quote or bracket, or a mark of [hugging] right after
 * the cursor, so a word written in front of `:` or `!` gains no space before it.
 */
internal fun autospaceWanted(
    tidy: Boolean,
    after: CharSequence?,
    hugging: String = Prefs.DEFAULT_NO_SPACE_BEFORE,
): Boolean {
    if (!tidy || after.isNullOrEmpty()) return true
    val c = after[0]
    if (c in SENTENCE_CLOSERS || c in hugging) return false
    // A space or tab holds only with a word right after it (`word| next`). A newline, or a
    // space a field keeps at its end, is not the separator the next word needs: counting them
    // silenced every autospace in such a field (17 of 17 holds in one capture).
    if (c == ' ' || c == '\t') return !(after.length >= 2 && !after[1].isWhitespace())
    return true
}

/**
 * Whether the space just typed can become a sentence end.
 *
 * [before] is the two characters before the cursor. True only for a single space after a
 * letter or digit, the one shape where ". " reads as finishing a sentence. A run of spaces is
 * deliberate whitespace, and after punctuation `e.g. ` would become `e.g.. `.
 */
internal fun doubleSpaceEndsSentence(before: CharSequence): Boolean {
    if (before.length < 2) return false
    if (before[before.length - 1] != ' ') return false
    return before[before.length - 2].isLetterOrDigit()
}

private const val SENTENCE_TERMINATORS = ".!?\u2026"

/** Skipped before looking for a terminator: quotes and closing brackets. */
private const val SENTENCE_CLOSERS = ")]}\"'\u00bb\u201d\u2019"

/** Spanish opens a sentence with these, so the word after one begins it. */
private const val SENTENCE_OPENERS = "\u00bf\u00a1"

// Punctuation that opens a word instead of joining one, so a space after the word that
// follows it is still right: brackets and the opening quote forms.
// Not the straight apostrophe, although it opens single quotes too: Italian elision is far
// more common than quoted text, and `al` is a word, so `l'altro` would become `l'al tro`.
// The cost is one space typed by hand inside 'quoted text'.
private const val WORD_OPENERS = "([{\"\u00ab\u201c\u2018"

// Punctuation that binds two word-shaped pieces into one token. Every one of these
// appears mid-token in something a keyboard has to type without spacing it:
// snake_case, e-mail, don't, example.com, a/b, user@host, a\\b, C:name.
private const val WORD_JOINERS = "_-'\u2019./\\@:"

/**
 * Whether a space held at offset [owedAt] is written as a new word starts at [cursor].
 *
 * [before] is the text before the cursor. Only at the offset the space was owed, and only
 * after a non-blank character: a cursor that moved, or a space already there, settles it.
 */
internal fun paysOwedSpace(owedAt: Int, cursor: Int, before: CharSequence?): Boolean {
    if (owedAt < 0 || owedAt != cursor) return false
    if (before.isNullOrEmpty()) return false
    return !before[before.length - 1].isWhitespace()
}
