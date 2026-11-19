package com.kinetica.keyboard.ime

import android.content.Context
import android.util.Log
import com.kinetica.keyboard.engine.CandidateReranker
import com.kinetica.keyboard.engine.CtcReranker
import com.kinetica.keyboard.engine.CtcScorer
import com.kinetica.keyboard.engine.DecoderConfig
import com.kinetica.keyboard.engine.DuoReranker
import com.kinetica.keyboard.engine.KeyboardGeometry
import java.io.IOException

/**
 * The decoder both builds ship by default from v3.0: the log-linear score with a real
 * language model, tap likelihood, speed-aware shape matching, the previous-word fix, lattice
 * word breaks, and two neural models over the candidates (issues #81, #167).
 *
 * Both models are trained from scratch by this project's workflows on FUTO's MIT swipe dataset
 * (never FUTO's weights or outputs): `futo_split_v1`, which scores each stroke against the
 * letters a reading gives it, and `futo_duo_v1`, which watches both thumbs in time and scores
 * the whole buffer. On the first phone batch typed with all of it on, replay read 85.0% of
 * words right against 80.7% for the classic decoder.
 *
 * The release build runs this or the classic decoder, by one Settings switch; the developer
 * build switches each part on its traces screen.
 */
object NeuralStack {

    const val SPLIT_ASSET = "ctc/futo_split_v1.kctc"
    const val SPLIT_NAME = "futo_split_v1"
    const val DUO_ASSET = "ctc/futo_duo_v1.kctc"

    /**
     * Two-thumb weight of the one-stroke model. Ten trace batches split in halves: 0.1 was best
     * on one half and second-best on the other; 0.05 to 0.2 all gained on both.
     */
    const val SPLIT_BETA = 0.1f

    /** One-thumb weight of the one-stroke model: a quarter of [SPLIT_BETA]; replay over 20 batches. */
    const val SOLO_BETA = 0.025f

    /** ln score margin at which a one-thumb leader skips the one-stroke model. */
    const val SOLO_GATE = 0.35f

    /**
     * Weight of the two-thumb model. Fitted on the trace files before 2026-10-22, held out on
     * the rest: true-label words 65.9 -> 70.2% top-1, two thumbs at once 79.8 -> 83.8%.
     */
    const val DUO_BETA = 0.5f

    /** Heap depth the one-stroke model reorders, as in the replay harness. */
    const val DEPTH = 50

    private const val TAG = "KineticaNeural"

    /** The engine switches of the new decoder, all on or all off ([on] false is the classic decoder). */
    fun applyScore(on: Boolean) {
        DecoderConfig.set(if (on) DecoderConfig.NEW else DecoderConfig.CLASSIC)
    }

    /** The one-stroke model's settings as replay measured them: light on one thumb, full on two. */
    fun twoThumb(r: CtcReranker): CtcReranker = r.also {
        it.overlap = true
        it.soloBeta = SOLO_BETA
        it.soloGate = SOLO_GATE
        it.soloShallow = true
    }

    /** The bytes of a model asset, or null when it is missing. */
    fun read(context: Context, asset: String): ByteArray? = try {
        context.assets.open(asset).use { it.readBytes() }
    } catch (e: IOException) {
        Log.w(TAG, "model asset $asset unavailable", e)
        null
    }

    /** A fresh scorer from [bytes] (one per decode thread: it keeps scratch buffers), or null. */
    fun scorer(bytes: ByteArray?, duo: Boolean): CtcScorer? {
        bytes ?: return null
        return try {
            CtcScorer.load(bytes.inputStream()).takeIf { it.duo == duo }
        } catch (e: Exception) {
            Log.w(TAG, "model unreadable", e)
            null
        }
    }

    /**
     * Both models over the candidates: the one-stroke model first, then the two-thumb model on
     * top. Either may be null; null when both are.
     */
    fun reranker(split: CtcScorer?, duo: CtcScorer?, geometry: () -> KeyboardGeometry?, beta: Float = SPLIT_BETA): CandidateReranker? {
        val inner = split?.let { twoThumb(CtcReranker(it, geometry, beta)) }
        return if (duo == null) inner else DuoReranker(duo, geometry, DUO_BETA, inner)
    }
}
