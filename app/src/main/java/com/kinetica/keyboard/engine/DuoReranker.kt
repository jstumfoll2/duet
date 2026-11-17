package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.ScoreTerms
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 * 80.6 -> 82.4%, two thumbs at once 79.8 -> 83.7%. JVM decode p50 1.1 -> 2.9 ms, p95 17.5 -> 28.2
 * as first built; with the encoder run beside the search ([ahead]) and its sums turned into
 * columns, all trace files: p95 14.6 -> 18.3 ms (legacy score 15.3), the same lists.
 *
 * Not thread-safe: one per decode thread. The encoder itself runs on a thread of this object's own.
 */
class DuoReranker(
    private val scorer: CtcScorer,
    private val geometry: () -> KeyboardGeometry?,
    @Volatile var beta: Float,
    private val inner: CandidateReranker? = null,
) : CandidateReranker {

    /**
     * The encoder is skipped when the leader beats the runner-up by this much (ln of the score
     * ratio) or more. It costs about 5 ms a buffer on a JVM (7 before its sums were turned into columns); at 1.5 it runs on a third of the
     * buffers and keeps the gain (held-out leads 81.59% against 81.65% ungated, 78.9% without).
     */
    @Volatile var gate = 1.5f

    /** Whether buffers of taps alone are scored too. */
    @Volatile var taps = true

    /**
     * Run the encoder beside the search, from [prepare], not after it. The encoder needs the
     * tokens and nothing the search finds, so on a second core the buffer waits for the slower
     * of the two, not for both in turn. The price: it then also runs on the buffers [gate]
     * would have skipped (two in three), on that second core.
     */
    @Volatile var ahead = true

    /** The encoder's frames for one buffer on one board, being computed or done. */
    private class Frames(val tokens: List<InputToken>, val g: KeyboardGeometry, val result: Future<FloatArray>)

    // The last buffer's frames: asked for by [prepare], read by [duo], and kept for the next
    // decode of the same tokens (the composer decodes a buffer again when a resting finger drops).
    private var frames: Frames? = null

    // Every run goes through this one thread; the scorer itself lets one run in at a time, since
    // the predictors of several languages share one scorer.
    private val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "kinetica-duo").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    init { require(scorer.duo) { "DuoReranker needs a two-thumb encoder" } }

    override fun prepare(tokens: List<InputToken>) {
        inner?.prepare(tokens)
        if (!ahead || beta == 0f || !ScoreModel.logLinear || !applies(tokens)) return
        framesFor(tokens, geometry() ?: return)
    }

    private fun framesFor(tokens: List<InputToken>, g: KeyboardGeometry): Future<FloatArray> {
        val f = frames
        if (f != null && f.g === g && f.tokens.size == tokens.size && tokens.indices.all { f.tokens[it] === tokens[it] }) return f.result
        f?.result?.cancel(true)
        val strokes = DuoFeatures.strokes(tokens)
        val result = worker.submit(Callable { scorer.logProbsDuoCancellable(strokes, g) })
        frames = Frames(ArrayList(tokens), g, result)
        return result
    }

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
        if (candidates[0].score > 0f && candidates[1].score > 0f && ln(candidates[0].score / candidates[1].score) >= gate) {
            // A clear leader: the frames asked for ahead are not needed. A run still going stops.
            frames?.let { if (!it.result.isDone) { it.result.cancel(true); frames = null } }
            return candidates
        }
        val g = geometry() ?: return candidates
        val lp = try {
            framesFor(tokens, g).get()
        } catch (e: ExecutionException) {
            frames = null
            return candidates
        } catch (e: CancellationException) {
            frames = null
            return candidates
        }
        val probs = CtcScorer.probabilities(lp)
        return candidates.map { c ->
            val cost = cost(probs, c.word, g) ?: return@map c
            c.copy(score = c.score * exp(-beta * cost), terms = c.terms.plusLog(ScoreTerms.Term.CTC, -beta * cost))
        }.sortedByDescending { it.score }
    }

    /** Mean per-letter CTC cost of [word] under [lp], or null when it has no letter the encoder knows. */
    private fun cost(probs: DoubleArray, word: String, g: KeyboardGeometry): Float? {
        val folded = AccentFolder.fold(word.lowercase())
        val labels = IntArray(folded.length)
        var n = 0
        for (ch in folded) {
            val code = Alphabet.codeOf(ch)
            if (code in 0 until Alphabet.LETTERS && g.hasKey(code)) labels[n++] = CtcScorer.classOf(code)
        }
        if (n == 0) return null
        val nll = CtcScorer.nllLinear(probs, scorer.frames, labels.copyOf(n))
        return if (nll.isInfinite()) MAX_LETTER_COST else nll / n
    }

    private companion object {
        /** Per-letter cost charged to a word the frames cannot hold. */
        const val MAX_LETTER_COST = 20f
    }
}
