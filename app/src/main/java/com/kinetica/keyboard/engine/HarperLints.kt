package com.kinetica.keyboard.engine

/**
 * The pure half of the Harper grammar check (spell/KineticaSpellChecker.kt runs the native
 * library through grammar/HarperNative.kt): how to read what comes back.
 *
 * Harper (Apache-2.0, https://github.com/Automattic/harper) is a rule-based English checker.
 * The native side returns one record per problem, tab-separated:
 * `start end kind priority message suggestions`, offsets in UTF-16 units of the text sent,
 * suggestions split by 0x1F and each `R<text>` (replace), `D` (delete) or `I<text>` (insert
 * after). Spelling is never reported: Duet's own dictionaries decide spelling.
 *
 * Unlike [GrammarCheck], nothing here rewrites text by itself. The problems reach the app as
 * grammar results of Duet's spell checker, so the text field underlines them and offers the
 * fixes when the underlined words are tapped.
 */
object HarperLints {

    enum class EditKind { REPLACE, DELETE, INSERT_AFTER }

    data class Edit(val kind: EditKind, val text: String)

    data class Lint(
        val start: Int,
        val end: Int,
        val kind: String,
        val priority: Int,
        val message: String,
        val edits: List<Edit>,
    )

    /** The records in [encoded]; a malformed record is skipped, never thrown on. */
    fun parse(encoded: String): List<Lint> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) return@mapNotNull null
            val start = f[0].toIntOrNull() ?: return@mapNotNull null
            val end = f[1].toIntOrNull() ?: return@mapNotNull null
            if (start < 0 || end < start) return@mapNotNull null
            val edits = f[5].split('\u001F').mapNotNull { s ->
                when (s.firstOrNull()) {
                    'R' -> Edit(EditKind.REPLACE, s.substring(1))
                    'D' -> Edit(EditKind.DELETE, "")
                    'I' -> Edit(EditKind.INSERT_AFTER, s.substring(1))
                    else -> null
                }
            }.distinct()
            if (edits.isEmpty()) return@mapNotNull null
            Lint(start, end, f[2], f[3].toIntOrNull() ?: 127, f[4], edits)
        }
    }

    /** What replaces [problem] when [edit] is applied. */
    fun applied(edit: Edit, problem: String): String = when (edit.kind) {
        EditKind.REPLACE -> edit.text
        EditKind.DELETE -> ""
        EditKind.INSERT_AFTER -> problem + edit.text
    }

    /**
     * One underline: [length] UTF-16 units from [start], what a tap offers in its place, and
     * Harper's explanation.
     */
    data class Mark(val start: Int, val length: Int, val suggestions: List<String>, val message: String = "")

    /**
     * The underlines for [lints] found in [text], in text order, at most [limit] suggestions
     * each. A deletion is not offered (an empty suggestion reads as a blank row in the popup),
     * so a problem whose only fix is one is underlined without suggestions. Spans outside the
     * text, empty spans and a second problem on the same span are dropped.
     */
    fun marks(lints: List<Lint>, text: String, limit: Int): List<Mark> {
        val seen = HashSet<Pair<Int, Int>>()
        return lints.sortedBy { it.start }.mapNotNull { l ->
            if (l.start < 0 || l.end > text.length || l.end <= l.start || !seen.add(l.start to l.end)) {
                return@mapNotNull null
            }
            val problem = text.substring(l.start, l.end)
            val offered = l.edits.filter { it.kind != EditKind.DELETE }
                .map { applied(it, problem) }
                .filter { it.isNotEmpty() && it != problem }
                .distinct()
                .take(limit)
            Mark(l.start, l.end - l.start, offered, l.message)
        }
    }

    /**
     * What the proofread strip shows: the sentence being written, from [start] in the text
     * checked, and the marks inside it, offsets relative to the sentence.
     */
    data class Proof(val start: Int, val sentence: String, val marks: List<Mark>)

    /** The strip's content for [text] (the editor text before the cursor) and its [marks]. */
    fun proof(text: String, marks: List<Mark>): Proof {
        val start = currentSentenceStart(text)
        val sentence = text.substring(start).trimEnd()
        val inside = marks.filter { it.start >= start && it.start + it.length <= start + sentence.length }
            .map { it.copy(start = it.start - start) }
        return Proof(start, sentence, inside)
    }

    /**
     * Where the sentence being written starts in [text]: after the last `.`, `!`, `?` or line
     * break that is followed by a space, not counting the marks at the very end, so a sentence
     * just closed with `. ` is still the one shown. Leading spaces are skipped.
     */
    fun currentSentenceStart(text: CharSequence): Int {
        var end = text.length
        while (end > 0 && (text[end - 1].isWhitespace() || text[end - 1] in TERMINATORS)) end--
        var start = 0
        for (i in end - 1 downTo 0) {
            val c = text[i]
            if (c == '\n' || (c in TERMINATORS && i + 1 < text.length && text[i + 1].isWhitespace())) {
                start = i + 1
                break
            }
        }
        while (start < end && text[start].isWhitespace()) start++
        return start
    }

    /**
     * Where a tail read at the read limit should be checked from: it starts mid-word, so from
     * the first space on. A tail shorter than the limit is the whole text and kept.
     */
    fun windowStart(tail: CharSequence, truncated: Boolean): Int {
        if (!truncated) return 0
        val space = tail.indexOfFirst { it.isWhitespace() }
        return if (space < 0) tail.length else space + 1
    }

    private const val TERMINATORS = ".!?"
}
