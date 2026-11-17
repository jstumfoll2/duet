package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * A stroke that dips to the spacebar and comes back up is two words: the word drawn before the
 * dip, a space, and the word drawn after it, so a phrase can be written without lifting.
 *
 * A dip is a run of samples below the letter block that reaches at least [MIN_DEPTH_KW] into
 * the spacebar's rect and then returns onto the letters. A stroke that only grazes the bottom
 * row's lower edge is not one, and neither is a stroke that lifts down there: with no letters
 * after it there is no second word, and the lift is left to what it was before.
 *
 * The trip down and back crosses letters nobody meant (from e down to the spacebar passes d
 * and c). Each leg is walked from the dip while the path keeps heading for it, and cut at the
 * turn where that began, as [ApostropheExcursion] cuts its trip. A word whose last stroke
 * already ran straight at the spacebar (e then d, then on down) has no turn at d to find, and
 * loses that letter to the leg: the walk stops at a letter only when the thumb turns there.
 * The cut pieces are plain strokes: marking them soft at the cut, or cutting at the block's
 * edge with no walk, both measured worse.
 *
 * This is a rule on the path, applied when the stroke lifts. It is not a scored word boundary
 * inside the decoder, so a dip is always taken as a space and never weighed against one word.
 */
object SpaceDip {

    /**
     * How far into the spacebar a dip must reach, kw. Of the 49 446 strokes in the developer's
     * traces (none meant as a dip), 13 went 0.3 into where the spacebar sits and came back, 1
     * went 0.5 and none 0.6.
     */
    const val MIN_DEPTH_KW = 0.5f

    /** Dips taken from one stroke; a longer phrase keeps its tail as one piece. */
    const val MAX_DIPS = 4

    /** Sample jitter allowed while walking a leg, kw. */
    private const val LEG_TOLERANCE_KW = 0.05f

    /**
     * Below this cosine between the path's heading and the way to the dip, the path turned
     * (about 26 degrees). At 45 degrees a letter roughly in line with the dip was taken for
     * part of the leg: on two recorded swipes joined by straight legs, both words came back
     * 49.8% of the time, against 56.3% here and 64.8% for the two swipes decoded apart
     * (`replay spacedip`). Real legs curve, so this is not set tighter on straight ones.
     */
    private const val TURN_COS = 0.9f

    /** Shortest step the heading is measured over, kw, so sample jitter does not read as a turn. */
    private const val HEADING_STEP_KW = 0.15f

    /** The piece before a dip ends at sample [headEnd]; the piece after it starts at [tailStart]. */
    class Dip(val headEnd: Int, val tailStart: Int)

    /**
     * The first dip in [points] at or after sample [from], or null. [space] is the spacebar's
     * rect and [floor] the bottom edge of the letter block, kw.
     */
    fun find(points: List<PathPoint>, space: FloatArray, floor: Float, from: Int = 0): Dip? {
        val n = points.size
        var i = from + 1
        while (i < n) {
            if (points[i].y <= floor) {
                i++
                continue
            }
            val a = i
            var b = a
            var deep = a
            while (b + 1 < n && points[b + 1].y > floor) {
                b++
                if (points[b].y > points[deep].y) deep = b
            }
            i = b + 1
            // Lifted below the letters: no second word.
            if (b >= n - 1) return null
            val d = points[deep]
            if (d.y < space[1] + MIN_DEPTH_KW || d.y >= space[3] || d.x < space[0] || d.x >= space[2]) continue
            return Dip(walk(points, a - 1, from, -1, d), walk(points, b + 1, n - 1, 1, d))
        }
        return null
    }

    /**
     * From sample [start], just outside the dip, steps away from it ([step] -1 back in time,
     * +1 forward) up to [limit] while the path there was heading for the dip's deepest point
     * [d] (or, after it, straight away from it), and returns the sample where that stops.
     */
    private fun walk(points: List<PathPoint>, start: Int, limit: Int, step: Int, d: PathPoint): Int {
        var j = start
        while (j != limit) {
            val next = j + step
            if (dist(points[next], d) < dist(points[j], d) - LEG_TOLERANCE_KW) break
            // The heading over a real step, measured from the far side of j.
            var k = next
            while (k != limit && dist(points[k], points[j]) < HEADING_STEP_KW) k += step
            val vx = points[j].x - points[k].x
            val vy = points[j].y - points[k].y
            val wx = d.x - points[j].x
            val wy = d.y - points[j].y
            val v = sqrt(vx * vx + vy * vy)
            val w = sqrt(wx * wx + wy * wy)
            if (v >= HEADING_STEP_KW && w > 0f && (vx * wx + vy * wy) / (v * w) < TURN_COS) break
            j = next
        }
        return j
    }

    /**
     * [token] as the words its dips separate, in order, or null when it has no dip (or the
     * board has no spacebar rect). A piece that barely moved is a tap on the letter under it.
     */
    fun split(token: SwipeToken, g: KeyboardGeometry, tapDispKw: Float, resampler: DtwMatcher): List<InputToken>? {
        val space = g.spaceRectKw() ?: return null
        val floor = g.blockBottomKw()
        val path = token.rawPath
        val out = ArrayList<InputToken>(2)
        var from = 0
        while (out.size < MAX_DIPS) {
            val dip = find(path, space, floor, from) ?: break
            out.add(piece(token, from, dip.headEnd, g, tapDispKw, resampler) ?: return null)
            from = dip.tailStart
        }
        if (out.isEmpty()) return null
        out.add(piece(token, from, path.size - 1, g, tapDispKw, resampler) ?: return null)
        return out
    }

    private fun piece(
        s: SwipeToken,
        from: Int,
        to: Int,
        g: KeyboardGeometry,
        tapDispKw: Float,
        resampler: DtwMatcher,
    ): InputToken? {
        val path = s.rawPath
        val tFrom = if (from == 0) s.tStart else path[from].t
        val tTo = if (to == path.size - 1) s.tEnd else path[to].t
        var maxDisp = 0f
        for (i in from..to) maxDisp = maxOf(maxDisp, dist(path[i], path[from]))
        if (to == from || maxDisp < tapDispKw) {
            val code = g.nearestKey(path[from].x, path[from].y)
            if (code < 0) return null
            return TapToken(s.streamId, code, path[from].x, path[from].y, longPress = false, tStart = tFrom, tEnd = tTo)
        }
        val sub = ArrayList(path.subList(from, to + 1))
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        resampler.resample(sub, r)
        var arc = 0f
        for (i in 1 until sub.size) arc += dist(sub[i], sub[i - 1])
        return SwipeToken(
            s.streamId, sub, r,
            s.keyContacts.filter { it.tExit >= path[from].t && it.tEnter <= path[to].t },
            arc, tFrom, tTo,
        )
    }

    private fun dist(a: PathPoint, b: PathPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
