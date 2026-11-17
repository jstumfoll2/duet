package com.kinetica.keyboard.engine

import kotlin.math.ln

/**
 * The words of a sentence read again together (issue #139): the "fix this sentence" action.
 *
 * Each word was committed on the words before it. Once the sentence stands, the words after it
 * are evidence too, and so are the runners-up the decoder ranked just behind each commit.
 * [PreviousWordFix] looks one word back as the next is typed; this looks at the whole sentence
 * at once and re-decodes nothing: it works from the ranked readings kept for each word.
 *
 * Every word offers its commit and its close runners-up. A path through the sentence scores
 *
 *     sum over words of  ln S(r) - ln S(commit)  +  A * (lm(r | p) - lm(r | commit before))  -  MARGIN if r is not the commit
 *
 * with `r` the reading taken for a word and `p` the reading taken for the word before it: the
 * reading's own score against the commit's, corrected for the context it would now stand in.
 * (A reading's score already holds `A * lm` given the committed word before it, so only the
 * difference is added. The two-word context is not redone.) The commits alone score 0, so the
 * best path changes a word only where the change earns more than [MARGIN] nats.
 *
 * Fitted for the log-linear score, as [PreviousWordFix] is. Pure: the caller supplies the
 * readings and the language model.
 */
object SentenceFix {

    /** A runner-up further than this behind its commit, in nats, is not offered. */
    @Volatile @JvmField var GAP = 2f

    /** What a change must earn, in nats, over leaving the commit. */
    @Volatile @JvmField var MARGIN = 0.75f

    /** At most this many runners-up per word. */
    @Volatile @JvmField var MAX_ALTERNATES = 3

    /**
     * One word of the sentence: what was committed and the ranked readings it was committed
     * from. No [readings], or a commit that is not among them, is a word the user typed, picked
     * or corrected: it is context for its neighbours and never changed.
     */
    class Word(val committed: String, val readings: List<PreviousWordFix.Reading> = emptyList())

    /** Word [index] of the sentence should read [to], not [from]. */
    data class Change(val index: Int, val from: String, val to: String)

    private class Option(val word: String, val gain: Float)

    /**
     * The changes that make [words] read best together, in order; empty when the commits
     * already do. [before] is the word in front of the sentence, if any. [lm] is
     * `ln P(word | previous)` ([WordPredictor.lmOf]); [a] its weight in the score.
     */
    fun fix(
        words: List<Word>,
        before: String?,
        a: Float = ScoreModel.A,
        lm: (word: String, prev: String?) -> Float,
    ): List<Change> {
        if (words.isEmpty()) return emptyList()
        val options = words.map { optionsOf(it) }
        if (options.all { it.size == 1 }) return emptyList()
        // best[i][k]: the best score of a path ending in option k of word i; from[i][k] its
        // option of word i - 1.
        val best = ArrayList<FloatArray>(words.size)
        val from = ArrayList<IntArray>(words.size)
        for (i in words.indices) {
            val opts = options[i]
            val b = FloatArray(opts.size)
            val f = IntArray(opts.size)
            for ((k, o) in opts.withIndex()) {
                if (i == 0) {
                    b[k] = o.gain
                    continue
                }
                val prevOpts = options[i - 1]
                val committedPrev = prevOpts[0].word
                val base = lm(o.word, committedPrev)
                var top = Float.NEGATIVE_INFINITY
                var arg = 0
                for ((j, p) in prevOpts.withIndex()) {
                    val edge = if (j == 0) 0f else a * (lm(o.word, p.word) - base)
                    val v = best[i - 1][j] + edge
                    if (v > top) {
                        top = v
                        arg = j
                    }
                }
                b[k] = top + o.gain
                f[k] = arg
            }
            best.add(b)
            from.add(f)
        }
        var k = best.last().indices.maxByOrNull { best.last()[it] } ?: 0
        // Ties and losses go to the commits: option 0 everywhere scores exactly 0.
        if (best.last()[k] <= 0f) return emptyList()
        val out = ArrayList<Change>()
        for (i in words.indices.reversed()) {
            if (k != 0) out.add(Change(i, words[i].committed, options[i][k].word))
            k = from[i][k]
        }
        out.reverse()
        return out
    }

    /**
     * An edit of the text before the cursor: its last [span] characters become [text]. [changes]
     * index the words of the history the plan was made from; [backs] says how many words from
     * the end each one is (0 is the last word).
     */
    class Plan(val span: String, val text: String, val changes: List<Change>, val backs: List<Int>) {
        /** `tot → to, he → the`, for the bar. */
        val label: String get() = changes.joinToString(", ") { "${it.from} \u2192 ${it.to}" }
    }

    /**
     * The fix for the sentence that ends [tail], the text before the cursor, or null when there
     * is nothing to change. [history] is the words committed lately, oldest first, each with its
     * readings. Only the words that still stand at the end of the text exactly as committed,
     * one space apart, are read again: the run stops at the sentence's start, at the first word
     * that was edited since, and at anything the history does not know. A word keeps the
     * capital and the comma or colon it has on screen.
     */
    fun plan(
        tail: String,
        history: List<Word>,
        a: Float = ScoreModel.A,
        lm: (word: String, prev: String?) -> Float,
    ): Plan? {
        val body = tail.trimEnd(' ')
        val trailing = tail.substring(body.length)
        if (body.isEmpty() || trailing.length > 1) return null
        var start = 0
        for (i in body.length - 2 downTo 0) {
            val c = body[i]
            if (c == '\n' || ((c == '.' || c == '!' || c == '?') && body[i + 1] == ' ')) {
                start = i + 1
                break
            }
        }
        val tokens = body.substring(start).split(' ')
        var n = 0
        while (n < tokens.size && n < history.size) {
            val core = core(tokens[tokens.size - 1 - n])
            if (core.isEmpty() || !core.equals(history[history.size - 1 - n].committed, ignoreCase = true)) break
            n++
        }
        if (n < 2) return null
        val first = tokens.size - n
        val before = tokens.getOrNull(first - 1)?.let { core(it) }?.takeIf { it.isNotEmpty() }
        val changes = fix(history.subList(history.size - n, history.size), before, a, lm)
        if (changes.isEmpty()) return null
        val out = tokens.toMutableList()
        for (c in changes) {
            val t = tokens[first + c.index]
            val core = core(t)
            out[first + c.index] = GrammarCheck.inCaseOf(core, c.to) + t.substring(core.length)
        }
        val from = first + changes[0].index
        return Plan(
            tokens.subList(from, tokens.size).joinToString(" ") + trailing,
            out.subList(from, out.size).joinToString(" ") + trailing,
            changes,
            changes.map { n - 1 - it.index },
        )
    }

    /** A word on screen without the marks that close it. */
    private fun core(token: String): String = token.trimEnd('.', ',', ';', ':', '!', '?')

    /** The commit first (gain 0), then its close runners-up, each charged [MARGIN]. */
    private fun optionsOf(w: Word): List<Option> {
        val out = ArrayList<Option>(1 + MAX_ALTERNATES)
        out.add(Option(w.committed, 0f))
        val own = w.readings.firstOrNull { it.word.equals(w.committed, ignoreCase = true) } ?: return out
        if (own.score <= 0f) return out
        for (r in w.readings) {
            if (out.size > MAX_ALTERNATES) break
            if (r.score <= 0f || r.word.equals(w.committed, ignoreCase = true)) continue
            val gap = ln(own.score / r.score)
            if (gap > GAP) continue
            out.add(Option(r.word, -gap - MARGIN))
        }
        return out
    }
}
