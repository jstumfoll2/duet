package com.kinetica.keyboard.ime

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.kinetica.keyboard.engine.BoundaryModel
import com.kinetica.keyboard.engine.LatticeDecoder
import com.kinetica.keyboard.engine.PreviousWordFix
import com.kinetica.keyboard.engine.RetypePenalties
import com.kinetica.keyboard.engine.ShapeTemplates
import com.kinetica.keyboard.engine.ScoreModel
import com.kinetica.keyboard.engine.ShapeModel
import com.kinetica.keyboard.engine.CandidateReranker
import com.kinetica.keyboard.engine.CtcReranker
import com.kinetica.keyboard.engine.CtcScorer
import com.kinetica.keyboard.engine.DuoReranker
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.engine.trace.SwipeTrace
import java.io.IOException

/**
 * Developer build: the neural CTC rerank, switchable on the phone.
 *
 * The release build has a stub that always builds today's decoder. Here the
 * trace screen can switch the rerank off for the active language and pick its
 * beta; off builds exactly the predictor the release build does,
 * no reranker and a [KineticaConstants.TOP_K] heap.
 *
 * Practice mode also decodes each finished buffer both ways, on two private
 * predictors kept off the live decode thread, so every prompt scores the two
 * decoders on the same swipe whichever one is live. The models ([NeuralStack]) are
 * trained by the project's train workflows from scratch on FUTO's MIT swipe dataset
 * (no FUTO weights), and ship in both builds from v3.0. futo_split_v1 is futo_v1
 * fine-tuned on those swipes cut at the midline into two-thumb strokes (train_split workflow):
 * overlapped words at beta 0.1, 84.8% vs 83.2% on one half of ten batches, 78.6% vs 78.9%
 * on the other.
 */
object NeuralRerank {

    const val MODEL_ASSET = NeuralStack.SPLIT_ASSET
    const val MODEL_NAME = NeuralStack.SPLIT_NAME

    /**
     * Two-thumb rerank, ten trace batches split in halves: 0.1 was best on one half and
     * second-best on the other (0.2 best there); 0.05 to 0.2 all gained on both.
     */
    const val DEFAULT_BETA = NeuralStack.SPLIT_BETA
    val BETAS = floatArrayOf(0.02f, 0.05f, 0.1f, 0.2f, 0.3f)

    /** Heap depth the rerank reorders, as in the replay harness. */
    const val DEPTH = NeuralStack.DEPTH

    private const val PREFS = "neural_v1"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_BETA = "beta"
    private const val PREF_BOUNDARY = "boundary_model"
    private const val PREF_LOGLINEAR = "score_loglinear"
    private const val PREF_TAPS = "score_taps"
    private const val PREF_SPEED = "shape_speed"
    private const val PREF_REVISE = "previous_word_fix"
    private const val PREF_LATTICE = "lattice_breaks"
    private const val PREF_DUO = "duo_rerank"
    private const val PREF_DUO_AHEAD = "duo_ahead"

    /**
     * The two-thumb encoder ([DuoReranker], decoder rework step 11, #134): trained by the
     * train_duo workflow from scratch on FUTO's MIT swipe dataset redrawn as two thumbs (no FUTO
     * weights). In both builds from v3.0.
     */
    const val DUO_ASSET = NeuralStack.DUO_ASSET

    /**
     * Fitted on the trace files before 2026-10-22 and held out on the rest, with the
     * language-model score, on top of the one-stroke rerank: true-label words 65.9 -> 70.2%
     * top-1, all words 80.6 -> 82.5%, two thumbs at once 79.8 -> 83.8%; 0.3 and 0.8 both lower.
     */
    const val DUO_BETA = NeuralStack.DUO_BETA

    private const val TAG = "KineticaNeural"

    /** Live rerank timings kept for the trace screen's p50/p95. */
    private const val RING = 256

    /** Rows of each arm kept in a trace line. */
    private const val SHOWN = 3

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var model: ByteArray? = null
    private var onChange: (() -> Unit)? = null

    var enabled: Boolean
        // On unless switched off: over 17 trace batches lines with a true label read 63.8% top-1
        // plain and 65.7% reranked (two-thumb words 78.1 -> 83.0%), with aim points and stroke joins on.
        get() = prefs?.getBoolean(PREF_ENABLED, true) == true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_ENABLED, on)?.apply()
            onChange?.invoke()
        }

    var beta: Float
        get() = prefs?.getFloat(PREF_BETA, DEFAULT_BETA) ?: DEFAULT_BETA
        set(b) {
            prefs?.edit()?.putFloat(PREF_BETA, b)?.apply()
            onChange?.invoke()
        }

    /** Beta of the predictor the keyboard is decoding with now: 0 when the rerank is off. */
    @Volatile
    var liveBeta: Float = 0f
        private set

    /**
     * The rerank as replay supports it: two thumbs drawing at once, each stroke scored on the
     * letters the interleaved reading gives it, at [beta]. One-thumb buffers rerank lightly
     * ([SOLO_BETA], the bar's own list, and only when the leader's margin is under [SOLO_GATE]):
     * over 20 trace batches true-label words went 65.4 -> 66.5% top-1 (55 right, 5 wrong), every
     * other bucket level or up. At the full beta the encoder also undid words the decoder had
     * right (`the` -> `three`), mostly on tap+swipe buffers.
     */
    private fun twoThumb(r: CtcReranker): CtcReranker = NeuralStack.twoThumb(r)

    /** One-thumb beta: a quarter of [DEFAULT_BETA]; replay over 20 batches, see [twoThumb]. */
    const val SOLO_BETA = NeuralStack.SOLO_BETA

    /** ln score margin at which a one-thumb leader skips the encoder; no flip in replay needed over 0.2. */
    const val SOLO_GATE = NeuralStack.SOLO_GATE

    /** Main thread only, like the IME. [onChange] rebuilds the keyboard's predictors. */
    fun install(context: Context, onChange: (() -> Unit)?) {
        useSettings(context)
        this.onChange = onChange
    }

    /** The settings alone, for the trace screen, which can open before the keyboard has. */
    fun useSettings(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        BoundaryModel.enabled = boundaryModel
        ScoreModel.mode = if (logLinearScore) ScoreModel.Mode.LOGLINEAR else ScoreModel.Mode.LEGACY
        ScoreModel.taps = tapLikelihood
        ShapeModel.speed = speedShape
        PreviousWordFix.enabled = previousWordFix
        LatticeDecoder.enabled = latticeBreaks
    }

    /**
     * Word boundaries (a tapped word then a swipe, a reopened word typed on) decided by the
     * fitted [BoundaryModel] instead of PeckSplit's ratios (#67). On by default in this build;
     * the release build never turns it on.
     */
    var boundaryModel: Boolean
        get() = prefs?.getBoolean(PREF_BOUNDARY, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_BOUNDARY, on)?.apply()
            BoundaryModel.enabled = on
        }

    /**
     * The log-linear candidate score with a real language model ([ScoreModel], decoder rework
     * step 1, #124). On by default in this build since the 2026-11-18 phone batch (replay of that
     * batch: 80.7 -> 85.0% top-1 with the switches below); the release build keeps the legacy score.
     */
    var logLinearScore: Boolean
        get() = prefs?.getBoolean(PREF_LOGLINEAR, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_LOGLINEAR, on)?.apply()
            ScoreModel.mode = if (on) ScoreModel.Mode.LOGLINEAR else ScoreModel.Mode.LEGACY
        }

    /** Continuous tap likelihood ([ScoreModel.taps], decoder rework step 4, #127). On by default in this build. */
    var tapLikelihood: Boolean
        get() = prefs?.getBoolean(PREF_TAPS, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_TAPS, on)?.apply()
            ScoreModel.taps = on
        }

    /** Speed-weighted shape matching ([ShapeModel.speed], decoder rework step 6, #129). On by default in this build. */
    var speedShape: Boolean
        get() = prefs?.getBoolean(PREF_SPEED, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_SPEED, on)?.apply()
            ShapeModel.speed = on
        }

    /**
     * The previous-word fix ([PreviousWordFix], decoder rework step 7, #130). On by default in this build, and
     * it only acts with the language-model score on.
     */
    var previousWordFix: Boolean
        get() = prefs?.getBoolean(PREF_REVISE, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_REVISE, on)?.apply()
            PreviousWordFix.enabled = on
        }

    /**
     * Word breaks chosen by the lattice ([LatticeDecoder], decoder rework step 10, #133). On by
     * default in this build, and it only acts with the language-model score on.
     */
    var latticeBreaks: Boolean
        get() = prefs?.getBoolean(PREF_LATTICE, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_LATTICE, on)?.apply()
            LatticeDecoder.enabled = on
        }

    /**
     * The decoder's candidates scored by the two-thumb encoder too ([DuoReranker]). On by
     * default in this build: the 2026-11-18 phone batch agreed with replay.
     */
    var duoRerank: Boolean
        get() = prefs?.getBoolean(PREF_DUO, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_DUO, on)?.apply()
            onChange?.invoke()
        }

    /** [DuoReranker.ahead]: the encoder runs beside the search (default) or after it. */
    var duoAhead: Boolean
        get() = prefs?.getBoolean(PREF_DUO_AHEAD, true) ?: true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_DUO_AHEAD, on)?.apply()
            onChange?.invoke()
        }

    /**
     * The decoder switches in force right now, by name, for the trace's `cfg.switches`: which
     * decoder wrote a word line can then be read from the line, not guessed from the decode log.
     */
    fun switchesOn(): List<String> = buildList {
        if (ScoreModel.logLinear) add("loglinear")
        if (ScoreModel.taps) add("taps")
        if (ShapeModel.speed) add("speed")
        if (PreviousWordFix.enabled) add("revise")
        if (LatticeDecoder.enabled) add("lattice")
        if (duoRerank) add(if (duoAhead) "duo" else "duo-after")
        if (BoundaryModel.enabled) add("boundary")
        if (ShapeTemplates.enabled) add("templates")
        if (RetypePenalties.enabled) add("retypes")
    }

    private var duoModel: ByteArray? = null

    /** A fresh two-thumb scorer, or null when the asset is missing or unreadable. */
    private fun duoScorer(): CtcScorer? {
        val bytes = duoModel ?: try {
            appContext?.assets?.open(DUO_ASSET)?.use { it.readBytes() }?.also { duoModel = it }
        } catch (e: IOException) {
            Log.w(TAG, "two-thumb model asset unavailable", e)
            null
        } ?: return null
        return try {
            CtcScorer.load(bytes.inputStream()).takeIf { it.duo }
        } catch (e: Exception) {
            Log.w(TAG, "two-thumb model unreadable", e)
            null
        }
    }

    fun detach() {
        onChange = null
    }

    /** Whether the model asset loads at all; the trace screen says so if not. */
    fun modelAvailable(context: Context): Boolean {
        useSettings(context)
        return scorer() != null
    }

    /** A fresh scorer per user: [CtcScorer] keeps scratch buffers, so one per thread. */
    private fun scorer(): CtcScorer? {
        val bytes = model ?: try {
            appContext?.assets?.open(MODEL_ASSET)?.use { it.readBytes() }?.also { model = it }
        } catch (e: IOException) {
            Log.w(TAG, "neural model asset unavailable", e)
            null
        } ?: return null
        return try {
            CtcScorer.load(bytes.inputStream())
        } catch (e: Exception) {
            Log.w(TAG, "neural model unreadable", e)
            null
        }
    }

    // ----------------------------------------------------- live predictor

    private var factory: ((CandidateReranker?, Int) -> WordPredictor)? = null
    private var live: WordPredictor? = null
    private var plainShadow: WordPredictor? = null
    private var neuralShadow: WordPredictor? = null

    /**
     * Builds the active language's predictor with [make] (a reranker or null, and
     * the heap depth). Off, that is `make(null, TOP_K)`, the release decode.
     */
    fun primary(make: (CandidateReranker?, Int) -> WordPredictor): WordPredictor {
        factory = make
        plainShadow = null
        neuralShadow = null
        val b = beta
        val s = if (enabled) scorer() else null
        val d = if (duoRerank) duoScorer() else null
        liveBeta = if (s == null) 0f else b
        val p = if (s == null && d == null) {
            make(null, KineticaConstants.TOP_K)
        } else {
            var built: WordPredictor? = null
            val inner = s?.let { twoThumb(CtcReranker(it, { built?.geometry }, b)) }
            val r: CandidateReranker = if (d == null) inner!! else DuoReranker(d, { built?.geometry }, DUO_BETA, inner).also { it.ahead = duoAhead }
            make(Timed(r), if (inner != null) DEPTH else KineticaConstants.TOP_K).also { built = it }
        }
        live = p
        return p
    }

    /**
     * Spacebar tag while the rerank is switched on, so a screenshot says which
     * decoder made it. Read from the switch rather than [liveBeta] because the
     * label is redrawn before the rebuilt predictor lands.
     */
    fun spacebarTag(): String? = if (enabled) "NN $beta" else null

    // ------------------------------------------------------- latency

    private val lock = Any()
    private val rerankMs = FloatArray(RING)
    private var rerankCount = 0

    /** p50 and p95 of the live rerank step in ms, and how many were timed; null before any. */
    fun rerankStats(): Triple<Float, Float, Int>? {
        synchronized(lock) {
            val n = minOf(rerankCount, RING)
            if (n == 0) return null
            val sorted = rerankMs.copyOf(n).also { it.sort() }
            return Triple(sorted[(n - 1) / 2], sorted[((n - 1) * 95) / 100], rerankCount)
        }
    }

    private fun recordRerank(ms: Float) {
        synchronized(lock) {
            rerankMs[rerankCount % RING] = ms
            rerankCount++
        }
    }

    /** The rerank step alone, timed on the decode thread. */
    private class Timed(private val inner: CandidateReranker) : CandidateReranker {
        override fun reranks(overlapped: Boolean) = inner.reranks(overlapped)

        override fun usesSegmentation(overlapped: Boolean) = inner.usesSegmentation(overlapped)

        override fun deepHeap(overlapped: Boolean) = inner.deepHeap(overlapped)

        override fun rerank(tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate> {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val out = inner.rerank(tokens, candidates)
            recordRerank((SystemClock.elapsedRealtimeNanos() - t0) / 1e6f)
            return out
        }

        override fun rerankOverlapped(
            il: com.kinetica.keyboard.engine.Interleave,
            tokens: List<InputToken>,
            candidates: List<WordCandidate>,
        ): List<WordCandidate>? {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val out = inner.rerankOverlapped(il, tokens, candidates)
            recordRerank((SystemClock.elapsedRealtimeNanos() - t0) / 1e6f)
            return out
        }
    }

    // ---------------------------------------------------------- A/B

    /** Set while a practice comparison decodes, so the decode-trace sink can drop its lines. */
    val comparing: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** Practice screen: told of each comparison as it is made (main thread). */
    var onCompare: ((SwipeTrace.AB, String) -> Unit)? = null

    /**
     * Decodes a finished practice buffer both ways, on the main thread, with
     * predictors of their own (the live one belongs to the decode thread).
     * Null outside practice, before a dictionary loads, or without the model.
     */
    fun compare(tokens: List<InputToken>, context: List<String>, target: String?): SwipeTrace.AB? {
        if (target == null || tokens.isEmpty()) return null
        val make = factory ?: return null
        val g = live?.geometry ?: return null
        val b = if (liveBeta != 0f) liveBeta else beta
        val plain = plainShadow ?: make(null, KineticaConstants.TOP_K).also { plainShadow = it }
        val neural = neuralShadow ?: run {
            val s = scorer() ?: return null
            var built: WordPredictor? = null
            make(twoThumb(CtcReranker(s, { built?.geometry }, b)), DEPTH).also { built = it; neuralShadow = it }
        }
        plain.geometry = g
        neural.geometry = g
        comparing.set(true)
        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val a = plain.decode(tokens, context)
            val t1 = SystemClock.elapsedRealtimeNanos()
            val n = neural.decode(tokens, context)
            val t2 = SystemClock.elapsedRealtimeNanos()
            val ab = SwipeTrace.AB(
                b, MODEL_NAME,
                SwipeTrace.candidates(a.take(SHOWN)), SwipeTrace.candidates(n.take(SHOWN)),
                (t1 - t0) / 1e6f, (t2 - t1) / 1e6f,
            )
            onCompare?.invoke(ab, target)
            return ab
        } catch (e: RuntimeException) {
            // A comparison must never take the keyboard down with it.
            Log.w(TAG, "practice comparison failed", e)
            return null
        } finally {
            comparing.set(false)
        }
    }
}
