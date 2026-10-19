package com.kinetica.keyboard.engine

/**
 * Corrections for tapped words too short for the decode to help: one letter hit beside the one
 * meant, in a word of at most [KineticaConstants.SLIP_MAX_LEN] letters.
 *
 * [WordPredictor.realWordCorrection] only fixes a rare word the decode offers back, and the
 * decode of a two-tap word is its prefix completions: tapped `ti` lists `tie`, `tim`, `tip`, never
 * `to`. Jason's traces hold `going ti`, `need ti`, `sorry ti` and `website ti`, each retyped as
 * `to`, and a lone `s` where `a` was meant. So the replacements here are built from the board:
 * each letter swapped for a key that touches it ([neighbours]).
 *
 * A short real word is a weak signal on its own (`ti` is a word), so the previous word decides:
 * the replacement must be a strong pair with it ([KineticaConstants.SLIP_MIN_PAIR_BYTE]) and a
 * clearly stronger pair than the letters as typed. With no previous word nothing is changed.
 *
 * A lone letter is different: one that is no word in the language ([correction]'s `standalone`)
 * and no chat shorthand is wrong wherever it stands, so it needs no previous word, and its
 * commit count says nothing. Jason's keyboard held `s` at 8 commits by 2026-10-07, every one an
 * uncorrected slip for `a`, and that count is what kept the first version of this fix from
 * firing on his phone.
 */
object ShortSlips {

    /**
     * The word [literal] was meant to be, or null to keep it.
     *
     * [standalone] holds the language's one-letter words ([com.kinetica.keyboard.keys
     * .StandaloneLetters]): a lone letter is only replaced by one of them, and only when exactly
     * one sits beside it, so `s` becomes `a`. Chat shorthand ([CHAT_LETTERS]: `u` for you, `k`
     * for ok, `r` for are) is never a slip, so `u` beside `i` stays. Words
     * of two or more letters keep their letters when a spelling with an apostrophe exists
     * (`im` is `i'm`), when the letters are a common word themselves, or when the user has
     * committed them [KineticaConstants.SLIP_KEEP_COUNT] times: more than the merge floor, since
     * before this fix existed every slip was committed and counted. A lone letter is kept only
     * when the previous word pairs strongly with it as typed.
     */
    fun correction(
        p: WordPredictor,
        g: KeyboardGeometry,
        literal: String,
        prevWord: String?,
        standalone: Set<Char>,
    ): String? {
        val typed = literal.lowercase()
        if (typed.isEmpty() || typed.length > KineticaConstants.SLIP_MAX_LEN) return null
        val before = prevWord?.lowercase()?.takeIf { it.isNotEmpty() }
        if (typed.length == 1) {
            if (!isStrayLetter(typed[0], standalone)) return null
            val only = neighbours(g, typed[0]).filter { it in standalone }.singleOrNull() ?: return null
            if (before != null && p.pairByte(before, typed) >= KineticaConstants.SLIP_MIN_PAIR_BYTE) return null
            return only.toString()
        }
        val prev = before ?: return null
        if (p.personalCount(typed) >= KineticaConstants.SLIP_KEEP_COUNT) return null
        val typedPair = p.pairByte(prev, typed)
        if ((1 until typed.length).any { p.isWord(typed.substring(0, it) + "'" + typed.substring(it)) }) return null
        val typedByte = p.frequencyByte(typed)
        if (typedByte > KineticaConstants.SLIP_MAX_TYPED_BYTE) return null
        var best: String? = null
        var bestScore = -1
        for (i in typed.indices) {
            for (n in neighbours(g, typed[i])) {
                val word = typed.substring(0, i) + n + typed.substring(i + 1)
                val byte = p.frequencyByte(word)
                if (byte < 0 || byte - maxOf(typedByte, 0) < KineticaConstants.SLIP_MIN_FREQ_GAP) continue
                val pair = p.pairByte(prev, word)
                if (!strongPair(pair, typedPair)) continue
                val score = byte + pair
                if (score > bestScore) {
                    best = word
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun strongPair(pair: Int, typedPair: Int): Boolean =
        pair >= KineticaConstants.SLIP_MIN_PAIR_BYTE && pair - typedPair >= KineticaConstants.SLIP_MIN_PAIR_GAP

    /**
     * The letters whose keys touch [c]'s: the keys either side in its row, and the keys of the
     * rows directly above and below that overlap it. Read in key widths across and in rows down,
     * so the answer does not change with the keyboard's height setting, which stretches the rows.
     */
    fun neighbours(g: KeyboardGeometry, c: Char): List<Char> {
        val code = g.alphabet.codeOf(c)
        if (!g.hasKey(code)) return emptyList()
        val pitch = rowPitch(g)
        val out = ArrayList<Char>(8)
        for (other in 0 until g.letterCount) {
            if (other == code || !g.hasKey(other)) continue
            val dx = kotlin.math.abs(g.centerX(other) - g.centerX(code))
            val rows = kotlin.math.abs(g.centerY(other) - g.centerY(code)) / pitch
            val touches = if (rows < SAME_ROW) {
                dx <= KineticaConstants.SLIP_ROW_NEIGHBOUR_KW
            } else {
                rows < NEXT_ROW && dx <= KineticaConstants.SLIP_DIAGONAL_NEIGHBOUR_KW
            }
            if (touches) out.add(g.alphabet.charOf(other))
        }
        return out
    }

    /** The smallest vertical step between two key centres: one row. */
    private fun rowPitch(g: KeyboardGeometry): Float {
        var pitch = Float.MAX_VALUE
        for (a in 0 until g.letterCount) {
            if (!g.hasKey(a)) continue
            for (b in a + 1 until g.letterCount) {
                if (!g.hasKey(b)) continue
                val dy = kotlin.math.abs(g.centerY(a) - g.centerY(b))
                if (dy > MIN_ROW_STEP_KW && dy < pitch) pitch = dy
            }
        }
        return if (pitch == Float.MAX_VALUE) 1f else pitch
    }

    /**
     * Whether [c] typed alone can only be a slip: a letter that is no word in the language
     * ([standalone]) and no chat shorthand. Such a letter is also never learned, or its count
     * would rank it above the word meant.
     */
    fun isStrayLetter(c: Char, standalone: Set<Char>): Boolean =
        c.isLetter() && c.lowercaseChar() !in standalone && c.lowercaseChar() !in CHAT_LETTERS

    /** Letters written alone as chat shorthand: be, see, ok, and, oh, are, you, why. */
    private const val CHAT_LETTERS = "bcknoruy"

    private const val SAME_ROW = 0.3f
    private const val NEXT_ROW = 1.5f
    private const val MIN_ROW_STEP_KW = 0.1f
}

/**
 * A digit from the numbers row typed inside a word, where the letter below it was meant: `t9`
 * for `to`, `w8th` for `with`.
 *
 * Only a run mixing letters and digits is looked at, and only when it is not a word itself and
 * its digits read as their letters make a common word ([KineticaConstants.DIGIT_SLIP_MIN_BYTE]),
 * so `mp3`, `4k`, `b2b`, `3d` and `covid19` stay as written.
 */
object DigitSlips {

    /**
     * The word [run] was meant to be, or null. [letterBelow] maps each digit to the letter under
     * it on the board the user sees; a digit missing from it is never replaced.
     */
    fun correction(p: WordPredictor, run: String, letterBelow: Map<Char, Char>): String? {
        if (run.length < 2 || run.length > KineticaConstants.MAX_WORD_LEN) return null
        val digits = run.count { it.isDigit() }
        if (digits == 0 || digits > KineticaConstants.DIGIT_SLIP_MAX_DIGITS) return null
        if (run.any { !it.isLetterOrDigit() } || run.none { it.isLetter() }) return null
        if (p.isWordIgnoringCase(run)) return null
        val sb = StringBuilder(run.length)
        for (c in run) {
            if (c.isDigit()) sb.append(letterBelow[c] ?: return null) else sb.append(c)
        }
        val word = sb.toString()
        val byte = p.frequencyByte(word.lowercase())
        return if (byte >= KineticaConstants.DIGIT_SLIP_MIN_BYTE) word else null
    }
}
