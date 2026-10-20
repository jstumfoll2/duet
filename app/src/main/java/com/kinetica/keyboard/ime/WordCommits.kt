package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.keys.ShiftState
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.ui.BarAdjust

// Decisions about the word in hand: what a commit, a pick, a retype or a delete spans, what is
// learned, and what the bar offers. Moved out of KineticaIME.kt unchanged; the service calls them,
// and the JVM tests reach them.

/**
 * The timestamp the first synthetic anchor of a reopened word takes, so that all [count] of
 * them land strictly before [before].
 *
 * [before] is the touch time of the letter that caused the reopen, or now for a cursor move.
 * Based on `now`, that letter's earlier touch time sorted it before the whole reloaded word
 * whenever latency exceeded the word's length in ms: `automatico` + `per` became
 * `pautomatico`, which decodes to nothing.
 */
internal fun reloadAnchorBase(before: Long, count: Int): Long = before - count - 1

/**
 * [word] as tap anchors at its keys' centres, timed to end before [beforeTime] (see
 * [reloadAnchorBase]), or null when a letter has no key on this board. Accents fold onto their
 * base keys and apostrophes are skipped: the trie puts dictionary apostrophes back for free.
 */
internal fun tapAnchors(word: String, g: KeyboardGeometry, beforeTime: Long): List<InputToken>? {
    val codes = g.alphabet.encode(AccentFolder.fold(word.lowercase())) ?: return null
    val taps = ArrayList<InputToken>(codes.size)
    val base = reloadAnchorBase(beforeTime, codes.size)
    for (i in codes.indices) {
        val code = codes[i]
        if (code == g.alphabet.apostrophe) continue
        if (!g.hasKey(code)) return null
        taps.add(
            TapToken(
                StreamId.LEFT, code, g.centerX(code), g.centerY(code),
                longPress = false, tStart = base + i, tEnd = base + i + 1,
            ),
        )
    }
    return taps.ifEmpty { null }
}

/** The shift a reloaded word's alternatives are shown in, read off the word itself. */
internal fun shiftOf(word: String): ShiftState.State = when {
    word.length > 1 && word.all { it.isUpperCase() } -> ShiftState.State.CAPS_LOCK
    word.firstOrNull()?.isUpperCase() == true -> ShiftState.State.SHIFT
    else -> ShiftState.State.NONE
}

/**
 * How much of the committed [word] is left before the cursor while backspace eats it: -1 while
 * it is still whole, the letters left while the run before the cursor is a prefix of it, 0 once
 * it is gone, null once the cursor has left it.
 */
internal fun backspaceLeft(before: CharSequence, word: String, maxTrailing: Int): Int? {
    if (commitSpan(before, word, maxTrailing) >= 0) return -1
    val run = trailingLetterRun(before)
    if (run.isEmpty()) return 0
    return if (run.length < word.length && word.startsWith(run, ignoreCase = true)) run.length else null
}

/**
 * How many characters before the cursor the committed [word] and whatever the editor put
 * after it occupy, or -1 when the editor does not hold [word] there.
 *
 * Asked of the editor, since a remembered trailing string drifted and re-casing `be?` gave
 * `bBE`. [maxTrailing] bounds what one mis-tracked commit can delete:
 * a longer run of marks means the word is not where the caller believes, so the answer is a
 * refusal. The word run is walked with [isWordChar], as the recase, retype and reload do.
 * Case is ignored because only the length is used, and auto-capitalization may have written
 * a letter the caller does not carry.
 */
internal fun commitSpan(before: CharSequence, word: String, maxTrailing: Int): Int {
    if (word.isEmpty()) return -1
    var i = before.length
    while (i > 0 && before.length - i < maxTrailing && !isWordChar(before[i - 1])) i--
    val start = i - word.length
    if (start < 0) return -1
    if (!before.subSequence(start, i).toString().equals(word, ignoreCase = true)) return -1
    return before.length - start
}

/**
 * How much text a pick from a kept bar replaces, or -1 to refuse.
 *
 * The stale timeout closed the buffer and left its candidates up, so the pick arrives after
 * the word state was cleared and no remembered length can be trusted.
 * [tailAtClose] is what the editor held before the cursor when the buffer closed: unless it
 * still holds that, something was typed or deleted since. [staleWord] is the earlier
 * decode still on screen, empty when the gesture never produced one.
 */
internal fun keptBarPickSpan(tailNow: CharSequence, tailAtClose: CharSequence, staleWord: String): Int {
    if (tailNow.toString() != tailAtClose.toString()) return -1
    if (!tailAtClose.endsWith(staleWord)) return -1
    return staleWord.length
}

/**
 * Which dictionary each candidate came from, keyed the way `languageOf` looks a word up:
 * lowercased, so a capitalized German noun such as `Haus` is filed under German, not the
 * active language.
 */
internal fun provenanceOf(candidates: List<WordCandidate>): Map<String, String> {
    if (candidates.isEmpty()) return emptyMap()
    val out = HashMap<String, String>(candidates.size * 2)
    for (c in candidates) if (c.language.isNotEmpty()) out.putIfAbsent(c.word.lowercase(), c.language)
    return out
}

/**
 * How long a one-letter word waits before its automatic space arrives.
 *
 * Longer than a word's, because one letter is weaker evidence: `a` is a word and also the
 * start of `and` and `arrivato`, and only silence tells them apart. Over 22 captured
 * one-letter words against 28 word-starts whose first letter is also a word:
 *
 * | delay | spaced | premature |
 * |---|---|---|
 * | 204 | 21/22 | 10/28 |
 * | 250 | 19/22 | 5/28 |
 * | 275 | 18/22 | 3/28 |
 * | 300 | 17/22 | 3/28 |
 * | 350 | 17/22 | 3/28 |
 * | 600 | 14/22 | 1/28 |
 *
 * [Prefs.SINGLE_LETTER_MIN_DELAY_MS] sits mid-plateau at 300: 275 is 1 ms above a cost sample,
 * 300 has 26 ms of margin below and 161 above. A floor, not a fixed value: a single letter
 * should never space sooner than a word whose delay the user raised.
 */
internal fun singleLetterDelayMs(tapDelayMs: Long, floorMs: Long): Long =
    maxOf(tapDelayMs, floorMs)

/**
 * How much text a retype deletes: the word in progress, else the word just committed with
 * whatever the keyboard put after it, else the letters the cursor is parked at the end of.
 *
 * Autospace commits fast, so a wrong word is usually committed by the time it is noticed; its
 * trailing space goes too, or the retyped word would land one space along. [wordUnderCursor]
 * covers an undecodable buffer: the stale timeout's `abandonWord` clears [tentativeLength]
 * and [CommitMemory] while the letters stay on screen. It is the read `reloadWordUnderCursor`
 * trusts, with the same guard, so a cursor parked mid-word does nothing.
 */
internal fun retypeSpan(
    tentativeLength: Int,
    commitSpan: Int,
    wordUnderCursor: String,
): Int = when {
    tentativeLength > 0 -> tentativeLength
    commitSpan >= 0 -> commitSpan
    else -> wordUnderCursor.length
}

/**
 * Which of [retypeSpan]'s three cases answered, for the trace.
 *
 * Takes [commitSpan], not the committed word: a word the editor no longer holds did not
 * answer, the run under the cursor did, and the trace has to say so.
 */
internal fun retypeSource(tentativeLength: Int, commitSpan: Int): String = when {
    tentativeLength > 0 -> "tentative"
    commitSpan >= 0 -> "commit"
    else -> "cursor"
}

/**
 * How long an undecodable buffer stays open: twice the swipe delay, never under
 * [STALE_TIMEOUT_FLOOR_MS].
 *
 * The swipe delay goes down to 10 ms (#2), and twice that would close a word typed in pieces
 * 20 ms after each piece. The floor is twice the old 100 ms minimum, so every setting
 * reachable before keeps its timeout.
 */
internal fun staleTimeoutMs(swipeDelayMs: Long): Long = maxOf(2 * swipeDelayMs, STALE_TIMEOUT_FLOOR_MS)

internal const val STALE_TIMEOUT_FLOOR_MS = 200L

/**
 * True when a slide or an unlearn takes a word that was merged into the trie (at or above the
 * merge floor) down: its trie frequency still holds the old count until the next load.
 */
internal fun userDictDemoted(countBefore: Int, countAfter: Int): Boolean =
    countBefore >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT && countAfter < countBefore

/**
 * Whether the trie must be rebuilt because a word's personal count has just made it
 * mergeable, judged from the count before and after one [KineticaIME.learnWord] call.
 *
 * The count map only supplies a ranking multiplier and cannot boost a candidate the trie
 * never produced, so a word the dictionary lacks stays undecodable until a load merges it.
 * Such a word is new, so its floor is [KineticaConstants.NEW_WORD_MERGE_MIN_COUNT].
 * True on the crossing only, so a word the reload cannot admit
 * (blocked, or past USER_DICT_LIMIT) costs one parse, not one per commit.
 */
internal fun userDictNeedsReload(
    countBefore: Int,
    countAfter: Int,
    trieHasWord: Boolean,
): Boolean = !trieHasWord &&
    countBefore < KineticaConstants.NEW_WORD_MERGE_MIN_COUNT &&
    countAfter >= KineticaConstants.NEW_WORD_MERGE_MIN_COUNT

/**
 * [candidates] with the word a retype just rejected moved to the end.
 *
 * Demoted, not dropped: of 78 retype presses, 12 handed back the word just rejected, but the
 * wanted word was among the alternates in only 5 of them, and a retype aimed at a
 * space still needs the word. Moving it last promotes the runner-up and hides nothing.
 *
 * Returns [candidates] itself when there is nothing to do, so the caller can test identity.
 */
internal fun demoteRejected(
    candidates: List<WordCandidate>,
    rejected: String?,
): List<WordCandidate> {
    if (rejected == null || candidates.size < 2) return candidates
    val i = candidates.indexOfFirst { it.word.equals(rejected, ignoreCase = true) }
    if (i < 0) return candidates
    return candidates.toMutableList().apply { add(removeAt(i)) }
}

/**
 * Whether a delimiter keeps the tapped letters as typed instead of autocorrecting them.
 *
 * - [editedByHand]: the word was reopened from the editor by a backspace or a cursor move and
 *   changed. Going back to turn a `d` into an `s`, or to finish `tion` after the keyboard made
 *   it `tino`, is the user spelling the word; correcting it again undid the edit, three times
 *   in a row in one capture.
 * - [rejected]: the user already took back an autocorrect of these exact letters.
 */
internal fun keepsTypedLetters(editedByHand: Boolean, rejected: Boolean): Boolean = editedByHand || rejected

/**
 * Whether a backspace has taken the last commit apart, so the +1 it earned goes back: [left]
 * is [backspaceLeft]'s answer after the delete. Junk decodes deleted on sight (`tsvlet`, `ioen`,
 * `preet`) otherwise stayed in the learned list, 30 of 449 learned commits in one capture.
 */
internal fun deleteTakesBackCommit(left: Int?, wordLength: Int): Boolean =
    left != null && left >= 0 && left < wordLength

/**
 * The bar for a word reopened untouched: the word on screen, then what the bar offered when it
 * was committed (so a swipe's own candidates come back), then the letters' decode. Without a
 * [history] it is the decode alone.
 */
internal fun reloadWords(decoded: List<String>, history: CommitHistory.Record?, current: String): List<String> {
    if (history == null) return decoded
    val out = ArrayList<String>()
    fun add(w: String) {
        if (w.isNotEmpty() && out.none { it.equals(w, ignoreCase = true) }) out.add(w)
    }
    add(current)
    add(history.word)
    history.alternatives.forEach(::add)
    decoded.forEach(::add)
    return out
}

/**
 * Whether committing [word] adds a unit of personal weight, given the word [reloadedFrom]
 * seeded back from the editor (null when typed from nothing).
 *
 * Deleting only the space after "hello" reloads it, and the next delimiter commits it again:
 * one authored word would earn two units, the self-reinforcing drift the merge floor and the
 * fade are tuned against. An edit still learns: "hell" reloaded and committed as "hello"
 * counts. Pure because the learn path has no JVM reach.
 */
internal fun learnsOnCommit(word: String, reloadedFrom: String?): Boolean {
    if (reloadedFrom == null) return true
    return !word.equals(reloadedFrom, ignoreCase = true)
}

/**
 * Whether a commit puts the correction strip up. [offersCorrections] is the field's own
 * answer, which is where the privateMode / noLearning split is decided and documented.
 *
 * An [optionCount] of one is suppressed: its only zone is the selected one, and a tap on the
 * selected zone is a no-op, so the strip would offer nothing.
 */
internal fun showsCorrectionStrip(
    word: String,
    offersCorrections: Boolean,
    optionCount: Int,
): Boolean = word.isNotEmpty() && offersCorrections && optionCount > 1

/**
 * The typed letters' zone on the bar, or "" for none. Letters that leave the accents off a word
 * (`pojsc` for `pójść`) are not offered while autocorrect is on: the word is on the bar, and the
 * correction strip still holds the letters after a commit. With autocorrect off the user asked
 * for what they type.
 */
internal fun literalZone(literal: String, autocorrects: Boolean, leavesAccentsOff: Boolean, blocked: Boolean = false): String =
    if (blocked || (autocorrects && leavesAccentsOff)) "" else literal

/**
 * Whether a strip pick of [replacement] over [current] takes back the autocorrect that turned
 * [typed] into [corrected]: the strip still names that word, and the pick is the typed letters.
 */
internal fun revertsAutocorrect(typed: String, corrected: String, current: String, replacement: String): Boolean =
    current.equals(corrected, ignoreCase = true) && replacement.equals(typed, ignoreCase = true)

/**
 * The word before the last one and the last one's predecessor, as written, from [tail] (the text
 * before the cursor) ending in [word]: `(before, prev)`, `before` null at the start of the text or
 * after punctuation. Null when [word] is not preceded by exactly one space and a word.
 */
internal fun grammarWords(tail: String, word: String): Pair<String?, String>? {
    if (!tail.endsWith(word)) return null
    val rest = tail.substring(0, tail.length - word.length)
    val m = GRAMMAR_TAIL_RE.find(rest) ?: return null
    return m.groupValues[1].ifEmpty { null } to m.groupValues[2]
}

private val GRAMMAR_TAIL_RE = Regex("""(?:(?:^|\s)([\p{L}']+) )?(?:^|(?<=[^\p{L}']))([\p{L}']+) $""")

/** Moves [typed] to just after the committed word in a strip's [options], adding it if absent. */
internal fun moveTypedSecond(options: MutableList<String>, typed: String) {
    if (options.isEmpty()) return
    options.remove(typed)
    options.add(1, typed)
}

/**
 * How much a backspace takes back to undo an autocorrect: the corrected word, plus the one space
 * or mark typed after it when [typedAfter] is 1. -1 when the text before the cursor no longer
 * ends that way, or more than one character followed.
 */
internal fun autocorrectUndoSpan(before: CharSequence, corrected: String, typedAfter: Int): Int {
    if (typedAfter !in 0..1 || corrected.isEmpty()) return -1
    val span = corrected.length + typedAfter
    if (before.length < span) return -1
    val tail = before.subSequence(before.length - span, before.length)
    if (!tail.startsWith(corrected)) return -1
    if (typedAfter == 1 && tail.last().isLetterOrDigit()) return -1
    return span
}

/** What blocking a word does to the text, with deleting on block on. */
internal enum class BlockDelete { COMMIT, TENTATIVE, NONE }

/**
 * Whether a blocked [word] is the text at the cursor, and which: the last commit, held on its
 * chip or the newest recent column, nothing in progress, nothing selected, no letter after the
 * cursor, and the editor still ending in it ([committedSpan] proved, as a retype proves it) with
 * nothing after it but spaces ([tailIsSpace]: a period or a newline typed after it is the
 * user's); or the word in progress, held on a live zone. Every other block leaves the text
 * alone: a wrong delete costs more than a wrong block.
 */
internal fun blockDelete(
    source: BarAdjust.BlockSource,
    word: String,
    tentativeWord: String?,
    otherPending: Boolean,
    selected: Boolean,
    letterAfter: Boolean,
    committed: String?,
    committedSpan: Int,
    tailIsSpace: Boolean = true,
): BlockDelete = when {
    selected -> BlockDelete.NONE
    source == BarAdjust.BlockSource.LIVE && tentativeWord != null && tentativeWord.equals(word, ignoreCase = true) ->
        BlockDelete.TENTATIVE
    (source == BarAdjust.BlockSource.CHIP || source == BarAdjust.BlockSource.NEWEST_RECENT) &&
        tentativeWord == null && !otherPending && !letterAfter &&
        committed != null && committed.equals(word, ignoreCase = true) && committedSpan > 0 && tailIsSpace -> BlockDelete.COMMIT
    else -> BlockDelete.NONE
}

/** [words] without the blocked spellings: a block means never offered, wherever the word was kept. */
internal fun notBlocked(words: List<String>, blocked: Set<String>): List<String> =
    if (blocked.isEmpty()) words else words.filter { it.lowercase() !in blocked }

/**
 * Composition-mode zone list: the ranked candidates, then the all-tap literal as the last zone
 * when it is not already among them, so an out-of-dictionary word commits verbatim with one
 * tap. Pure so the JVM suite can pin it; callers pass an empty literal for swipe buffers.
 */
internal fun suggestionZoneWords(candidates: List<String>, literal: String): List<String> =
    if (literal.isEmpty() || candidates.contains(literal)) candidates
    else candidates + literal

/** Two languages' predictions in one list: strongest first, one entry per word. */
internal fun mergeNextWords(
    active: List<WordPredictor.NextWord>,
    other: List<WordPredictor.NextWord>,
    limit: Int,
): List<WordPredictor.NextWord> {
    val seen = HashSet<String>()
    return (active + other).sortedByDescending { it.score }.filter { seen.add(it.word.lowercase()) }.take(limit)
}

/**
 * A committed word that was two words read as one: [word], ending at offset [end], whose first
 * [head] letters were a word of their own before the join (`to` of `toddle`, from `to` tapped
 * and `feel` swiped). [rest] is how the swipe after the tapped word read on its own (`feel`),
 * null when it was not decoded that way.
 */
internal class JoinedCommit(val word: String, val head: Int, val end: Int, val rest: String? = null) {
    /**
     * What a backspace right after the word puts back, as (characters replaced, text): the two
     * words, with the space after the word kept when one follows (`toddle ` becomes `to feel `).
     * Null unless [rest] is known, the cursor sits right after the word or after it and one
     * space, and the text there is still the word. A word with an apostrophe is never split:
     * `it's` from a tapped `i` is one word.
     */
    fun splitBefore(before: CharSequence, anchor: Int): Pair<Int, String>? {
        val r = rest?.takeIf { it.isNotEmpty() } ?: return null
        if (head <= 0 || head >= word.length) return null
        if (word.any { it == '\'' || it == '\u2019' }) return null
        val second = if (word.length > 1 && word.none { it.isLowerCase() }) r.uppercase() else r
        val two = word.substring(0, head) + " " + second
        return when {
            anchor == end && before.endsWith(word) -> word.length to two
            anchor == end + 1 && before.endsWith("$word ") -> word.length + 1 to "$two "
            else -> null
        }
    }

    /**
     * How many characters before [anchor] a backspace slide's first unit takes to stop at the
     * join, [before] being the text before [anchor]: the letters after it, and the space after
     * the word if one follows. 0 unless the cursor sits right after this word, or after it and
     * one space, and the text there is still the word.
     */
    fun cutBefore(before: CharSequence, anchor: Int): Int {
        val tail = word.length - head
        if (tail <= 0) return 0
        return when {
            anchor == end && before.endsWith(word) -> tail
            anchor == end + 1 && before.endsWith("$word ") -> tail + 1
            else -> 0
        }
    }
}
