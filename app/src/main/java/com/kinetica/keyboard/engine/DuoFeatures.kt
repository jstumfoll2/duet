package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.exp

/**
 * A whole buffer as the two-thumb encoder sees it (decoder rework step 11, issue #134): both
 * thumbs sampled at the same [FRAMES] instants, evenly in time from the first touch to the
 * last lift, so which thumb was where while the other moved is in the input, not left to a
 * search over cuts and hand-overs.
 *
 * Each frame holds, per thumb (left then right), [PER_THUMB] values: a gaussian proximity to
 * every letter key ([SIGMA_KW], as [CtcScorer] has it, so any layout's geometry works), the
 * step from the frame before, and whether the thumb is down. A thumb that is up is all zeros.
 * A tap is a stroke of one point: it is down for the frames its time covers, at least one.
 *
 * `tools/experiment/duo_ctc.py` builds the same frames (`features`); the two must agree.
 */
object DuoFeatures {
    const val FRAMES = 64
    const val PER_THUMB = Alphabet.LETTERS + 3
    const val IN_DIM = 2 * PER_THUMB
    const val SIGMA_KW = 0.5f

    /** One stroke: its thumb (0 left, 1 right), and its samples as x, y (kw) and t (ms), in time order. */
    class Stroke(val thumb: Int, val x: FloatArray, val y: FloatArray, val t: LongArray) {
        val tStart: Long get() = t.first()
        val tEnd: Long get() = t.last()
    }

    fun strokes(tokens: List<InputToken>): List<Stroke> = tokens.sortedBy { it.tStart }.map { tok ->
        val thumb = if (tok.streamId == StreamId.LEFT) 0 else 1
        when (tok) {
            is TapToken -> Stroke(thumb, floatArrayOf(tok.x, tok.x), floatArrayOf(tok.y, tok.y), longArrayOf(tok.tStart, maxOf(tok.tEnd, tok.tStart)))
            is SwipeToken -> {
                val p = tok.rawPath
                Stroke(thumb, FloatArray(p.size) { p[it].x }, FloatArray(p.size) { p[it].y }, LongArray(p.size) { p[it].t })
            }
        }
    }

    /** [FRAMES] x [IN_DIM] values for [strokes] (in start order) on [g]. */
    fun of(strokes: List<Stroke>, g: KeyboardGeometry): FloatArray {
        val out = FloatArray(FRAMES * IN_DIM)
        if (strokes.isEmpty()) return out
        val t0 = strokes.minOf { it.tStart }
        val t1 = strokes.maxOf { it.tEnd }
        val step = maxOf(t1 - t0, 1L).toDouble() / (FRAMES - 1)
        val px = FloatArray(2 * FRAMES)
        val py = FloatArray(2 * FRAMES)
        val down = BooleanArray(2 * FRAMES)
        for (s in strokes) {
            var i = 0
            for (f in 0 until FRAMES) {
                val tf = t0 + f * step
                if (tf < s.tStart - step / 2 || tf >= s.tEnd + step / 2) continue
                val at = tf.coerceIn(s.tStart.toDouble(), s.tEnd.toDouble())
                while (i < s.t.size - 2 && s.t[i + 1] < at) i++
                val a = s.t[i].toDouble()
                val b = s.t[minOf(i + 1, s.t.size - 1)].toDouble()
                val w = if (b > a) ((at - a) / (b - a)).coerceIn(0.0, 1.0).toFloat() else 0f
                val j = minOf(i + 1, s.t.size - 1)
                val k = s.thumb * FRAMES + f
                px[k] = s.x[i] + w * (s.x[j] - s.x[i])
                py[k] = s.y[i] + w * (s.y[j] - s.y[i])
                down[k] = true
            }
        }
        val inv = 1f / (2f * SIGMA_KW * SIGMA_KW)
        for (thumb in 0..1) for (f in 0 until FRAMES) {
            val k = thumb * FRAMES + f
            if (!down[k]) continue
            val base = f * IN_DIM + thumb * PER_THUMB
            for (c in 0 until Alphabet.LETTERS) {
                if (!g.hasKey(c)) continue
                val dx = px[k] - g.centerX(c)
                val dy = py[k] - g.centerY(c)
                out[base + c] = exp(-(dx * dx + dy * dy) * inv)
            }
            if (f > 0 && down[k - 1]) {
                out[base + Alphabet.LETTERS] = px[k] - px[k - 1]
                out[base + Alphabet.LETTERS + 1] = py[k] - py[k - 1]
            }
            out[base + Alphabet.LETTERS + 2] = 1f
        }
        return out
    }
}
