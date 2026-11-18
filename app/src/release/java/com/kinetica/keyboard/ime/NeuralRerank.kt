package com.kinetica.keyboard.ime

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.engine.CandidateReranker
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
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.NEURAL_DECODER) {
            NeuralStack.applyScore(on)
            onChange?.invoke()
        }
    }

    private val on: Boolean
        get() = prefs?.getBoolean(Prefs.NEURAL_DECODER, Prefs.DEFAULT_NEURAL_DECODER) ?: Prefs.DEFAULT_NEURAL_DECODER

    /** Main thread only, like the IME. [onChange] rebuilds the keyboard's predictors. */
    fun install(context: Context, onChange: (() -> Unit)?) {
        this.onChange = onChange
        if (prefs == null) {
            appContext = context.applicationContext
            prefs = PreferenceManager.getDefaultSharedPreferences(context).also {
                it.registerOnSharedPreferenceChangeListener(listener)
            }
        }
        NeuralStack.applyScore(on)
    }

    fun detach() {
        onChange = null
    }

    /** The active language's predictor: [make] with both models, or the classic `make(null, TOP_K)`. */
    fun primary(make: (CandidateReranker?, Int) -> WordPredictor): WordPredictor {
        val ctx = appContext
        if (!on || ctx == null) return make(null, KineticaConstants.TOP_K)
        val s = NeuralStack.scorer(split ?: NeuralStack.read(ctx, NeuralStack.SPLIT_ASSET)?.also { split = it }, duo = false)
        val d = NeuralStack.scorer(duo ?: NeuralStack.read(ctx, NeuralStack.DUO_ASSET)?.also { duo = it }, duo = true)
        var built: WordPredictor? = null
        val r = NeuralStack.reranker(s, d, { built?.geometry }) ?: return make(null, KineticaConstants.TOP_K)
        return make(r, if (s != null) NeuralStack.DEPTH else KineticaConstants.TOP_K).also { built = it }
    }

    fun spacebarTag(): String? = null
}
