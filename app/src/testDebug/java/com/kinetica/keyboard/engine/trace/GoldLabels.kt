package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.hypot

/**
 * Labels the replay can trust, read off the trace itself.
 *
 * Three quarters of free-typed labels are decodes the space bar committed, so a wrong word
 * left in the text scores as right and a change that fixes real errors reads as a loss. Two
 * things in a trace say what the user meant regardless of what the keyboard made of it: a
 * practice prompt, and a second attempt. This object finds the second attempts the adjacent
 * retype rule misses (a word swiped again after the wrong one was deleted some other way, or
 * after an abandoned buffer) and the practice lines that are not attempts at their prompt.
 */
object GoldLabels {
    /** Mean distance in kw between two attempts' paths below which they trace the same word. */
    const val SAME_SHAPE_KW = 0.55f

    /** Two attempts further apart in time than this are not one word tried twice. */
    const val CHAIN_GAP_MS = 15_000L

    /** A buffer whose whole path is shorter than this (kw) is taps; taps alone are not chained. */
    const val MIN_ARC_KW = 0.6f

    /** A label letter further than this (kw) from every sample was not touched. */
    const val UNTOUCHED_KW = 1.0f

    private const val POINTS = 16

    /** A buffer as two paths in kw, one per thumb, and when it began; null unless every token is a gesture. */
    class Shape(val left: List<FloatArray>, val right: List<FloatArray>, val start: Long) {
        val arc: Float get() = length(left) + length(right)
    }

    fun shape(w: SwipeTrace.Word): Shape? {
        val kw = w.geometry.keyWidthPx
        val l = ArrayList<FloatArray>()
        val r = ArrayList<FloatArray>()
        var start = Long.MAX_VALUE
        for (t in w.tokens) {
            val g = t as? SwipeTrace.Gesture ?: return null
            if (g.samples.isEmpty()) return null
            start = minOf(start, g.samples.first().t)
            val into = if (g.stream == StreamId.LEFT) l else r
            for (s in g.samples) into.add(floatArrayOf(s.x / kw, s.y / kw))
        }
        return if (start == Long.MAX_VALUE) null else Shape(l, r, start)
    }

    /**
     * Mean distance in kw between [a] and [b], each thumb's path resampled to the same number of
     * points and compared with the same thumb's. Infinite when one used a thumb the other did
     * not, or when a thumb's path is under half the other attempt's length.
     */
    fun distance(a: Shape, b: Shape): Float {
        var total = 0f
        var n = 0
        for ((pa, pb) in listOf(a.left to b.left, a.right to b.right)) {
            if (pa.isEmpty() && pb.isEmpty()) continue
            if (pa.isEmpty() || pb.isEmpty()) return Float.POSITIVE_INFINITY
            val la = length(pa)
            val lb = length(pb)
            if (maxOf(la, lb) > 1f && minOf(la, lb) / maxOf(la, lb) < 0.5f) return Float.POSITIVE_INFINITY
            val ra = resample(pa)
            val rb = resample(pb)
            for (i in 0 until POINTS) total += hypot(ra[i][0] - rb[i][0], ra[i][1] - rb[i][1])
            n += POINTS
        }
        return if (n == 0) Float.POSITIVE_INFINITY else total / n
    }

    private fun length(p: List<FloatArray>): Float {
        var d = 0f
        for (i in 1 until p.size) d += hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1])
        return d
    }

    private fun resample(p: List<FloatArray>): List<FloatArray> {
        val total = length(p)
        if (p.size == 1 || total < 1e-6f) return List(POINTS) { p[0] }
        val out = ArrayList<FloatArray>(POINTS)
        var j = 0
        var before = 0f
        var seg = hypot(p[1][0] - p[0][0], p[1][1] - p[0][1])
        for (k in 0 until POINTS) {
            val s = total * k / (POINTS - 1)
            while (j < p.size - 2 && before + seg < s) {
                before += seg
                j++
                seg = hypot(p[j + 1][0] - p[j][0], p[j + 1][1] - p[j][1])
            }
            val f = if (seg < 1e-9f) 0f else ((s - before) / seg).coerceIn(0f, 1f)
            out.add(floatArrayOf(p[j][0] + f * (p[j + 1][0] - p[j][0]), p[j][1] + f * (p[j + 1][1] - p[j][1])))
        }
        return out
    }

    /**
     * Relabels second attempts in [words], in place: consecutive buffers within [CHAIN_GAP_MS]
     * whose paths lie within [SAME_SHAPE_KW] of each other are tries at one word, and the last
     * one committed (after any bar correction) is what was meant. An earlier try committed as
     * something else, or abandoned, takes that label with `how = "reswiped"`. A chain that ends
     * on a practice prompt is left alone: its lines have their labels. Returns the count.
     */
    fun relabelReswipes(words: MutableList<Pair<Int, SwipeTrace.Word>>): Int {
        val shapes = words.map { shape(it.second) }
        var changed = 0
        var k = 0
        while (k < words.size) {
            val chain = arrayListOf(k)
            while (k + 1 < words.size) {
                val a = shapes[k] ?: break
                val b = shapes[k + 1] ?: break
                if (b.start - a.start > CHAIN_GAP_MS || b.start < a.start || a.arc < MIN_ARC_KW) break
                if (distance(a, b) > SAME_SHAPE_KW) break
                chain.add(++k)
            }
            k++
            if (chain.size < 2) continue
            val last = chain.lastOrNull { words[it].second.committed != null } ?: continue
            if (chain.any { words[it].second.target != null }) continue
            val meant = words[last].second.committed ?: continue
            for (i in chain) {
                if (i == last) continue
                val (n, w) = words[i]
                if (w.committed.equals(meant, ignoreCase = true)) continue
                words[i] = n to w.copy(committed = meant, how = "reswiped")
                changed++
            }
        }
        return changed
    }

    /**
     * Whether a practice line is not an attempt at its prompt: half of the prompt's letters or
     * more (and at least two) lie over [UNTOUCHED_KW] from everything the buffer touched. One
     * tap recorded against a seven-letter word is a piece of a broken-up word or a stray touch,
     * and no decoder could read the prompt from it.
     */
    fun isFragment(w: SwipeTrace.Word): Boolean {
        val label = w.target?.lowercase() ?: return false
        val kw = w.geometry.keyWidthPx
        val pts = ArrayList<FloatArray>()
        for (t in w.tokens) when (t) {
            is SwipeTrace.Gesture -> for (s in t.samples) pts.add(floatArrayOf(s.x / kw, s.y / kw))
            is SwipeTrace.Literal -> {
                val code = (t.token as? TapToken)?.code ?: return false
                val r = w.geometry.keys[code] ?: return false
                pts.add(floatArrayOf((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f))
            }
        }
        if (pts.isEmpty()) return false
        var letters = 0
        var far = 0
        for (ch in label) {
            val code = Alphabet.encode(ch.toString())?.singleOrNull() ?: continue
            val r = w.geometry.keys[code] ?: continue
            if (code == Alphabet.APOSTROPHE) continue
            letters++
            val cx = (r[0] + r[2]) / 2f
            val cy = (r[1] + r[3]) / 2f
            if (pts.none { hypot(it[0] - cx, it[1] - cy) <= UNTOUCHED_KW }) far++
        }
        return far >= maxOf(2, letters / 2)
    }
}
