package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tanh

/**
 * [CtcScorer] sums each layer input by input over weights turned into columns, and skips inputs
 * that are exactly zero. Here the same network is run the plain way, output by output, on random
 * weights: every log-probability must come out the same to the last bit, because the rerank's
 * measured numbers were taken with the plain form.
 */
class CtcScorerColumnsTest {

    private val g = TestData.qwertyGeometry()
    private val width = 6
    private val hidden = 5
    private val inDim = DuoFeatures.IN_DIM
    private val n = DuoFeatures.FRAMES

    private val shapes = listOf(
        intArrayOf(width, inDim, 5), intArrayOf(width), intArrayOf(width, width, 5), intArrayOf(width),
        intArrayOf(3 * hidden, width), intArrayOf(3 * hidden, hidden), intArrayOf(3 * hidden), intArrayOf(3 * hidden),
        intArrayOf(3 * hidden, width), intArrayOf(3 * hidden, hidden), intArrayOf(3 * hidden), intArrayOf(3 * hidden),
        intArrayOf(CtcScorer.CLASSES, 2 * hidden), intArrayOf(CtcScorer.CLASSES),
    )

    private val weights: List<FloatArray> = Random(7).let { r ->
        shapes.map { s -> FloatArray(s.fold(1) { a, b -> a * b }) { (r.nextFloat() - 0.5f) } }
    }

    private fun model(): CtcScorer {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun float(v: Float) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
        out.write("KCTC".toByteArray(Charsets.US_ASCII))
        int(2); int(shapes.size); float(DuoFeatures.SIGMA_KW); int(n)
        for ((i, s) in shapes.withIndex()) {
            int(s.size); s.forEach { int(it) }
            weights[i].forEach { float(it) }
        }
        return CtcScorer.load(ByteArrayInputStream(out.toByteArray()))
    }

    private fun conv(inp: FloatArray, cin: Int, w: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(n * width)
        for (t in 0 until n) for (o in 0 until width) {
            var s = b[o]
            for (k in 0 until 5) {
                val ti = t + k - 2
                if (ti < 0 || ti >= n) continue
                for (c in 0 until cin) s += w[(o * cin + c) * 5 + k] * inp[ti * cin + c]
            }
            out[t * width + o] = max(s, 0f)
        }
        return out
    }

    private fun sigmoid(v: Float) = 1f / (1f + exp(-v))

    private fun gru(h2: FloatArray, base: Int, forward: Boolean, hs: FloatArray) {
        val wih = weights[base]; val whh = weights[base + 1]; val bih = weights[base + 2]; val bhh = weights[base + 3]
        val prev = FloatArray(hidden)
        val off = if (forward) 0 else hidden
        for (step in 0 until n) {
            val t = if (forward) step else n - 1 - step
            val a = FloatArray(3 * hidden)
            val b = FloatArray(3 * hidden)
            for (r in 0 until 3 * hidden) {
                var x = bih[r]
                for (c in 0 until width) x += wih[r * width + c] * h2[t * width + c]
                a[r] = x
                var y = bhh[r]
                for (c in 0 until hidden) y += whh[r * hidden + c] * prev[c]
                b[r] = y
            }
            val now = FloatArray(hidden)
            for (j in 0 until hidden) {
                val rg = sigmoid(a[j] + b[j])
                val zg = sigmoid(a[hidden + j] + b[hidden + j])
                val ng = tanh(a[2 * hidden + j] + rg * b[2 * hidden + j])
                now[j] = (1f - zg) * ng + zg * prev[j]
                hs[t * 2 * hidden + off + j] = now[j]
            }
            now.copyInto(prev)
        }
    }

    private fun plain(x: FloatArray): FloatArray {
        val h2 = conv(conv(x, inDim, weights[0], weights[1]), width, weights[2], weights[3])
        val hs = FloatArray(n * 2 * hidden)
        gru(h2, 4, true, hs)
        gru(h2, 8, false, hs)
        val out = FloatArray(n * CtcScorer.CLASSES)
        for (t in 0 until n) {
            var mx = Float.NEGATIVE_INFINITY
            for (c in 0 until CtcScorer.CLASSES) {
                var s = weights[13][c]
                for (k in 0 until 2 * hidden) s += weights[12][c * 2 * hidden + k] * hs[t * 2 * hidden + k]
                out[t * CtcScorer.CLASSES + c] = s
                if (s > mx) mx = s
            }
            var sum = 0.0
            for (c in 0 until CtcScorer.CLASSES) sum += exp((out[t * CtcScorer.CLASSES + c] - mx).toDouble())
            val lse = mx + ln(sum).toFloat()
            for (c in 0 until CtcScorer.CLASSES) out[t * CtcScorer.CLASSES + c] -= lse
        }
        return out
    }

    @Test fun columnsGiveThePlainSumsBitForBit() {
        // One thumb swipes while the other taps late, so some frames have a thumb up (all zeros).
        val tokens = listOf(
            TestData.swipe("hello", g, 0L, 600L, StreamId.RIGHT),
            TestData.tap('a', g, 400L, StreamId.LEFT),
        )
        val strokes = DuoFeatures.strokes(tokens)
        val want = plain(DuoFeatures.of(strokes, g))
        val m = model()
        // Twice: the scratch buffers of the first run must not leak into the second.
        m.logProbsDuo(strokes, g)
        val got = m.logProbsDuo(strokes, g)
        assertEquals(want.size, got.size)
        for (i in want.indices) assertEquals("value $i", want[i].toRawBits(), got[i].toRawBits())
    }

    @Test fun theLinearSumIsTheLogSum() {
        val tokens = listOf(
            TestData.swipe("hello", g, 0L, 600L, StreamId.RIGHT),
            TestData.tap('a', g, 400L, StreamId.LEFT),
        )
        val lp = model().logProbsDuo(DuoFeatures.strokes(tokens), g)
        val probs = CtcScorer.probabilities(lp)
        for (word in listOf("a", "hello", "aa", "mississippi", "zzzzzzzzzzzzzzzzzzzz")) {
            val labels = IntArray(word.length) { CtcScorer.classOf(word[it] - 'a') }
            val want = CtcScorer.nll(lp, n, labels)
            assertEquals(word, want, CtcScorer.nllLinear(probs, n, labels), 1e-3f * maxOf(1f, want))
        }
        // More letters than the frames can hold: no path, in either form.
        val long = IntArray(n + 1) { 1 + it % 2 }
        assertEquals(Float.POSITIVE_INFINITY, CtcScorer.nll(lp, n, long), 0f)
        assertEquals(Float.POSITIVE_INFINITY, CtcScorer.nllLinear(probs, n, long), 0f)
    }
}
