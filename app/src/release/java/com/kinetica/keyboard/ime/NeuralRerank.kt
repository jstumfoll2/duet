package com.kinetica.keyboard.ime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.engine.CandidateReranker
import com.kinetica.keyboard.engine.CtcScorer
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.settings.Prefs

/**
 * Release build: the new decoder ([NeuralStack]) unless Settings switches it back to the
 * classic one ([Prefs.NEURAL_DECODER]).
 *
 * The developer build switches each part on its traces screen instead. Off here builds exactly
 * the classic decoder: the legacy score, no reranker and a [KineticaConstants.TOP_K] heap.
 */
object NeuralRerank {

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var onChange: (() -> Unit)? = null
    private var split: ByteArray? = null
    private var duo: ByteArray? = null

    // Held here: SharedPreferences keeps only a weak reference to its listeners.
    // The switch rebuilds the predictors; their install applies the engine switches with them,
    // so a decode never runs the new switches on the old predictors for the length of a reload.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.NEURAL_DECODER) onChange?.invoke()
    }

    private val on: Boolean
        get() = prefs?.getBoolean(Prefs.NEURAL_DECODER, Prefs.DEFAULT_NEURAL_DECODER) ?: Prefs.DEFAULT_NEURAL_DECODER

    @Volatile private var warned = false

    /** Main thread only, like the IME. [onChange] rebuilds the keyboard's predictors. */
    fun install(context: Context, onChange: (() -> Unit)?) {
        this.onChange = onChange
        if (prefs == null) {
            appContext = context.applicationContext
            prefs = PreferenceManager.getDefaultSharedPreferences(context).also {
                it.registerOnSharedPreferenceChangeListener(listener)
            }
        }
    }

    fun detach() {
        onChange = null
    }

    /** The models parsed for one predictor build, off the main thread ([prepare]). */
    class Prepared internal constructor(
        internal val on: Boolean,
        internal val split: CtcScorer?,
        internal val duo: CtcScorer?,
    )

    /**
     * Reads and parses both models (about 1.1 MB, tens of ms on a phone). Call it on the
     * dictionary load thread: [primary] then only assembles, on the main thread.
     */
    fun prepare(): Prepared {
        val ctx = appContext
        if (!on || ctx == null) return Prepared(false, null, null)
        val s = NeuralStack.scorer(split ?: NeuralStack.read(ctx, NeuralStack.SPLIT_ASSET)?.also { split = it }, duo = false)
        val d = NeuralStack.scorer(duo ?: NeuralStack.read(ctx, NeuralStack.DUO_ASSET)?.also { duo = it }, duo = true)
        return Prepared(true, s, d)
    }

    /**
     * The active language's predictor: [make] with both models, or the classic
     * `make(null, TOP_K)`. Applies the matching engine switches. When either model is missing
     * or unreadable the whole classic decoder runs, never the new score without its models (a
     * mix nobody measured).
     */
    fun primary(prepared: Prepared, make: (CandidateReranker?, Int) -> WordPredictor): WordPredictor {
        val s = prepared.split
        val d = prepared.duo
        if (!prepared.on || s == null || d == null) {
            if (prepared.on && !warned) {
                warned = true
                Log.e(TAG, "neural decoder models unavailable (split=${s != null} duo=${d != null}); classic decoder")
            }
            NeuralStack.applyScore(false)
            return make(null, KineticaConstants.TOP_K)
        }
        NeuralStack.applyScore(true)
        var built: WordPredictor? = null
        val r = NeuralStack.reranker(s, d, { built?.geometry })!!
        return make(r, NeuralStack.DEPTH).also { built = it }
    }

    fun spacebarTag(): String? = null

    private const val TAG = "KineticaNeural"
}
