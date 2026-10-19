package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * Two-thumb input read as ONE timeline: the model behind [InterleavedSearch].
 *
 * Nintype-style typing runs both thumbs at once. Each thumb swipes through its
 * own letters, and the word's letter order is the order in which EITHER thumb
 * reached its keys - "identify" is the right thumb on i..n..i while the left
 * goes d-e..t..f-y, the two strokes overlapping almost end to end. Sorting
 * tokens by start time and cutting them at a few guessed points
 * ([MergeAlternatives]) cannot recover that order once the letters alternate
 * more than a couple of times; reading positions against time can.
 *
 * Every token (a swipe, or a tap as a still stroke) is a stroke. A reading of a
 * word assigns each letter to one stroke at one moment, such that:
 *  - moments do not go backwards along the word, beyond [ORDER_SLACK_MS] and
 *    then only at [ORDER_PENALTY_PER_MS] - thumbs are not perfectly in step,
 *    and a lift a little before the other thumb's last key is common;
 *  - each stroke's first letter is at its touch-down and its last at its lift
 *    (the ends of a stroke are the most deliberate points of it);
 *  - every stroke carries at least one letter.
 * The cost of a reading is the distance from the visiting thumb to each
 * letter's key centre, kw; a word's cost is the best reading's mean per key
 * visit. A doubled letter is one visit.
 *
 * The dynamic programme runs letter by letter, so the search can extend it one
 * trie edge at a time ([step]). A row is indexed by (stroke states, moment):
 * each stroke is unused, open or closed, base 3, and the moment is the grid
 * index of the latest letter. After the `masks * grid` costs a row holds one
 * flag per stroke-state mask, 1 when that mask's block has a live reading: most
 * masks are dead at any letter, and a dead block is never read, filled or
 * scanned, so its costs may be stale.
 */
class Interleave private constructor(
    val strokes: Int,
    private val times: LongArray,
    private val px: FloatArray,
    private val py: FloatArray,
    private val start: IntArray,
    private val end: IntArray,
    private val geometry: KeyboardGeometry,
    /** Each stroke's arc length in kw, in stroke order (0 for a tap). */
    val arcs: FloatArray,
) {
    val grid: Int = times.size
    val masks: Int = pow3(strokes)
    private val flags: Int = masks * grid
    val rowSize: Int = flags + masks
    private val allClosed = masks - 1

    // Per grid index: the last index within the order slack, for the free part
    // of the reach, and the penalty's time term.
    private val hi = IntArray(grid)
    private val scratchA = FloatArray(grid)
    private val scratchB = FloatArray(grid)
    // Thumb-to-key distance per (letter, stroke, moment), filled the first time
    // a letter is stepped: the search steps the same 26 letters thousands of times.
    private val dist = FloatArray(Alphabet.LETTERS * strokes * grid)
    private val distReady = BooleanArray(Alphabet.LETTERS)

    /** The lowest cost [step] wrote into its last row (INF when it returned false). */
    var lastMin: Float = INF
        private set

    init {
        var j = 0
        for (i in 0 until grid) {
            while (j + 1 < grid && times[j + 1] <= times[i] + ORDER_SLACK_MS) j++
            hi[i] = j
        }
    }

    /** The row before any letter: nothing assigned, no moment yet. */
    fun initialRow(): FloatArray = FloatArray(rowSize) { INF }.also {
        java.util.Arrays.fill(it, flags, rowSize, 0f)
        it[0] = 0f
        it[flags] = 1f
    }

    /**
     * Extends [prev] by the letter [code] into [out]; returns false when no
     * reading survives. [first] marks the word's first letter, which has no
     * moment to follow.
     */
    fun step(prev: FloatArray, code: Int, first: Boolean, out: FloatArray, bound: Float = INF, skip: Boolean = false): Boolean {
        java.util.Arrays.fill(out, flags, rowSize, 0f)
        lastMin = INF
        if (!geometry.hasKey(code)) return false
        val dOff = code * strokes * grid
        if (!distReady[code]) {
            fillDist(code, dOff)
            distReady[code] = true
        }
        var best = INF
        for (m in 0 until masks) {
            val base = m * grid
            // reach[i]: best previous reading that letter at moment i may follow.
            val reach = scratchA
            if (first) {
                if (m != 0) continue
                java.util.Arrays.fill(reach, 0f)
            } else {
                if (prev[flags + m] == 0f) continue
                reachOf(prev, base, reach)
            }
            var p = 1
            for (s in 0 until strokes) {
                val state = (m / p) % 3
                val dBase = dOff + s * grid
                when (state) {
                    0 -> {
                        val g0 = start[s]
                        val g1 = end[s]
                        val r = reach[g0]
                        if (r < INF) {
                            // Opened at touch-down.
                            val v = r + dist[dBase + g0]
                            if (v < INF && v <= bound) {
                                val o = open(out, m + p) + g0
                                if (v < out[o]) out[o] = v
                                if (v < best) best = v
                            }
                            // A one-letter stroke: down and lift on the same key.
                            val v2 = r + 0.5f * (dist[dBase + g0] + dist[dBase + g1])
                            if (v2 < INF && v2 <= bound) {
                                val o2 = open(out, m + 2 * p) + g1
                                if (v2 < out[o2]) out[o2] = v2
                                if (v2 < best) best = v2
                            }
                        }
                    }
                    1 -> {
                        val g0 = start[s]
                        val g1 = end[s]
                        for (i in g0 + 1..g1) {
                            val r = reach[i]
                            if (r >= INF) continue
                            val v = r + dist[dBase + i]
                            if (v >= INF || v > bound) continue
                            // Closed at the lift, or still open.
                            val o = open(out, if (i == g1) m + p else m) + i
                            if (v < out[o]) out[o] = v
                            if (v < best) best = v
                        }
                    }
                }
                p *= 3
            }
        }
        // A letter no thumb reached: the reading keeps its strokes and moment and pays SKIP_KW.
        // Long words typed fast drop a key now and then, and without this one miss kills the word.
        if (skip && !first && SKIP_KW > 0f) {
            for (m in 0 until masks) {
                if (prev[flags + m] == 0f) continue
                val base = m * grid
                for (i in 0 until grid) {
                    val v = prev[base + i] + SKIP_KW
                    if (v >= INF || v > bound) continue
                    val o = open(out, m) + i
                    if (v < out[o]) out[o] = v
                    if (v < best) best = v
                }
            }
        }
        lastMin = best
        return best < INF
    }

    /** The start of mask [m]'s block in [row], cleared to INF the first time a step writes it. */
    private fun open(row: FloatArray, m: Int): Int {
        val base = m * grid
        if (row[flags + m] == 0f) {
            java.util.Arrays.fill(row, base, base + grid, INF)
            row[flags + m] = 1f
        }
        return base
    }

    /** Mean cost per key visit of the best complete reading in [row], or INF. */
    fun finalCost(row: FloatArray, visits: Int): Float {
        var best = INF
        if (row[flags + allClosed] == 0f) return INF
        val base = allClosed * grid
        for (i in 0 until grid) if (row[base + i] < best) best = row[base + i]
        return if (best >= INF || visits == 0) INF else best / visits
    }

    /** Whether any reading in [row] is still alive. */
    fun alive(row: FloatArray): Boolean {
        for (m in 0 until masks) if (row[flags + m] != 0f) return true
        return false
    }

    /** Cost of a whole word (lowercase a-z letters; others make it INF). */
    fun cost(word: String): Float {
        var row = initialRow()
        var next = FloatArray(rowSize)
        var visits = 0
        var prevCode = -1
        for (ch in word) {
            val code = Alphabet.codeOf(ch)
            if (code < 0) return INF
            if (code == prevCode || code == Alphabet.APOSTROPHE) continue
            if (!step(row, code, visits == 0, next, skip = visits >= SKIP_FROM)) return INF
            val t = row; row = next; next = t
            visits++
            prevCode = code
        }
        return finalCost(row, visits)
    }

    /**
     * The centre-to-centre length, kw, of the letters the best reading of [word] puts on each
     * stroke, in stroke order: what a thumb that drew exactly those letters would have
     * travelled. Null when [word] has no reading.
     *
     * Rebuilt by walking the best reading back through its rows. Each step is re-derived with
     * the same arithmetic [step] used, and the closest match is taken at each letter, so a
     * float tie picks one of the equal readings rather than failing.
     */
    fun strokeIdeal(word: String): FloatArray? {
        val codes = ArrayList<Int>(word.length)
        var prevCode = -1
        for (ch in word) {
            val code = Alphabet.codeOf(ch)
            if (code < 0) return null
            if (code == prevCode || code == Alphabet.APOSTROPHE) continue
            codes.add(code)
            prevCode = code
        }
        if (codes.isEmpty()) return null
        val rows = ArrayList<FloatArray>(codes.size + 1)
        rows.add(initialRow())
        for ((k, code) in codes.withIndex()) {
            val next = FloatArray(rowSize)
            if (!step(rows[k], code, k == 0, next)) return null
            rows.add(next)
        }
        val last = rows[codes.size]
        if (last[flags + allClosed] == 0f) return null
        var m = allClosed
        var i = -1
        var v = INF
        for (g in 0 until grid) if (last[m * grid + g] < v) { v = last[m * grid + g]; i = g }
        if (i < 0) return null
        val strokeOf = IntArray(codes.size)
        val reach = FloatArray(grid)
        for (k in codes.size - 1 downTo 0) {
            val prev = rows[k]
            val dOff = codes[k] * strokes * grid
            var bestErr = INF
            var bestS = -1
            var bestM = -1
            var p = 1
            for (st in 0 until strokes) {
                val state = (m / p) % 3
                val d = dOff + st * grid
                // (previous mask, value the letter added) for each way this stroke could have
                // taken the letter at moment i.
                val ways = ArrayList<Pair<Int, Float>>(2)
                if (state == 1 && i == start[st]) ways.add((m - p) to dist[d + i])
                if (state == 1 && i > start[st] && i < end[st]) ways.add(m to dist[d + i])
                if (state == 2 && i == end[st]) {
                    if (end[st] > start[st]) ways.add((m - p) to dist[d + i])
                    ways.add((m - 2 * p) to 0.5f * (dist[d + start[st]] + dist[d + end[st]]))
                }
                for ((pm, add) in ways) {
                    val r = if (k == 0) {
                        if (pm != 0) continue
                        0f
                    } else {
                        if (prev[flags + pm] == 0f) continue
                        reachOf(prev, pm * grid, reach)
                        reach[i]
                    }
                    if (r >= INF) continue
                    val err = kotlin.math.abs(r + add - v)
                    if (err < bestErr) { bestErr = err; bestS = st; bestM = pm }
                }
                p *= 3
            }
            if (bestS < 0) return null
            strokeOf[k] = bestS
            if (k == 0) break
            // The previous letter's moment: the one the reach at i was taken from.
            val base = bestM * grid
            var bj = -1
            var bv = INF
            for (j in 0 until grid) {
                val pv = prev[base + j]
                if (pv >= INF) continue
                val w = if (j <= hi[i]) pv else pv + ORDER_PENALTY_PER_MS * (times[j] - times[i] - ORDER_SLACK_MS)
                if (w < bv) { bv = w; bj = j }
            }
            if (bj < 0) return null
            m = bestM
            i = bj
            v = prev[base + bj]
        }
        val ideal = FloatArray(strokes)
        val lastOn = IntArray(strokes) { -1 }
        for ((k, code) in codes.withIndex()) {
            val st = strokeOf[k]
            val pc = lastOn[st]
            if (pc >= 0) {
                val dx = geometry.centerX(code) - geometry.centerX(pc)
                val dy = geometry.centerY(code) - geometry.centerY(pc)
                ideal[st] += sqrt(dx * dx + dy * dy)
            }
            lastOn[st] = code
        }
        return ideal
    }

    private fun fillDist(code: Int, off: Int) {
        val cx = geometry.centerX(code)
        val cy = geometry.centerY(code)
        for (k in 0 until strokes * grid) {
            val x = px[k]
            dist[off + k] = if (x.isNaN()) INF else {
                val dx = x - cx
                val dy = py[k] - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d > MAX_LETTER_KW) INF else d
            }
        }
    }

    private fun reachOf(prev: FloatArray, base: Int, reach: FloatArray) {
        // Free part: any earlier moment up to the slack, a running minimum.
        val fwd = scratchB
        var run = INF
        for (i in 0 until grid) {
            val v = prev[base + i]
            if (v < run) run = v
            fwd[i] = run
        }
        // Penalised part: moments past the slack, min over j > hi[i] of
        // prev[j] + lambda * (t[j] - t[i] - slack), a suffix minimum of
        // prev[j] + lambda * t[j] shifted by the letter's own time.
        val suffix = reach
        var s = INF
        for (j in grid - 1 downTo 0) {
            val v = prev[base + j]
            if (v < INF) {
                val w = v + ORDER_PENALTY_PER_MS * (times[j] - times[0])
                if (w < s) s = w
            }
            suffix[j] = s
        }
        for (i in 0 until grid) {
            val free = fwd[hi[i]]
            val j = hi[i] + 1
            val pen = if (j < grid && suffix[j] < INF) {
                suffix[j] - ORDER_PENALTY_PER_MS * (times[i] - times[0] + ORDER_SLACK_MS)
            } else {
                INF
            }
            reach[i] = if (free < pen) free else pen
        }
    }

    companion object {
        const val INF = Float.POSITIVE_INFINITY

        /** More strokes than this and the state space (3^n) is not worth it. */
        const val MAX_STROKES = 4

        /**
         * Timeline resolution: union of sample times, thinned to this many (plus stroke ends).
         * Step cost is linear in it. 96 -> 64 on the developer's eight trace batches: the same
         * top-1 on all but two lines (both gained) and about a third less search time.
         */
        const val GRID_MAX = 64

        /** Two thumbs are this far out of step for free. */
        const val ORDER_SLACK_MS = 60L

        /** Beyond the slack, kw of cost per ms a letter comes before its predecessor. */
        const val ORDER_PENALTY_PER_MS = 0.005f

        /** A key further than this from the thumb at its moment was not visited. */
        const val MAX_LETTER_KW = 1.6f

        /**
         * Cost, kw, of reading a letter that no thumb visited (0 = not allowed): the
         * [MAX_LETTER_KW] a visit may cost at most. Before it, 59 of the 77 overlapped 7+ letter
         * words missing from the list (ten trace batches) had no reading at all, one key short.
         * 1.6 from the 7th visit: 10+ letters 64.9 -> 66.5% top-1, recall@50 75.2 -> 77.1%,
         * nothing shorter changed; JVM p99 47 -> 53 ms. From the 5th: the same top-1, p99 70 ms.
         * Vars so replay can sweep them (--skip, --skipfrom).
         */
        @JvmField var SKIP_KW = 1.6f

        /** Key visits a reading needs before it may skip one: early skips multiply the search. */
        @JvmField var SKIP_FROM = 6

        /**
         * The interleaved reading of [tokens], or null when the input is not
         * two-thumb overlapped: fewer than two tokens, more than [MAX_STROKES],
         * or no two SWIPES from different thumbs overlapping in time. A tap held
         * during a swipe is the pecking case the regular search already reads
         * (MergeAlternatives' tap insertion), and reading it here too crowds the
         * merged list - the Italian "sarei" gesture in LanguageDetectGoldenTest.
         */
        fun of(tokens: List<InputToken>, g: KeyboardGeometry): Interleave? {
            if (tokens.size < 2 || tokens.size > MAX_STROKES) return null
            if (tokens.none { it is SwipeToken }) return null
            if (!thumbsOverlap(tokens)) return null
            val strokes = tokens.sortedBy { it.tStart }
            val paths = strokes.map { t ->
                when (t) {
                    is SwipeToken -> t.rawPath.map { Triple(it.x, it.y, it.t) }
                    is TapToken -> listOf(Triple(t.x, t.y, t.tStart), Triple(t.x, t.y, maxOf(t.tEnd, t.tStart + 1)))
                }
            }
            if (paths.any { it.isEmpty() }) return null
            val all = paths.flatMap { p -> p.map { it.third } }.distinct().sorted()
            val ends = paths.flatMap { listOf(it.first().third, it.last().third) }.toSet()
            val times = if (all.size <= GRID_MAX) all else {
                val keep = (0 until GRID_MAX).map { all[(it.toLong() * (all.size - 1) / (GRID_MAX - 1)).toInt()] }
                (keep + ends).distinct().sorted()
            }.toLongArray()
            val n = strokes.size
            val grid = times.size
            val px = FloatArray(n * grid) { Float.NaN }
            val py = FloatArray(n * grid) { Float.NaN }
            val start = IntArray(n)
            val end = IntArray(n)
            for ((s, p) in paths.withIndex()) {
                val t0 = p.first().third
                val t1 = p.last().third
                start[s] = times.indexOfFirst { it >= t0 }
                end[s] = times.indexOfLast { it <= t1 }
                var k = 0
                for (i in 0 until grid) {
                    val t = times[i]
                    if (t < t0 || t > t1) continue
                    while (k + 1 < p.size && p[k + 1].third <= t) k++
                    val a = p[k]
                    val b = if (k + 1 < p.size) p[k + 1] else a
                    val f = if (b.third > a.third) (t - a.third).toFloat() / (b.third - a.third) else 0f
                    px[s * grid + i] = a.first + f * (b.first - a.first)
                    py[s * grid + i] = a.second + f * (b.second - a.second)
                }
                if (start[s] < 0 || end[s] < start[s]) return null
            }
            val arcs = FloatArray(n) { (strokes[it] as? SwipeToken)?.arcLen ?: 0f }
            return Interleave(n, times, px, py, start, end, g, arcs)
        }

        /** Whether two swipes from different thumbs overlap in time: both thumbs drawing at once. */
        fun thumbsOverlap(tokens: List<InputToken>): Boolean {
            for (a in tokens) for (b in tokens) {
                if (a !== b && a is SwipeToken && b is SwipeToken && a.streamId != b.streamId &&
                    a.tStart < b.tEnd && b.tStart < a.tEnd
                ) return true
            }
            return false
        }

        private fun pow3(n: Int): Int {
            var r = 1
            repeat(n) { r *= 3 }
            return r
        }
    }
}
