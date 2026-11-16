package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.ScoreTerms
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import kotlin.math.exp
import kotlin.math.ln

/**
 * The decoder's candidates scored by the two-thumb encoder (decoder rework step 11, issue #134):
 * `score x exp(-beta x cost)`, cost the CTC negative log-likelihood of the word's letters under
 * the encoder's frames for the whole buffer ([DuoFeatures]), per letter.
 *
 * Unlike [CtcReranker], which scores each stroke against the letters a segmentation or an
 * interleaved reading gives it, this needs no reading: the encoder saw both thumbs in time, so
 * the word is scored against the buffer as it was typed. [inner] runs first when given (the
 * one-stroke rerank), so the two stack.
 *
 * Buffers of one stroke are left to [inner]: over the held-out half the encoder moved as many
 * one-swipe words wrong as right. At [beta] = 0 this is [inner] alone.
 *
 * Log-linear score only ([ScoreModel]): there a candidate's score is a probability the cost can
 * be weighed against. On the legacy score the same beta took true-label words 62.9 -> 66.3% but
 * all words 82.5 -> 79.8% on the held-out half, so there this is [inner] alone too.
 *
 * Held out (trace files from 2026-10-22, beta fitted on the files before), log-linear score with
 * tap likelihood and speed-aware shape, on top of the one-stroke rerank, model `futo_duo_v1`:
 * true-label words 65.9 -> 70.1% top-1, words with context 36.8 -> 42.9%, all words
 * 80.6 -> 82.4%, two thumbs at once 79.8 -> 83.7%. JVM decode p50 1.1 -> 2.9 ms, p95 17.5 -> 28.2.
 *
 * Not thread-safe ([CtcScorer]'s scratch buffers): one per decode thread.
 */
class DuoReranker(
    private val scorer: CtcScorer,
    private val geometry: () -> KeyboardGeometry?,
    @Volatile var beta: Float,
    private val inner: CandidateReranker? = null,
) : CandidateReranker {

    init { require(scorer.duo) { "DuoReranker needs a two-thumb encoder" } }

    /**
     * The encoder is skipped when the leader beats the runner-up by this much (ln of the score
     * ratio) or more. It costs about 10 ms a buffer on a JVM; at 1.5 it runs on a third of the
     * buffers and keeps the gain (held-out leads 81.59% against 81.65% ungated, 78.9% without).
     */
    @Volatile var gate = 1.5f

    /** Whether buffers of taps alone are scored too. */
    @Volatile var taps = true

    override fun rerank(tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate> =
        duo(tokens, inner?.takeIf { it.reranks(false) }?.rerank(tokens, candidates) ?: candidates)

    override fun rerankOverlapped(il: Interleave, tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate> =
        duo(tokens, inner?.takeIf { it.reranks(true) }?.rerankOverlapped(il, tokens, candidates) ?: candidates)

    override fun reranks(overlapped: Boolean): Boolean = beta != 0f || inner?.reranks(overlapped) == true

    override fun deepHeap(overlapped: Boolean): Boolean = inner?.takeIf { it.reranks(overlapped) }?.deepHeap(overlapped) ?: false

    override fun usesSegmentation(overlapped: Boolean): Boolean =
        inner?.takeIf { it.reranks(overlapped) }?.usesSegmentation(overlapped) ?: false

    private fun applies(tokens: List<InputToken>): Boolean = when {
        tokens.size < 2 -> false
        tokens.any { it is TapToken && it.apostrophe } || tokens.any { it is SwipeToken && it.apostrophe } -> false
        tokens.all { it is TapToken } -> taps
        else -> true
    }

    private fun duo(tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate> {
        val beta = beta
        if (beta == 0f || !ScoreModel.logLinear || candidates.size < 2 || !applies(tokens)) return candidates
        if (candidates[0].score > 0f && candidates[1].score > 0f && ln(candidates[0].score / candidates[1].score) >= gate) return candidates
        val g = geometry() ?: return candidates
        val lp = scorer.logProbsDuo(DuoFeatures.strokes(tokens), g)
        return candidates.map { c ->
            val cost = cost(lp, c.word, g) ?: return@map c
            c.copy(score = c.score * exp(-beta * cost), terms = c.terms.plusLog(ScoreTerms.Term.CTC, -beta * cost))
        }.sortedByDescending { it.score }
    }

    /** Mean per-letter CTC cost of [word] under [lp], or null when it has no letter the encoder knows. */
    private fun cost(lp: FloatArray, word: String, g: KeyboardGeometry): Float? {
        val folded = AccentFolder.fold(word.lowercase())
        val labels = IntArray(folded.length)
        var n = 0
        for (ch in folded) {
            val code = Alphabet.codeOf(ch)
            if (code in 0 until Alphabet.LETTERS && g.hasKey(code)) labels[n++] = CtcScorer.classOf(code)
        }
        if (n == 0) return null
        val nll = CtcScorer.nll(lp, scorer.frames, labels.copyOf(n))
        return if (nll.isInfinite()) MAX_LETTER_COST else nll / n
    }

    private companion object {
        /** Per-letter cost charged to a word the frames cannot hold. */
        const val MAX_LETTER_COST = 20f
    }
}
