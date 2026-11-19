package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import kotlin.math.ln
import kotlin.math.ln1p

/**
 * A buffer read as one, two or three words, with the word breaks chosen inside one search
 * (decoder rework step 10, issue #133; the first slice of it).
 *
 * Today a buffer is one word unless one of five separate rules says otherwise, each for one
 * shape of buffer: the automatic-space timer, [PeckSplit] for taps then a swipe, the rejoin
 * rule for a reopened word, the space split and the backspace split. None of them can read
 * `changes` swiped, `the` swiped straight after, as two words; the pair decodes as `cheated`.
 *
 * Here every lift between gestures is a place a word may end ([boundaries]). The tokens between
 * two such places are an arc, read by the ordinary decoder after the word before it, and a path
 * of arcs is scored
 *
 *     EVIDENCE * sum(ln S(arc's word)) + sum(break(boundary))
 *     break = BREAK + GAP * g + [tap before] (PREV_TAP + PREV_TAP_GAP * g)
 *                              + [tap after] (NEXT_TAP + NEXT_TAP_GAP * g),   g = ln(1 + gap ms)
 *
 * so a break is a transition with its own score, as a letter is, and the best path wins
 * ([decode]). The one-word path has no break and is the buffer as it decodes today. The break
 * term is [BoundaryModel]'s idea made local to the boundary: how long the thumbs were off the
 * glass, and whether a tap sits on either side (a word started with taps runs straight on into
 * its swipe; a new word takes a moment).
 *
 * What this slice is not: it breaks only where every finger is up, never inside a stroke; arcs
 * are decoded by the current search, one decode per arc, not by one pass over the event stream;
 * and nothing in the keyboard calls it yet. Log-linear score only ([ScoreModel]), since only
 * there do two words' scores multiply as probabilities. Off by default ([enabled]).
 *
 * Weights fitted on the `latticedata` rows of all trace files (2026-11-16), see
 * `tools/experiment/lattice_fit.py`.
 */
object LatticeDecoder {

    /** Whether callers may read a buffer as several words. Nothing in the keyboard asks yet. */
    var enabled: Boolean
        get() = DecoderConfig.current().lattice
        set(value) = DecoderConfig.update { it.copy(lattice = value) }

    /** Boundaries tried in one buffer: the longest lifts. */
    @JvmField var MAX_BOUNDARIES = 3

    /** Words in one path at most. */
    @JvmField var MAX_WORDS = 3

    @JvmField var EVIDENCE = 0.426f
    @JvmField var BREAK = -12.559f
    @JvmField var GAP = 3.452f
    @JvmField var PREV_TAP = 0.029f
    @JvmField var PREV_TAP_GAP = -0.517f
    @JvmField var NEXT_TAP = 5.606f
    @JvmField var NEXT_TAP_GAP = -1.558f

    /**
     * How far a path of several words must beat the one word. A break that was not meant spoils
     * a word that was right, and costs more than a missed one, which only leaves things as today.
     * Held out by halves (A = files before 2026-10-22, each fitted on the other), one-word
     * buffers broken and two unspaced words both read right: margin 0, 182 + 29 of 20,868 and
     * 67.4% / 63.4%; margin 2, 68 + 12 and 65.6% / 57.3%; margin 4, 22 + 5 and 57.7% / 42.8%.
     * Today's rules read 8.6% / 9.2% of those pairs.
     */
    @JvmField var MARGIN = 2f

    /** The weights in the fit's order, for the replay flag. */
    fun setWeights(w: FloatArray) {
        require(w.size == 7)
        EVIDENCE = w[0]; BREAK = w[1]; GAP = w[2]; PREV_TAP = w[3]; PREV_TAP_GAP = w[4]; NEXT_TAP = w[5]; NEXT_TAP_GAP = w[6]
    }

    /** One word of a path: tokens `from until to` of the sorted buffer, and their decode. */
    class Arc(val from: Int, val to: Int, val list: List<WordCandidate>, val word: WordCandidate)

    /** A reading of the buffer: its words in order, and its score against the other paths. */
    class Path(val arcs: List<Arc>, val score: Float) {
        val words: List<String> get() = arcs.map { it.word.word }
    }

    /** The best path, and the one-word path it was weighed against (null when the buffer reads as nothing). */
    class Result(val best: Path, val one: Path?) {
        val split: Boolean get() = best.arcs.size > 1
    }

    /**
     * Where a word may end in [sorted] (tokens by start time): each index whose token began
     * after every token before it had lifted. At most [MAX_BOUNDARIES], the longest lifts,
     * in order.
     */
    fun boundaries(sorted: List<InputToken>): List<Int> {
        val out = ArrayList<Pair<Int, Long>>()
        var lift = Long.MIN_VALUE
        for (i in sorted.indices) {
            if (i > 0 && sorted[i].tStart >= lift) out.add(i to sorted[i].tStart - lift)
            lift = maxOf(lift, sorted[i].tEnd)
        }
        if (out.size <= MAX_BOUNDARIES) return out.map { it.first }
        return out.sortedByDescending { it.second }.take(MAX_BOUNDARIES).map { it.first }.sorted()
    }

    /** Milliseconds every finger was up before token [i] of [sorted]. */
    fun gapBefore(sorted: List<InputToken>, i: Int): Long =
        sorted[i].tStart - sorted.subList(0, i).maxOf { it.tEnd }

    /** The score of ending a word before token [i]. */
    fun breakScore(sorted: List<InputToken>, i: Int): Float {
        val g = ln1p(maxOf(gapBefore(sorted, i), 0L).toDouble()).toFloat()
        var z = BREAK + GAP * g
        if (sorted[i - 1] is TapToken) z += PREV_TAP + PREV_TAP_GAP * g
        if (sorted[i] is TapToken) z += NEXT_TAP + NEXT_TAP_GAP * g
        return z
    }

    /**
     * The word an arc stands for, or null when its tokens are no word of their own. Taps alone
     * must spell a word, not start one (a completion is a guess at a longer word), and a single
     * letter must be one that stands alone in the language.
     */
    fun arcWord(
        predictor: WordPredictor,
        tokens: List<InputToken>,
        list: List<WordCandidate>,
        oneLetterWord: (Char) -> Boolean,
    ): WordCandidate? {
        val taps = tokens.all { it is TapToken }
        val w = (if (taps) list.firstOrNull { it.source != WordCandidate.Source.COMPLETION } else list.firstOrNull()) ?: return null
        if (w.word.length == 1) return w.takeIf { oneLetterWord(w.word[0].lowercaseChar()) }
        if (taps && !predictor.isWord(w.word)) return null
        return w
    }

    /** `ln` of a word's score as a path counts it: never above 0, so a boosted short word cannot pay for a break. */
    fun lnScore(c: WordCandidate): Float = if (c.score <= 0f) Float.NEGATIVE_INFINITY else minOf(0f, ln(c.score))

    /**
     * [tokens] read as the best path of up to [MAX_WORDS] words. [joined] is the buffer's own
     * decode when the caller has it. Null when the buffer has no place to break, has an
     * apostrophe stroke (which starts no word), or reads as nothing either way.
     */
    fun decode(
        predictor: WordPredictor,
        tokens: List<InputToken>,
        context: List<String>,
        joined: List<WordCandidate>? = null,
        oneLetterWord: (Char) -> Boolean,
    ): Result? {
        val sorted = tokens.sortedBy { it.tStart }
        if (sorted.any { it is TapToken && it.apostrophe }) return null
        val cuts = boundaries(sorted)
        if (cuts.isEmpty()) return null
        val nodes = listOf(0) + cuts + sorted.size
        val whole = joined ?: predictor.decode(sorted, context)
        val one = whole.firstOrNull()?.let { Path(listOf(Arc(0, sorted.size, whole, it)), EVIDENCE * lnScore(it)) }

        // best[k][n]: the best path of n + 1 words ending at node k + 1 (first-order: one path
        // per node and word count, the next arc decoded after that path's last word).
        val best = Array(nodes.size) { arrayOfNulls<Path>(MAX_WORDS) }
        for (j in 1 until nodes.size) {
            for (i in 0 until j) {
                if (i == 0 && j == nodes.size - 1) continue // the one-word path
                val part = sorted.subList(nodes[i], nodes[j])
                val before: List<Path?> = if (i == 0) listOf(null) else best[i].toList()
                for (prev in before) {
                    if (i > 0 && prev == null) continue
                    val n = prev?.arcs?.size ?: 0
                    if (n >= MAX_WORDS) continue
                    // The last word of a path may use every word; an earlier one leaves room for one more.
                    if (j < nodes.size - 1 && n + 1 >= MAX_WORDS) continue
                    val ctx = if (prev == null) context else (context + prev.words).takeLast(2)
                    val list = predictor.decode(part, ctx)
                    val w = arcWord(predictor, part, list, oneLetterWord) ?: continue
                    val s = (prev?.score ?: 0f) + EVIDENCE * lnScore(w) + (if (i > 0) breakScore(sorted, nodes[i]) else 0f)
                    if (s == Float.NEGATIVE_INFINITY) continue
                    val cur = best[j][n]
                    if (cur == null || s > cur.score) best[j][n] = Path((prev?.arcs ?: emptyList()) + Arc(nodes[i], nodes[j], list, w), s)
                }
            }
        }
        val split = best[nodes.size - 1].filterNotNull().maxByOrNull { it.score }
        val top = when {
            split == null -> one
            one == null || split.score > one.score + MARGIN -> split
            else -> one
        } ?: return null
        DecodeTrace.log {
            "  lattice ${top.words.joinToString("+")}:${top.score} one=${one?.words?.firstOrNull()}:${one?.score} " +
                "cuts=${cuts.joinToString(",") { "$it@${gapBefore(sorted, it)}ms" }}"
        }
        return Result(top, one)
    }
}
