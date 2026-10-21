package com.kinetica.keyboard.engine

/**
 * The pure half of the Harper grammar check (grammar/HarperChecker.kt runs the native
 * library): which text to hand it, how to read what comes back, and which problem to offer.
 *
 * Harper (Apache-2.0, https://github.com/Automattic/harper) is a rule-based English checker.
 * The native side returns one record per problem, tab-separated:
 * `start end kind priority message suggestions`, offsets in UTF-16 units of the text sent,
 * suggestions split by 0x1F and each `R<text>` (replace), `D` (delete) or `I<text>` (insert
 * after). Spelling is never reported: Duet's own dictionaries decide spelling.
 *
 * Unlike [GrammarCheck], nothing here rewrites text by itself. A problem is offered on the idle
 * bar as one chip per fix, and only a tap applies it.
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

    /**
     * Where the text to check starts in [tail], the editor text before the cursor. A tail cut
     * at the read limit starts mid-word, so it is checked from the first space on.
     */
    fun windowStart(tail: CharSequence, truncated: Boolean): Int {
        if (!truncated) return 0
        val space = tail.indexOfFirst { it.isWhitespace() }
        return if (space < 0) tail.length else space + 1
    }

    /**
     * Where the sentence being written starts in [text]: after the last `.`, `!`, `?` or line
     * break that is followed by a space, not counting the marks at the very end, so a sentence
     * just closed with `. ` is still the one checked.
     */
    fun currentSentenceStart(text: CharSequence): Int {
        var end = text.length
        while (end > 0 && (text[end - 1].isWhitespace() || text[end - 1] in TERMINATORS)) end--
        for (i in end - 1 downTo 0) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c in TERMINATORS && i + 1 < text.length && text[i + 1].isWhitespace()) return i + 1
        }
        return 0
    }

    /**
     * The problem to offer: the last one in the sentence being written whose [key] is not in
     * [offered], or null. Problems in earlier sentences had their chance when they were typed.
     */
    fun pick(lints: List<Lint>, text: CharSequence, offered: Set<String>, keyBase: Int): Lint? {
        val from = currentSentenceStart(text)
        return lints.filter { it.start >= from && it.end <= text.length && key(it, text, keyBase) !in offered }
            .maxWithOrNull(compareBy<Lint> { it.start }.thenByDescending { it.priority })
    }

    /**
     * Identifies a problem across checks: where it sits in the editor ([keyBase] is the
     * editor offset of [text]'s start), what it covers and its first fix.
     */
    fun key(lint: Lint, text: CharSequence, keyBase: Int): String =
        "${keyBase + lint.start}:${text.subSequence(lint.start, lint.end)}:${lint.edits.firstOrNull()}"

    /** What a chip shows for [edit] on [problem]: the fixed words, or what goes away. */
    fun label(edit: Edit, problem: String): String = when (edit.kind) {
        EditKind.REPLACE -> "$CHIP ${edit.text.ifEmpty { "∅" }}"
        EditKind.DELETE -> "$CHIP −${problem.trim()}"
        EditKind.INSERT_AFTER -> "$CHIP ${problem.trim()}${edit.text}"
    }

    /** What replaces [problem] when [edit] is applied. */
    fun applied(edit: Edit, problem: String): String = when (edit.kind) {
        EditKind.REPLACE -> edit.text
        EditKind.DELETE -> ""
        EditKind.INSERT_AFTER -> problem + edit.text
    }

    /** Most chips one problem puts on the bar. */
    const val MAX_FIXES = 3

    /** Marks a grammar fix on the bar, apart from the words around it. */
    const val CHIP = "✎"

    private const val TERMINATORS = ".!?"
}
