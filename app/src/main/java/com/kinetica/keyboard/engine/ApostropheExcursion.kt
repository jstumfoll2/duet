package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.PathPoint
import kotlin.math.sqrt

/**
 * A swipe that went out to the apostrophe key and came back mid-word, Nintype style:
 * `w-e-'-r-e` for "we're".
 *
 * The apostrophe key sits at the right edge of the home row, so the trip there and back
 * crosses a row of letters nobody meant (e to ' passes r, t, y, u, i, o). Matching that
 * path letter for letter would never find "we're". So the trip is cut out of the path,
 * which leaves the gesture the user would have drawn without it (w-e-r-e), and the token
 * is marked: the decoder then prefers the dictionary's apostrophe spelling of whatever
 * the letters say (WordPredictor.preferApostrophe). The letters themselves never had to
 * spell the apostrophe; the trie already admits it anywhere at no path cost.
 *
 * Two shapes count, and both are deliberately narrow, because a false mark turns "well"
 * into "we'll":
 *  - a TRIP: the path enters the key's rect after it started, leaves it again, arrived
 *    from at least [MIN_LEG_KW] away, went back at least as far, and turned around in x
 *    (in moving right, out moving left). A stroke that merely clips the key's corner on
 *    its way somewhere else (p down to l) is not one. The cut runs from where the path
 *    turned toward the key to where it turned back.
 *  - an ENDING: the stroke lifts on the key after travelling at least [MIN_LEG_KW] to it
 *    from a turn ("don't" as d, then o-n-' by the right thumb). The cut runs from that
 *    turn to the lift, so the stroke ends on its real last letter. An overshoot past L
 *    was the worry here; across the 7 062 recorded gestures in the developer's traces
 *    (2026-10-02 and 2026-10-06) no home-row sample ever went past x 9.44 kw, against
 *    the key's 9.5, so a lift on the key is taken as meant.
 *    The turn is first looked for as the point where the distance to the key stops
 *    growing. A stroke that heads for the key all the way from its start has no such point,
 *    and two shapes of it are taken by heading instead (batch 11: every swiped `I've` and
 *    `who's` read the key as L and came out `vile` and `who'lls`):
 *     - STRAIGHT from its first letter, at most [MAX_STRAIGHT_KW] away (`I've` as i, then
 *       straight to '): the cut leaves that first sample alone, and GestureStream makes
 *       the stroke a tap on it, marked.
 *     - from a turn in heading ([TURN_COS]) that the distance missed (`who's` as h-o, then
 *       down to '), when the leg after the turn never sits on the key left of it: a lift
 *       past L at the end of `festival` turns there too, and must not lose its L.
 *
 * Leg ends are found by walking away from the key while the distance to its centre keeps
 * growing, so a leg ends at the turn where the path started heading for the key, or at the
 * stroke's start or lift. That is what limits a trip to the two-thumb shape it is for, a
 * short stroke near the key ("i'll" as i, out to ', back to l): a one-thumb run from the
 * far side (w-e-'-r-e) has no turn at e or r to find, so its cut swallows those letters
 * and the word does not decode. A tap on the key is the way to write that one. An ending
 * with no turn at all is not taken.
 *
 * Only the first such visit is taken; one apostrophe per gesture covers every English
 * contraction and every elision the bundled dictionaries hold.
 */
object ApostropheExcursion {

    /**
     * How far from the key's centre a leg must start, kw. Below one key on purpose: L's
     * centre sits 0.75 to 0.9 kw from the key's, and "i'll" is i, out to ', back to l.
     */
    const val MIN_LEG_KW = 0.6f

    /** How far left of the key's centre both legs must reach, kw: the turn in x. */
    private const val TURN_X_KW = 0.3f

    /** Sample jitter allowed while walking a leg, kw. */
    private const val LEG_TOLERANCE_KW = 0.05f

    /**
     * The farthest a STRAIGHT ending may start from the key's centre, kw. `I've` from i is
     * 2.2 to 2.6 in batch 11 and `m'` from m 3.1 to 3.4; a stroke from t that ran on past L
     * at the end of `actually` was 5.3.
     */
    const val MAX_STRAIGHT_KW = 3.5f

    /** Below this cosine between the path's heading and the way to the key, the path turned. */
    private const val TURN_COS = 0.7071f

    /** Shortest step the heading is measured over, kw, so sample jitter does not read as a turn. */
    private const val HEADING_STEP_KW = 0.1f

    /** How near the stroke's start a heading turn counts as the start itself, kw. */
    private const val START_KW = 0.3f

    /**
     * The samples to drop, or null when [points] made no trip to [rect] (kw: left, top,
     * right, bottom) and did not end on it. The range never includes the sample where the
     * path turned toward the key, nor (for a trip) the one where it turned back: the path
     * joins them with a straight line. For an ending it runs to the last sample. [left] is the
     * letter key beside the key (L on QWERTY), which a heading turn's leg must not sit on.
     */
    fun find(points: List<PathPoint>, rect: FloatArray, left: FloatArray? = null): IntRange? {
        val n = points.size
        var a = -1
        for (i in 0 until n) {
            if (inside(points[i], rect)) {
                a = i
                break
            }
        }
        if (a <= 0) return null
        var b = a
        while (b + 1 < n && inside(points[b + 1], rect)) b++

        val cx = (rect[0] + rect[2]) / 2f
        val cy = (rect[1] + rect[3]) / 2f
        var s = a
        while (s > 0 && dist(points[s - 1], cx, cy) >= dist(points[s], cx, cy) - LEG_TOLERANCE_KW) s--

        if (b >= n - 1) {
            // An ending: only from a turn, and only after real travel to the key.
            if (s > 0 && dist(points[s], cx, cy) >= MIN_LEG_KW) return (s + 1) until n
            return headingEnding(points, a, left, cx, cy)
        }
        var e = b
        while (e < n - 1 && dist(points[e + 1], cx, cy) >= dist(points[e], cx, cy) - LEG_TOLERANCE_KW) e++

        if (dist(points[s], cx, cy) < MIN_LEG_KW || dist(points[e], cx, cy) < MIN_LEG_KW) return null
        // Turned around in x: in moving right, out moving left.
        if (cx - points[s].x < TURN_X_KW || points[e].x - cx > -TURN_X_KW) return null
        if (e - s < 2) return null
        return (s + 1) until e
    }

    /**
     * The cut for an ending whose distance to the key grew all the way back to the stroke's
     * start ([find]): from the last turn in heading before the key was entered at [entry], or
     * everything after the first sample when there is none. Null when neither shape fits.
     */
    private fun headingEnding(points: List<PathPoint>, entry: Int, left: FloatArray?, cx: Float, cy: Float): IntRange? {
        var j = entry
        while (j > 0) {
            var k = j - 1
            while (k > 0 && dist(points[k], points[j].x, points[j].y) < HEADING_STEP_KW) k--
            val vx = points[j].x - points[k].x
            val vy = points[j].y - points[k].y
            val wx = cx - points[j].x
            val wy = cy - points[j].y
            val v = sqrt(vx * vx + vy * vy)
            val w = sqrt(wx * wx + wy * wy)
            if (v > 0f && w > 0f && (vx * wx + vy * wy) / (v * w) < TURN_COS) break
            j = k
        }
        val leg = dist(points[j], cx, cy)
        if (leg < MIN_LEG_KW) return null
        val n = points.size
        if (dist(points[j], points[0].x, points[0].y) <= START_KW) {
            return if (dist(points[0], cx, cy) <= MAX_STRAIGHT_KW) 1 until n else null
        }
        if (left != null && (j + 1 until n).any { inside(points[it], left) }) return null
        return (j + 1) until n
    }

    /** What a swipe's per-sample records look like once [cut] is removed from its path. */
    class Cut(
        val points: List<PathPoint>,
        val contacts: List<KeyContact>,
        val dwells: List<Dwell>,
        val arcLen: Float,
    )

    /**
     * Removes [cut] from the path and drops what the trip produced: the key contacts
     * entered and left inside it (the letters it crossed) and the dwells that touch it.
     * Later dwells are re-indexed, and contacts that end up adjacent on one key merge, so
     * the contact list keeps its no-consecutive-duplicates invariant.
     */
    fun apply(
        points: List<PathPoint>,
        contacts: List<KeyContact>,
        dwells: List<Dwell>,
        cut: IntRange,
    ): Cut {
        val tFrom = points[cut.first - 1].t
        // An ending has nothing after the cut: everything entered after the turn goes.
        val tTo = if (cut.last + 1 < points.size) points[cut.last + 1].t else Long.MAX_VALUE
        val kept = ArrayList<PathPoint>(points.size - (cut.last - cut.first + 1))
        for (i in points.indices) if (i !in cut) kept.add(points[i])

        val cs = ArrayList<KeyContact>(contacts.size)
        for (c in contacts) {
            // Entered after the turn toward the key and left before the turn back: a key
            // the trip crossed. The key the turn sat on was entered before it and stays.
            if (c.tEnter > tFrom && c.tExit <= tTo) continue
            val last = cs.lastOrNull()
            if (last != null && last.code == c.code) {
                cs[cs.size - 1] = KeyContact(c.code, last.tEnter, c.tExit)
            } else {
                cs.add(c)
            }
        }

        val removed = cut.last - cut.first + 1
        val ds = ArrayList<Dwell>(dwells.size)
        for (d in dwells) {
            when {
                d.exitIdx < cut.first -> ds.add(d)
                d.enterIdx > cut.last -> ds.add(d.copy(enterIdx = d.enterIdx - removed, exitIdx = d.exitIdx - removed))
                else -> Unit
            }
        }

        var arc = 0f
        for (i in 1 until kept.size) {
            val dx = kept[i].x - kept[i - 1].x
            val dy = kept[i].y - kept[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        return Cut(kept, cs, ds, arc)
    }

    private fun inside(p: PathPoint, r: FloatArray): Boolean =
        p.x >= r[0] && p.x < r[2] && p.y >= r[1] && p.y < r[3]

    private fun dist(p: PathPoint, cx: Float, cy: Float): Float {
        val dx = p.x - cx
        val dy = p.y - cy
        return sqrt(dx * dx + dy * dy)
    }
}
