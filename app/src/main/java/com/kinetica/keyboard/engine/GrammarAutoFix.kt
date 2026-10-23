package com.kinetica.keyboard.engine

/**
 * Slips the keyboard puts right on its own as a word lands, behind the grammar auto-fix setting.
 * Each is one Harper flagged in the 2026-10-22 traces and one no reading makes right:
 *
 * - A function word typed twice ([doubled]): `to to`, `the the`, `and and`. Only words whose
 *   double is never meant: `had had`, `that that` and `is is` are English and stay.
 * - A contraction split by a stray space ([contraction]): `isn t` is `isn't`, `I m` is `I'm`.
 *   Only the fixed list in [CONTRACTIONS], so `take s` and `Lou s` (a plural or a name) stay.
 * - `I` with a verb that agrees with no `I` ([afterI]): `I are` is `I am`, unless `I` closes a
 *   compound subject (`you and I are`).
 * - `to` run into the word next to it ([joinedTo]): `tobe` is `to be`, `triedto` is `tried to`.
 *   Only when the joined letters are no word the user writes ([Lexicon.learned]) nor a common
 *   one (no word at all when `to` ends them), and the bundled pairs hold the two together.
 *
 * Harper's other splits (`kinetica` as `kinetic a`, `yoyo`, `spacebar`) and its capitals (names)
 * were wrong too often to apply unasked; they stay underlined on the proofread strip.
 *
 * Pure: no Android types; the dictionary comes in as a [Lexicon].
 */
object GrammarAutoFix {

    interface Lexicon {
        fun isWord(word: String): Boolean
        /** The bundled frequency byte, -1 when not a word. */
        fun frequencyByte(word: String): Int
        fun pairByte(prev: String, next: String): Int
        /** Whether the user writes [word] themselves: committed often enough to count. */
        fun learned(word: String): Boolean
    }

    /** Words whose double is a slip: `to to`, never `had had` or `that that`. */
    val NEVER_TWICE: Set<String> = setOf(
        "a", "an", "the", "to", "and", "or", "of", "in", "on", "at", "for", "with", "from", "if",
        "my", "your", "our", "i", "i'm", "i've", "i'll", "i'd", "but", "it's", "you're", "we're", "they're", "don't", "can't",
    )

    /** Contractions a stray space can split, written with their apostrophe. */
    val CONTRACTIONS: Set<String> = buildSet {
        for (w in listOf(
            "is", "are", "was", "were", "do", "does", "did", "has", "have", "had", "could", "would",
            "should", "must", "need", "might",
        )) add("${w}n't")
        addAll(listOf("can't", "won't", "ain't", "shan't"))
        for (p in listOf("i")) {
            add("$p'm")
        }
        for (p in listOf("i", "you", "we", "they")) {
            add("$p've"); add("$p'll"); add("$p'd")
        }
        for (p in listOf("you", "we", "they")) add("$p're")
        for (p in listOf("he", "she")) {
            add("$p'll"); add("$p'd"); add("$p's")
        }
        addAll(listOf("it's", "that's", "there's", "what's", "let's", "here's", "who's", "where's", "it'll"))
    }

    /** True when [word], just written after [prev], is [prev] again and a slip: drop it. */
    fun doubled(prev: String, word: String): Boolean {
        val w = word.lowercase()
        return w == prev.lowercase() && w in NEVER_TWICE
    }

    /**
     * The contraction [prev] and [word] are when a space split it at the apostrophe (`isn` + `t`),
     * lowercase, or null. The apostrophe may have been typed on either side of the space.
     */
    fun contraction(prev: String, word: String): String? {
        val p = prev.lowercase().trimEnd('\'', '’')
        val w = word.lowercase().trimStart('\'', '’')
        if (p.isEmpty() || w.isEmpty() || w.length > 2) return null
        if (!p.all { it.isLetter() } || !w.all { it.isLetter() }) return null
        val joined = "$p'$w"
        return joined.takeIf { it in CONTRACTIONS }
    }

    /**
     * What [word] should be after [prev] `I`, [before] being the word before that (null at the
     * start of the text or after punctuation), or null. `I are` and `I is` are `I am`.
     */
    fun afterI(before: String?, prev: String, word: String): String? {
        if (prev.lowercase() != "i") return null
        val w = word.lowercase()
        if (w != "are" && w != "is") return null
        if (before?.lowercase() in COMPOUND) return null
        return "am"
    }

    private val COMPOUND = setOf("and", "or", "nor", "&")

    /**
     * [word] cut where `to` runs into the word beside it, as (first, second), or null. The other
     * piece is a word of at least [MIN_PIECE] letters, the bundled pairs hold the two together
     * ([KineticaConstants.SPACE_SPLIT_MIN_PAIR_BYTE], as the tapped-word split asks), and the
     * joined letters are no word the user writes nor one more common than
     * [KineticaConstants.AUTOFIX_JOINED_MAX_FREQ_BYTE] (`today`, `token`, `potato` stay).
     */
    fun joinedTo(word: String, lex: Lexicon): Pair<String, String>? {
        val w = word.lowercase()
        if (w.length < 2 + MIN_PIECE || !w.all { it.isLetter() }) return null
        if (lex.learned(w)) return null
        if (lex.isWord(w) && lex.frequencyByte(w) > KineticaConstants.AUTOFIX_JOINED_MAX_FREQ_BYTE) return null
        val cuts = listOfNotNull(
            if (w.startsWith("to")) 2 else null,
            if (w.endsWith("to")) w.length - 2 else null,
        )
        var best: Pair<String, String>? = null
        var bestByte = KineticaConstants.SPACE_SPLIT_MIN_PAIR_BYTE - 1
        for (cut in cuts) {
            val head = w.substring(0, cut)
            val tail = w.substring(cut)
            val piece = if (cut == 2) tail else head
            if (piece.length < MIN_PIECE || !lex.isWord(piece)) continue
            // A word ending in `to` is a word (`canto`, `hereto`), not two run together.
            if (cut != 2 && lex.isWord(w)) continue
            val byte = lex.pairByte(head, tail)
            if (byte > bestByte) {
                bestByte = byte
                best = word.substring(0, cut) to word.substring(cut)
            }
        }
        return best
    }

    private const val MIN_PIECE = 2
}
