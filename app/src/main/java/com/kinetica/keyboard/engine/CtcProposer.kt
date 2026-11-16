package com.kinetica.keyboard.engine

import kotlin.math.exp
import kotlin.math.ln

/**
 * Words proposed straight from an encoder's frames: a CTC prefix beam that walks the trie
 * (decoder rework step 11, issue #134; the first slice of it).
 *
 * Every search in the decoder today starts from geometry: which keys a path touched or came
 * near. A word whose letters the path never came near is never scored, whatever a scorer would
 * have said of it. Here the encoder's own frames ([CtcScorer.logProbs]) say which letters are
 * likely where, and the beam reads them as words: a prefix is a trie node, so nothing outside
 * the lexicon is ever built, and a prefix's probability is the sum over every alignment of its
 * letters to the frames (the usual blank / non-blank pair per prefix).
 *
 * Fused with the unigram language model: a prefix is kept by its probability times the best
 * word still below it ([LM_WEIGHT]), a finished word ranked by its own.
 *
 * What this slice is not: the frames are still the one-finger encoder's, one swipe at a time
 * ([frames] sets several strokes end to end); the two-thumb encoder is the rest of #134.
 * Nothing in the decoder calls it, and with this encoder nothing should. Over all trace files
 * (`replay ctcpropose`, 2026-11-16): on one-swipe buffers the beam alone leads with the label
 * on 65.3% (the decoder 77.5%) and holds 25 of the 275 labels the decoder's list lacks; on two
 * thumbs drawing at once, where frames end to end mean nothing, 15 of 276. About 5 ms a buffer.
 */
object CtcProposer {

    /** Prefixes kept per frame. */
    @JvmField var WIDTH = 64

    /** Weight of the unigram log-probability against the frames' log-probability. */
    @JvmField var LM_WEIGHT = 0.3f

    /** A letter less likely than this in a frame (ln) extends no prefix there. */
    @JvmField var MIN_LETTER = -9f

    /** A proposed word: its trie node, the frames' log-probability of its letters, and its rank score. */
    class Proposal(val node: Int, val logP: Float, val score: Float)

    /** Several strokes' frames ([CtcScorer.logProbs] each) set end to end, in the order given. */
    fun frames(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /**
     * The [top] likeliest words of [trie] under [lp] ([frames] x [CtcScorer.CLASSES]
     * log-probabilities), best first.
     */
    fun propose(trie: Trie, lp: FloatArray, frames: Int, top: Int = 20): List<Proposal> {
        // Per prefix (trie node): ln P(ending in blank), ln P(ending in its last letter).
        var cur = HashMap<Int, DoubleArray>()
        cur[trie.root] = doubleArrayOf(0.0, NEG)
        for (t in 0 until frames) {
            val row = t * CtcScorer.CLASSES
            val next = HashMap<Int, DoubleArray>(cur.size * 4)
            for ((node, p) in cur) {
                val both = logAdd(p[0], p[1])
                val same = next.getOrPut(node) { doubleArrayOf(NEG, NEG) }
                same[0] = logAdd(same[0], both + lp[row])
                val last = if (node == trie.root) -1 else trie.letter(node)
                if (last >= 0) same[1] = logAdd(same[1], p[1] + lp[row + CtcScorer.classOf(last)])
                val first = trie.firstChild(node)
                for (i in 0 until trie.childCount(node)) {
                    val child = first + i
                    val code = trie.letter(child)
                    if (code > Alphabet.APOSTROPHE) continue
                    val l = lp[row + CtcScorer.classOf(code)]
                    if (l < MIN_LETTER) continue
                    // A doubled letter needs a blank between its two frames.
                    val from = if (code == last) p[0] else both
                    if (from == NEG) continue
                    val c = next.getOrPut(child) { doubleArrayOf(NEG, NEG) }
                    c[1] = logAdd(c[1], from + l)
                }
            }
            cur = if (next.size <= WIDTH) next else {
                val kept = next.entries.sortedByDescending { (n, p) ->
                    logAdd(p[0], p[1]) + LM_WEIGHT * trie.logProb(trie.maxDescendantFreq(n))
                }
                HashMap<Int, DoubleArray>(WIDTH * 2).also { m -> for (i in 0 until WIDTH) m[kept[i].key] = kept[i].value }
            }
        }
        return cur.entries.asSequence()
            .filter { it.key != trie.root && trie.isWord(it.key) }
            .map { (n, p) ->
                val l = logAdd(p[0], p[1]).toFloat()
                Proposal(n, l, l + LM_WEIGHT * trie.logProb(trie.frequency(n)))
            }
            .sortedByDescending { it.score }
            .take(top)
            .toList()
    }

    private const val NEG = Double.NEGATIVE_INFINITY

    private fun logAdd(a: Double, b: Double): Double {
        if (a == NEG) return b
        if (b == NEG) return a
        val m = maxOf(a, b)
        return m + ln(exp(a - m) + exp(b - m))
    }
}
