package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * Undoes the touchscreen merging two thumbs that came close together.
 *
 * Seen in the developer's practice traces ("sitting", "positive"): with the
 * thumbs about a key apart near the middle of the board, the digitizer reports
 * them as ONE contact for a few milliseconds. Android delivers that as both
 * pointers lifting at the same moment, a one- or two-sample touch at their
 * midpoint, and then two fresh touches where the thumbs already were. The user
 * never lifted; the trail jumps to the centre and back, and the decoder gets
 * five or six tokens for a word the thumbs drew as two.
 *
 * [repair] recognises exactly that shape, drops the midpoint blip, and joins
 * each interrupted stroke to its continuation (the fresh touch nearest to where
 * it stopped). Anything else is returned untouched, so ordinary lifts, taps and
 * double-letter pecks are never rejoined.
 *
 * The owner's 2026-10-21 "stronger" tries show three more shapes of the same merge, none with
 * a blip, each undone here:
 * - **Re-ID**: one pointer lifts and another goes down on the same spot in the same few ms
 *   while the other thumb has just lifted. One contact, renumbered: joined back ([reId]).
 * - **Swap**: a stroke slides across to where the other thumb just lifted, and a fresh touch
 *   appears where the stroke was. The digitizer handed each thumb the other's pointer. The
 *   stroke's head and the fresh touch are one thumb, the lifted stroke and the slid tail the
 *   other ([swap]). The trail is what visibly jumps.
 * - **Resume**: both thumbs lift and both come back down within a few ms of each other, each
 *   near where its stroke just was. The stroke that was dragged towards the other thumb keeps
 *   only the path before the drag ([resume]). Without it the thumb resting on its key comes
 *   back as an extra tap.
 */
object ContactRepair {

    /** The blip lasts at most this long. */
    const val BLIP_MAX_MS = 30L

    /** Both thumbs drop within this of each other, and of the blip starting. */
    const val END_SYNC_MS = 20L

    /** A continuation starts at most this long after the blip ends. */
    const val RESUME_MS = 50L

    /** The blip sits this close to the thumbs' midpoint. */
    const val MIDPOINT_KW = 1.0f

    /** A continuation starts this close to where its stroke stopped (thumbs keep moving through the merge: 1.75 kw in "positive"). */
    const val RESUME_KW = 2.0f

    fun repair(tokens: List<InputToken>): List<InputToken> {
        if (tokens.size < 3) return tokens
        if (tokens.any { it is SwipeToken && it.rawPath.isEmpty() }) return tokens
        var cur = tokens
        // Each pass removes one blip and at least one token, so this terminates.
        while (true) {
            cur = repairOnce(cur) ?: return cur
        }
    }

    /**
     * [tokens] (already [repair]ed) regrouped by the blip-free merge shapes, or null when none
     * is found. A reading, not a verdict: the same shapes also turn up in fast deliberate
     * strokes, and replacing the input with this won 13 words and lost 8 on the owner's 13
     * batches, so the decoder reads both and keeps each word's better score ([REGROUP_KEEP]).
     */
    fun regrouped(tokens: List<InputToken>): List<InputToken>? {
        if (tokens.size < 3) return null
        if (tokens.any { it is SwipeToken && it.rawPath.isEmpty() }) return null
        var cur = tokens
        // Each pass removes at least one token, so this terminates; bounded all the same.
        repeat(tokens.size) {
            cur = reId(cur) ?: swap(cur) ?: resume(cur) ?: return cur.takeIf { it !== tokens }
        }
        return cur.takeIf { it !== tokens }
    }

    /**
     * Score kept by a word read from the [regrouped] tokens. The same shapes turn up in fast
     * deliberate strokes, so the regrouped reading has to win clearly. 13 batches, against
     * reading the buffer as it came: 1.0 +13/-7 words, 0.8 +13/-5, 0.7 +12/-3 (both halves
     * up: +0.12 / +0.11 pts), 0.6 +10/-3, 0.5 +8/-2.
     */
    const val REGROUP_KEEP = 0.7f

    /** A renumbered contact starts within this of the old one lifting, */
    const val REID_MS = 12L

    /** this close to where it lifted, */
    const val REID_KW = 0.4f

    /** and the other thumb lifted at most this long before (a merge needs both thumbs). */
    const val REID_OTHER_MS = 60L

    /** A swapped touch lands this close to where the other stroke was, */
    const val SWAP_KW = 0.8f

    /** at most this long before it landed; the stroke then reaches the lifted end */
    const val SWAP_LOOKBACK_MS = 150L

    /** within this of the swapped touch landing, */
    const val SWAP_ARRIVE_MS = 100L

    /** and it counts as arrived this close to where the lifted stroke ended (the merged contact drifts between the thumbs before it snaps across), */
    const val SWAP_ARRIVE_KW = 0.5f

    /** after crossing at least this far. */
    const val SWAP_TRAVEL_KW = 1.0f

    /** Two thumbs a merge can involve are at most this far apart. */
    const val MERGE_SPAN_KW = 3.0f

    /** Both resumes land within this of each other, */
    const val RESUME_SYNC_MS = 15L

    /** each at most this long after its stroke lifted, one of them at most [END_SYNC_MS] + 5, */
    const val RESUME_WINDOW_MS = 120L

    /** near a point of its stroke from the last this-many ms. */
    const val RESUME_TAIL_MS = 100L

    private fun reId(tokens: List<InputToken>): List<InputToken>? {
        for (a in tokens) for (c in tokens) {
            if (a === c || a !is SwipeToken || c !is SwipeToken) continue
            val gap = c.tStart - a.tEnd
            if (gap < 0 || gap > REID_MS) continue
            val (ax, ay) = endOf(a)
            val (cx, cy) = startOf(c)
            if (dist(ax, ay, cx, cy) > REID_KW) continue
            val otherLifted = tokens.any {
                it !== a && it !== c && it.streamId != a.streamId &&
                    a.tEnd - it.tEnd in -REID_MS..REID_OTHER_MS
            }
            if (!otherLifted) continue
            return tokens.mapNotNull { t ->
                when {
                    t === c -> null
                    t === a -> join(a, c)
                    else -> t
                }
            }
        }
        return null
    }

    private fun swap(tokens: List<InputToken>): List<InputToken>? {
        for (d in tokens) for (b in tokens) {
            if (b === d || b !is SwipeToken || b.streamId == d.streamId) continue
            if (b.tStart >= d.tStart || b.tEnd <= d.tStart) continue
            val (dx, dy) = startOf(d)
            val path = b.rawPath
            var tau = -1
            for (i in path.indices) {
                val p = path[i]
                if (p.t > d.tStart) break
                if (p.t >= d.tStart - SWAP_LOOKBACK_MS && dist(p.x, p.y, dx, dy) <= SWAP_KW) tau = i
            }
            if (tau < 1) continue
            for (a in tokens) {
                if (a === b || a === d || a.streamId != d.streamId) continue
                if (a.tEnd < path[tau].t - END_SYNC_MS * 2 || a.tEnd > d.tStart + REID_MS) continue
                val (ax, ay) = endOf(a)
                if (dist(ax, ay, path[tau].x, path[tau].y) > MERGE_SPAN_KW) continue
                var sigma = -1
                for (j in tau + 1 until path.size) {
                    if (path[j].t > d.tStart + SWAP_ARRIVE_MS) break
                    if (dist(path[j].x, path[j].y, ax, ay) <= SWAP_ARRIVE_KW) { sigma = j; break }
                }
                if (sigma < 0 || path.size - sigma < 2) continue
                if (dist(path[tau].x, path[tau].y, path[sigma].x, path[sigma].y) < SWAP_TRAVEL_KW) continue
                val head = slice(b, 0, tau)
                val tail = slice(b, sigma, path.size - 1)
                return tokens.mapNotNull { t ->
                    when {
                        t === d -> null
                        t === b -> join(head, d)
                        t === a -> join(a, tail)
                        else -> t
                    }
                }
            }
        }
        return null
    }

    private fun resume(tokens: List<InputToken>): List<InputToken>? {
        for (c1 in tokens) for (c2 in tokens) {
            if (c1 === c2 || c1.streamId == c2.streamId || c1.tStart > c2.tStart) continue
            if (c2.tStart - c1.tStart > RESUME_SYNC_MS) continue
            val ended = tokens.filter {
                it is SwipeToken && it !== c1 && it !== c2 &&
                    it.tEnd <= c1.tStart && c1.tStart - it.tEnd <= RESUME_WINDOW_MS
            }
            for (a1 in ended) for (a2 in ended) {
                if (a1 === a2 || a1.streamId == a2.streamId) continue
                if (minOf(c1.tStart - a1.tEnd, c1.tStart - a2.tEnd) > END_SYNC_MS + 5) continue
                val (x1, y1) = endOf(a1)
                val (x2, y2) = endOf(a2)
                if (dist(x1, y1, x2, y2) > MERGE_SPAN_KW) continue
                val k1 = nearTail(a1 as SwipeToken, c1) ?: continue
                val k2 = nearTail(a2 as SwipeToken, c2) ?: continue
                val j1 = join(slice(a1, 0, k1), c1)
                val j2 = join(slice(a2, 0, k2), c2)
                return tokens.mapNotNull { t ->
                    when {
                        t === c1 || t === c2 -> null
                        t === a1 -> j1
                        t === a2 -> j2
                        else -> t
                    }
                }
            }
        }
        return null
    }

    /**
     * The last index of [a]'s path in its final [RESUME_TAIL_MS] within [RESUME_KW] / 2 of where
     * [c] lands, the point the stroke was really at before the merge dragged it; null if none.
     */
    private fun nearTail(a: SwipeToken, c: InputToken): Int? {
        val (cx, cy) = startOf(c)
        val path = a.rawPath
        var best = -1
        var bestD = RESUME_KW / 2
        for (i in path.indices.reversed()) {
            val p = path[i]
            if (a.tEnd - p.t > RESUME_TAIL_MS) break
            val d = dist(p.x, p.y, cx, cy)
            if (d <= bestD) { bestD = d; best = i }
        }
        return if (best >= 1) best else null
    }

    /** [a]'s path [from]..[to] as a stroke of its own, with the key contacts inside that time. */
    private fun slice(a: SwipeToken, from: Int, to: Int): SwipeToken {
        val path = ArrayList(a.rawPath.subList(from, to + 1))
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, r)
        var arc = 0f
        for (i in 1 until path.size) arc += dist(path[i - 1].x, path[i - 1].y, path[i].x, path[i].y)
        val t0 = path.first().t
        val t1 = path.last().t
        val dwells = a.dwells.filter { it.enterIdx >= from && it.exitIdx <= to }
            .map { it.copy(enterIdx = it.enterIdx - from, exitIdx = it.exitIdx - from) }
        return SwipeToken(
            a.streamId, path, r,
            a.keyContacts.filter { it.tExit >= t0 && it.tEnter <= t1 }
                .map { KeyContact(it.code, maxOf(it.tEnter, t0), minOf(it.tExit, t1)) },
            arc, if (from == 0) a.tStart else t0, if (to == a.rawPath.size - 1) a.tEnd else t1,
            dwells = dwells,
        )
    }

    private fun repairOnce(tokens: List<InputToken>): List<InputToken>? {
        for (b in tokens) {
            if (b.tEnd - b.tStart > BLIP_MAX_MS) continue
            val ended = tokens.filter {
                it !== b && it.tEnd <= b.tStart && b.tStart - it.tEnd <= END_SYNC_MS
            }
            for (a1 in ended) for (a2 in ended) {
                if (a1 === a2 || a1.streamId == a2.streamId) continue
                if (kotlin.math.abs(a1.tEnd - a2.tEnd) > END_SYNC_MS) continue
                val (x1, y1) = endOf(a1)
                val (x2, y2) = endOf(a2)
                val (bx, by) = startOf(b)
                if (dist(bx, by, (x1 + x2) / 2, (y1 + y2) / 2) > MIDPOINT_KW) continue
                val c1 = continuation(tokens, b, a1, a2) ?: continue
                val c2 = continuation(tokens, b, a2, a1)
                if (c2 === c1) continue
                val out = ArrayList<InputToken>(tokens.size)
                for (t in tokens) {
                    when {
                        t === b || t === c1 || t === c2 -> {}
                        t === a1 -> out.add(join(a1, c1))
                        t === a2 -> out.add(if (c2 != null) join(a2, c2) else a2)
                        else -> out.add(t)
                    }
                }
                return out
            }
        }
        return null
    }

    /** The fresh touch nearest to where [a] stopped, starting just after the blip. */
    private fun continuation(tokens: List<InputToken>, b: InputToken, a: InputToken, other: InputToken): InputToken? {
        val (ax, ay) = endOf(a)
        var best: InputToken? = null
        var bestD = RESUME_KW
        for (c in tokens) {
            if (c === a || c === b || c === other) continue
            if (c.tStart < b.tEnd || c.tStart - b.tEnd > RESUME_MS) continue
            val (cx, cy) = startOf(c)
            val d = dist(ax, ay, cx, cy)
            if (d <= bestD) { bestD = d; best = c }
        }
        return best
    }

    private fun join(a: InputToken, c: InputToken): SwipeToken {
        val path = ArrayList<PathPoint>()
        path.addAll(pointsOf(a))
        path.addAll(pointsOf(c))
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, r)
        var arc = 0f
        for (i in 1 until path.size) arc += dist(path[i - 1].x, path[i - 1].y, path[i].x, path[i].y)
        val contacts = ArrayList<KeyContact>()
        for (k in contactsOf(a) + contactsOf(c)) {
            val last = contacts.lastOrNull()
            if (last != null && last.code == k.code) contacts[contacts.size - 1] = KeyContact(k.code, last.tEnter, k.tExit)
            else contacts.add(k)
        }
        val offset = pointsOf(a).size
        val dwells = (a as? SwipeToken)?.dwells.orEmpty() +
            (c as? SwipeToken)?.dwells.orEmpty().map { it.copy(enterIdx = it.enterIdx + offset, exitIdx = it.exitIdx + offset) }
        return SwipeToken(a.streamId, path, r, contacts, arc, a.tStart, c.tEnd, dwells = dwells)
    }

    private fun pointsOf(t: InputToken): List<PathPoint> = when (t) {
        is SwipeToken -> t.rawPath
        is TapToken -> listOf(PathPoint(t.x, t.y, t.tStart), PathPoint(t.x, t.y, t.tEnd))
    }

    private fun contactsOf(t: InputToken): List<KeyContact> = when (t) {
        is SwipeToken -> t.keyContacts
        is TapToken -> listOf(KeyContact(t.code, t.tStart, t.tEnd))
    }

    private fun startOf(t: InputToken): Pair<Float, Float> = when (t) {
        is SwipeToken -> t.rawPath.first().let { it.x to it.y }
        is TapToken -> t.x to t.y
    }

    private fun endOf(t: InputToken): Pair<Float, Float> = when (t) {
        is SwipeToken -> t.rawPath.last().let { it.x to it.y }
        is TapToken -> t.x to t.y
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }
}
