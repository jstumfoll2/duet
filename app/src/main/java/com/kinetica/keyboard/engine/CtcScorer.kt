package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.ScoreTerms
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tanh

/**
 * A small CTC swipe encoder, run from scratch in plain Kotlin: two 1-D
 * convolutions, one bidirectional GRU, a linear layer to blank + a..z +
 * apostrophe. Trained by tools/experiment/swipe_ctc.py, which defines the same
 * graph and writes the "KCTC" weight file read here.
 *
 * Input is one swipe piece's resampled path (RESAMPLE_N points, kw). Each point
 * becomes a gaussian proximity to every letter key centre plus its step from
 * the previous point, so the encoder sees letters, not coordinates, and any
 * layout's geometry works without retraining.
 *
 * Not thread-safe: scratch buffers are reused. One instance per decode thread.
 */
class CtcScorer private constructor(
    private val sigmaKw: Float,
    private val conv1W: FloatArray, private val conv1B: FloatArray,
    private val conv2W: FloatArray, private val conv2B: FloatArray,
    private val gru: Array<FloatArray>,      // ih, hh, bih, bhh, then the reverse direction
    private val outW: FloatArray, private val outB: FloatArray,
    private val width: Int,
    private val hidden: Int,
    /** Frames per input: [KineticaConstants.RESAMPLE_N] for a stroke, [DuoFeatures.FRAMES] for a buffer. */
    val frames: Int = KineticaConstants.RESAMPLE_N,
    private val inDim: Int = IN_DIM,
) {
    private val n = frames
    private val x = FloatArray(n * inDim)

    /** Whether this is the two-thumb encoder, which reads a whole buffer ([logProbsDuo]), not a stroke. */
    val duo: Boolean get() = inDim == DuoFeatures.IN_DIM

    private val h1 = FloatArray(n * width)
    private val h2 = FloatArray(n * width)
    private val hs = FloatArray(n * 2 * hidden)
    // Scratch per GRU direction, so the two directions can run on two threads ([split]).
    private val gates = Array(2) { FloatArray(3 * hidden) }
    private val gatesH = Array(2) { FloatArray(3 * hidden) }
    private val hPrev = Array(2) { FloatArray(hidden) }

    /** Log-probabilities, [RESAMPLE_N * CLASSES], for one resampled piece on [g]. */
    fun logProbs(resampled: FloatArray, g: KeyboardGeometry): FloatArray {
        require(!duo) { "a two-thumb encoder reads buffers" }
        features(resampled, g)
        return run()
    }

    /** Log-probabilities, [frames] x [CLASSES], for a whole buffer's [strokes] on [g] (the two-thumb encoder). */
    fun logProbsDuo(strokes: List<DuoFeatures.Stroke>, g: KeyboardGeometry): FloatArray {
        require(duo) { "a one-stroke encoder reads resampled strokes" }
        DuoFeatures.of(strokes, g).copyInto(x)
        return run()
    }

    /**
     * [logProbsDuo] for a caller that runs it on a thread of its own and may give up on the
     * answer: one run at a time across every such caller of this scorer, and an interrupt of
     * the running thread ends the run early with a [CancellationException].
     */
    fun logProbsDuoCancellable(strokes: List<DuoFeatures.Stroke>, g: KeyboardGeometry): FloatArray = synchronized(this) {
        require(duo) { "a one-stroke encoder reads resampled strokes" }
        DuoFeatures.of(strokes, g).copyInto(x)
        stops = true
        try {
            run(split)
        } finally {
            stops = false
        }
    }

    // Whether the run in progress ends at an interrupt ([logProbsDuoCancellable]).
    private var stops = false

    private fun checkStop() {
        if (stops && Thread.interrupted()) throw CancellationException("encoder run given up")
    }

    private fun run(parallel: Boolean = false): FloatArray {
        if (parallel) {
            val half = n / 2
            both({ conv(x, inDim, conv1T, conv1B, h1, 0, half, acc[0]) }, { conv(x, inDim, conv1T, conv1B, h1, half, n, acc[1]) })
            checkStop()
            both({ conv(h1, width, conv2T, conv2B, h2, 0, half, acc[0]) }, { conv(h1, width, conv2T, conv2B, h2, half, n, acc[1]) })
            checkStop()
            both({ gruPass(forward = true) }, { gruPass(forward = false) })
        } else {
            conv(x, inDim, conv1T, conv1B, h1)
            checkStop()
            conv(h1, width, conv2T, conv2B, h2)
            checkStop()
            gruPass(forward = true)
            checkStop()
            gruPass(forward = false)
        }
        val out = FloatArray(n * CLASSES)
        for (t in 0 until n) {
            var mx = Float.NEGATIVE_INFINITY
            for (c in 0 until CLASSES) {
                var s = outB[c]
                val wRow = c * 2 * hidden
                val hRow = t * 2 * hidden
                for (k in 0 until 2 * hidden) s += outW[wRow + k] * hs[hRow + k]
                out[t * CLASSES + c] = s
                if (s > mx) mx = s
            }
            var sum = 0.0
            for (c in 0 until CLASSES) sum += exp((out[t * CLASSES + c] - mx).toDouble())
            val lse = mx + ln(sum).toFloat()
            for (c in 0 until CLASSES) out[t * CLASSES + c] -= lse
        }
        return out
    }

    /**
     * [mine] on this thread and [theirs] on [helper], both done before it returns. The two
     * write disjoint parts of the scratch arrays. An interrupt of this thread while it waits
     * still lets [theirs] finish first, so no run is left writing into the scratch.
     */
    private fun both(mine: () -> Unit, theirs: () -> Unit) {
        val other = helper.submit(Runnable { theirs() })
        try {
            mine()
        } finally {
            var interrupted = false
            while (true) {
                try {
                    other.get()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw (e.cause ?: e)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun features(p: FloatArray, g: KeyboardGeometry) {
        val inv = 1f / (2f * sigmaKw * sigmaKw)
        for (t in 0 until n) {
            val px = p[2 * t]
            val py = p[2 * t + 1]
            for (k in 0 until Alphabet.LETTERS) {
                x[t * IN_DIM + k] = if (g.hasKey(k)) {
                    val dx = px - g.centerX(k)
                    val dy = py - g.centerY(k)
                    exp(-(dx * dx + dy * dy) * inv)
                } else {
                    0f
                }
            }
            x[t * IN_DIM + 26] = if (t == 0) 0f else px - p[2 * (t - 1)]
            x[t * IN_DIM + 27] = if (t == 0) 0f else py - p[2 * (t - 1) + 1]
        }
    }

    // The weights turned so that everything one input value feeds lies side by side: a sum over
    // inputs becomes "add this input times its column" for each input, in the same input order
    // as before, so every output is the same sum of the same terms. A column whose input is
    // exactly zero is skipped: a thumb that is up is all zeros, and so is about half of what a
    // ReLU puts out.
    private val conv1T = convColumns(conv1W, inDim)
    private val conv2T = convColumns(conv2W, width)
    private val gruT = Array(4) { i -> columns(gru[if (i < 2) i else i + 2], 3 * hidden, if (i % 2 == 0) width else hidden) }
    private val acc = Array(2) { FloatArray(width) }

    /**
     * Whether [logProbsDuoCancellable] splits a run across its caller and one helper thread:
     * each convolution by halves of time, the GRU by direction. Every output is the same sum of
     * the same terms in the same order as on one thread, so the frames are bit for bit the same.
     */
    @Volatile var split = true

    // The helper thread for [split] runs, made on first use; a daemon that ends when idle.
    private val helper by lazy {
        java.util.concurrent.ThreadPoolExecutor(1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()) { r ->
            Thread(r, "kinetica-ctc-helper").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    /** [w] as [cout, cin, KERNEL] turned to [KERNEL, cin, cout]. */
    private fun convColumns(w: FloatArray, cin: Int): FloatArray {
        val out = FloatArray(w.size)
        for (o in 0 until width) for (c in 0 until cin) for (k in 0 until KERNEL) {
            out[(k * cin + c) * width + o] = w[(o * cin + c) * KERNEL + k]
        }
        return out
    }

    /** [w] as [rows, cols] turned to [cols, rows]. */
    private fun columns(w: FloatArray, rows: Int, cols: Int): FloatArray {
        val out = FloatArray(w.size)
        for (r in 0 until rows) for (c in 0 until cols) out[c * rows + r] = w[r * cols + c]
        return out
    }

    /** Conv1d, kernel 5, padding 2, then ReLU. [inp] is time-major [n * cin]; [wT] from [convColumns]. */
    private fun conv(inp: FloatArray, cin: Int, wT: FloatArray, b: FloatArray, out: FloatArray, from: Int = 0, until: Int = n, acc: FloatArray = this.acc[0]) {
        val width = width
        for (t in from until until) {
            System.arraycopy(b, 0, acc, 0, width)
            for (k in 0 until KERNEL) {
                val ti = t + k - KERNEL / 2
                if (ti < 0 || ti >= n) continue
                val iBase = ti * cin
                val wBase = k * cin * width
                for (c in 0 until cin) {
                    val v = inp[iBase + c]
                    if (v == 0f) continue
                    val col = wBase + c * width
                    for (o in 0 until width) acc[o] += wT[col + o] * v
                }
            }
            val oBase = t * width
            for (o in 0 until width) out[oBase + o] = max(acc[o], 0f)
        }
    }

    /** One direction of PyTorch's GRU (gate order r, z, n) into its half of [hs]. */
    private fun gruPass(forward: Boolean) {
        val base = if (forward) 0 else 4
        val wihT = gruT[if (forward) 0 else 2]
        val whhT = gruT[if (forward) 1 else 3]
        val bih = gru[base + 2]
        val bhh = gru[base + 3]
        val rows = 3 * hidden
        val d = if (forward) 0 else 1
        val gates = gates[d]
        val gatesH = gatesH[d]
        val hPrev = hPrev[d]
        hPrev.fill(0f)
        val off = if (forward) 0 else hidden
        for (step in 0 until n) {
            val t = if (forward) step else n - 1 - step
            System.arraycopy(bih, 0, gates, 0, rows)
            for (c in 0 until width) {
                val v = h2[t * width + c]
                if (v == 0f) continue
                val col = c * rows
                for (r in 0 until rows) gates[r] += wihT[col + r] * v
            }
            System.arraycopy(bhh, 0, gatesH, 0, rows)
            for (c in 0 until hidden) {
                val v = hPrev[c]
                if (v == 0f) continue
                val col = c * rows
                for (r in 0 until rows) gatesH[r] += whhT[col + r] * v
            }
            for (j in 0 until hidden) {
                val rg = sigmoid(gates[j] + gatesH[j])
                val zg = sigmoid(gates[hidden + j] + gatesH[hidden + j])
                val ng = tanh(gates[2 * hidden + j] + rg * gatesH[2 * hidden + j])
                val h = (1f - zg) * ng + zg * hPrev[j]
                hs[t * 2 * hidden + off + j] = h
            }
            for (j in 0 until hidden) hPrev[j] = hs[t * 2 * hidden + off + j]
        }
    }

    companion object {
        const val CLASSES = 28
        private const val IN_DIM = 28
        private const val KERNEL = 5

        /** Largest single dimension and tensor (floats) a model may declare; the shipped ones are far below. */
        private const val MAX_DIM = 4096
        private const val MAX_TENSOR = 4_000_000L

        private fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))

        /** CTC class of a letter code: a..z are 1..26, the apostrophe 27. */
        fun classOf(code: Int): Int = if (code == Alphabet.APOSTROPHE) 27 else code + 1

        /**
         * Negative log-likelihood of [labels] (CTC classes, no blanks) under
         * [lp] ([frames] x [CLASSES] log-probabilities): the CTC forward pass.
         * +Infinity when the label cannot fit in the frames.
         */
        fun nll(lp: FloatArray, frames: Int, labels: IntArray): Float {
            val s = 2 * labels.size + 1
            var alpha = DoubleArray(s) { Double.NEGATIVE_INFINITY }
            var next = DoubleArray(s)
            fun ext(i: Int) = if (i % 2 == 0) 0 else labels[i / 2]
            alpha[0] = lp[0].toDouble()
            if (s > 1) alpha[1] = lp[ext(1)].toDouble()
            for (t in 1 until frames) {
                for (i in 0 until s) {
                    var a = alpha[i]
                    if (i >= 1) a = logAdd(a, alpha[i - 1])
                    if (i >= 2 && i % 2 == 1 && ext(i) != ext(i - 2)) a = logAdd(a, alpha[i - 2])
                    next[i] = a + lp[t * CLASSES + ext(i)]
                }
                val tmp = alpha; alpha = next; next = tmp
            }
            val end = if (s > 1) logAdd(alpha[s - 1], alpha[s - 2]) else alpha[s - 1]
            return if (end == Double.NEGATIVE_INFINITY) Float.POSITIVE_INFINITY else (-end).toFloat()
        }

        /** [lp] as plain probabilities, for [nllLinear]. */
        fun probabilities(lp: FloatArray): DoubleArray = DoubleArray(lp.size) { exp(lp[it].toDouble()) }

        /**
         * [nll] from [probs] ([probabilities] of the same frames), summed as probabilities and
         * rescaled each frame so nothing underflows. The same number to about twelve digits, at
         * a fraction of the cost: [nll] takes a logarithm and two exponentials for every sum.
         */
        fun nllLinear(probs: DoubleArray, frames: Int, labels: IntArray): Float {
            val s = 2 * labels.size + 1
            var alpha = DoubleArray(s)
            var next = DoubleArray(s)
            val ext = IntArray(s) { if (it % 2 == 0) 0 else labels[it / 2] }
            alpha[0] = probs[0]
            if (s > 1) alpha[1] = probs[ext[1]]
            var logScale = 0.0
            for (t in 1 until frames) {
                val row = t * CLASSES
                var total = 0.0
                for (i in 0 until s) {
                    var a = alpha[i]
                    if (i >= 1) a += alpha[i - 1]
                    if (i >= 2 && i % 2 == 1 && ext[i] != ext[i - 2]) a += alpha[i - 2]
                    a *= probs[row + ext[i]]
                    next[i] = a
                    total += a
                }
                if (total <= 0.0) return Float.POSITIVE_INFINITY
                val inv = 1.0 / total
                for (i in 0 until s) next[i] *= inv
                logScale += ln(total)
                val tmp = alpha; alpha = next; next = tmp
            }
            val end = if (s > 1) alpha[s - 1] + alpha[s - 2] else alpha[s - 1]
            return if (end <= 0.0) Float.POSITIVE_INFINITY else (-(ln(end) + logScale)).toFloat()
        }

        private fun logAdd(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            val m = maxOf(a, b)
            return m + ln(exp(a - m) + exp(b - m))
        }

        /** Reads a "KCTC" v1 file as written by swipe_ctc.py's export(). */
        fun load(input: InputStream): CtcScorer {
            val d = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "KCTC") { "not a KCTC file" }
            fun int(): Int = ByteBuffer.wrap(ByteArray(4).also { d.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).int
            fun float(): Float = java.lang.Float.intBitsToFloat(int())
            val version = int()
            require(version == 1 || version == 2) { "unsupported KCTC version $version" }
            val count = int()
            require(count == 14) { "expected 14 tensors, found $count" }
            val sigma = float()
            // Version 2 (duo_ctc.py): the two-thumb encoder, its frame count in the header.
            val frames = if (version == 2) int() else KineticaConstants.RESAMPLE_N
            val inDim = if (version == 2) DuoFeatures.IN_DIM else IN_DIM
            val shapes = ArrayList<IntArray>(count)
            val t = Array(count) {
                val nd = int()
                // Checked before anything is allocated: a bad file must fail here, not as a
                // negative array size or an out-of-bounds read at inference.
                require(nd in 1..3) { "tensor $it has $nd dims" }
                val dims = IntArray(nd) { int() }
                require(dims.all { d -> d in 1..MAX_DIM }) { "tensor $it dims ${dims.toList()}" }
                val wide = dims.fold(1L) { a, b -> a * b }
                require(wide <= MAX_TENSOR) { "tensor $it too large: $wide" }
                shapes.add(dims)
                val size = wide.toInt()
                val bytes = ByteArray(4 * size).also { d.readFully(it) }
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(size).also { fb.get(it) }
            }
            val width = shapes[0][0]
            val hidden = shapes[5][1]
            require(shapes[0][1] == inDim && shapes[0][2] == KERNEL) { "conv1 shape ${shapes[0].toList()}" }
            require(version == 1 || (frames == DuoFeatures.FRAMES && sigma == DuoFeatures.SIGMA_KW)) { "frames $frames sigma $sigma" }
            require(shapes[12][0] == CLASSES && shapes[12][1] == 2 * hidden) { "output shape ${shapes[12].toList()}" }
            // Every tensor's shape, not three of them: conv1, conv2, both GRU directions, output.
            val gates = 3 * hidden
            val expected = listOf(
                intArrayOf(width, inDim, KERNEL), intArrayOf(width),
                intArrayOf(width, width, KERNEL), intArrayOf(width),
                intArrayOf(gates, width), intArrayOf(gates, hidden), intArrayOf(gates), intArrayOf(gates),
                intArrayOf(gates, width), intArrayOf(gates, hidden), intArrayOf(gates), intArrayOf(gates),
                intArrayOf(CLASSES, 2 * hidden), intArrayOf(CLASSES),
            )
            for (i in expected.indices) {
                require(shapes[i].contentEquals(expected[i])) { "tensor $i shape ${shapes[i].toList()}, expected ${expected[i].toList()}" }
            }
            return CtcScorer(
                sigma, t[0], t[1], t[2], t[3],
                arrayOf(t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11]),
                t[12], t[13], width, hidden, frames, inDim,
            )
        }
    }
}

/**
 * Stage A: `score x exp(-beta x cost)`, where cost is the CTC negative
 * log-likelihood of each swipe piece's letters under the encoder, averaged per
 * letter so long and short words pay on one scale. Tap-anchored letters carry no
 * cost; a candidate with no swipe piece is left as it is.
 *
 * At [beta] = 0 the list comes back untouched, the same objects in the same
 * order, which is what makes the term safe to ship dark.
 */
class CtcReranker(
    private val scorer: CtcScorer,
    private val geometry: () -> KeyboardGeometry?,
    var beta: Float,
) : CandidateReranker {

    /**
     * Beta for one-thumb buffers; NaN uses [beta]. The encoder was trained on one-finger swipes
     * and helps there only lightly: over 20 trace batches a quarter of the two-thumb beta flips
     * 63 true-label words right and 6 wrong, while the full beta also undoes words the decoder
     * had right (`the` -> `three`).
     */
    @Volatile var soloBeta: Float = Float.NaN

    /**
     * Skip the encoder on a one-thumb buffer whose leader beats the runner-up by this much
     * (ln of the score ratio) or more: at [soloBeta] the rerank cannot close such a gap, so the
     * encoder would cost time and change only the tail.
     */
    @Volatile var soloGate: Float = Float.POSITIVE_INFINITY

    /** One-thumb buffers rerank the shipping list itself, not a [rerankDepth]-deep heap. */
    @Volatile var soloShallow: Boolean = false

    private val soloBetaNow: Float get() = if (overlapOnly) 0f else if (soloBeta.isNaN()) beta else soloBeta

    override fun deepHeap(overlapped: Boolean): Boolean = overlapped || !soloShallow

    override fun rerank(tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate> {
        val beta = soloBetaNow
        if (beta == 0f || candidates.isEmpty()) return candidates
        if (candidates.size >= 2 && candidates[0].score > 0f && candidates[1].score > 0f &&
            ln(candidates[0].score / candidates[1].score) >= soloGate
        ) return candidates
        val g = geometry() ?: return candidates
        if (g !== cacheGeometry) {
            encoded.clear()
            cacheGeometry = g
        }
        val cache = IdentityHashMap<FloatArray, FloatArray>()
        val rescored = candidates.map { c ->
            val cost = cost(c, g, cache)
            if (cost == null) c else c.copy(score = c.score * exp(-beta * cost), terms = c.terms.plusLog(ScoreTerms.Term.CTC, -beta * cost))
        }
        return rescored.sortedByDescending { it.score }
    }

    /**
     * Whether two thumbs drawing at once are reranked too ([rerankOverlapped]). Off on the
     * phone until a two-thumb-trained encoder beats the plain order in replay.
     */
    @Volatile var overlap = false

    /** With [overlap]: leave every other buffer in its plain order (the one-thumb rerank is flat). */
    @Volatile var overlapOnly = false

    override fun reranks(overlapped: Boolean): Boolean =
        if (overlapped) beta != 0f && overlap else soloBetaNow != 0f

    // rerankOverlapped reads each stroke's letters off the interleaved reading instead.
    override fun usesSegmentation(overlapped: Boolean): Boolean = !overlapped

    /**
     * [candidates] for two thumbs drawing at once: each candidate's letters are given to the
     * strokes by its best interleaved reading ([Interleave.strokeLetters]) and each swipe is
     * scored on its own letters, the mean per letter as in [cost]. A candidate with no such
     * reading pays the worst cost in the list, so missing evidence never ranks a word up.
     */
    override fun rerankOverlapped(il: Interleave, tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate>? {
        if (!overlap) return null
        if (beta == 0f || candidates.isEmpty()) return candidates
        val g = geometry() ?: return candidates
        val strokes = tokens.sortedBy { it.tStart }
        val costs = candidates.map { c -> interleavedCost(il, strokes, c.word, g) }
        val worst = costs.filterNotNull().maxOrNull() ?: return candidates
        return candidates.mapIndexed { i, c -> c.copy(score = c.score * exp(-beta * (costs[i] ?: worst)), terms = c.terms.plusLog(ScoreTerms.Term.CTC, -beta * (costs[i] ?: worst))) }
            .sortedByDescending { it.score }
    }

    private fun interleavedCost(il: Interleave, strokes: List<InputToken>, word: String, g: KeyboardGeometry): Float? {
        val per = il.strokeLetters(AccentFolder.fold(word.lowercase())) ?: return null
        var total = 0f
        var letters = 0
        for ((s, t) in strokes.withIndex()) {
            if (t !is SwipeToken) continue
            val labels = per[s].filter { it == Alphabet.APOSTROPHE || g.hasKey(it) }.map { CtcScorer.classOf(it) }.toIntArray()
            if (labels.isEmpty()) continue
            val nll = CtcScorer.nll(encode(t.resampled, g), KineticaConstants.RESAMPLE_N, labels)
            total += if (nll.isInfinite()) MAX_LETTER_COST * labels.size else nll
            letters += labels.size
        }
        return if (letters == 0) null else total / letters
    }

    /** Mean per-letter CTC cost over [c]'s swipe pieces, or null when it has none. */
    fun cost(c: WordCandidate, g: KeyboardGeometry, cache: IdentityHashMap<FloatArray, FloatArray>): Float? {
        val seg = c.segmentation ?: return null
        var total = 0f
        var letters = 0
        for (p in seg.pieces) {
            val spelled = p.letters ?: seg.letters
            val labels = (p.from until p.to).map { spelled[it] }
                .filter { it == Alphabet.APOSTROPHE || g.hasKey(it) }
                .map { CtcScorer.classOf(it) }.toIntArray()
            if (labels.isEmpty()) continue
            val lp = cache.getOrPut(p.resampled) { encode(p.resampled, g) }
            val nll = CtcScorer.nll(lp, KineticaConstants.RESAMPLE_N, labels)
            // A label longer than the frames can hold is as bad as it gets, not free.
            total += if (nll.isInfinite()) MAX_LETTER_COST * labels.size else nll
            letters += labels.size
        }
        return if (letters == 0) null else total / letters
    }

    /** A piece's samples compared by content: each decode rebuilds its pieces, the paths repeat. */
    private class PathKey(val xy: FloatArray) {
        private val hash = xy.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is PathKey && xy.contentEquals(other.xy)
    }

    // The composer re-decodes the whole buffer after every token, so without this
    // every swipe is encoded again on every later token of its word.
    private val encoded = object : LinkedHashMap<PathKey, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PathKey, FloatArray>) = size > ENCODED_MAX
    }
    private var cacheGeometry: KeyboardGeometry? = null

    private fun encode(xy: FloatArray, g: KeyboardGeometry): FloatArray =
        synchronized(encoded) {
            encoded[PathKey(xy)] ?: scorer.logProbs(xy, g).also { encoded[PathKey(xy.copyOf())] = it }
        }

    private companion object {
        /** Per-letter cost charged to a piece CTC cannot align at all. */
        const val MAX_LETTER_COST = 20f

        /** Distinct piece paths kept encoded: a long buffer's pieces and cuts fit many times over. */
        const val ENCODED_MAX = 256
    }
}
