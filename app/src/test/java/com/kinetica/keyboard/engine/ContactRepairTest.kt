package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The digitizer merging two close thumbs into one contact for a few ms. Shape
 * and timings follow the developer's "positive" trace: both strokes stop at
 * 431 ms, a 2-sample touch at their midpoint 439-447, both resume at 456.
 */
class ContactRepairTest {

    private fun swipe(s: StreamId, x0: Float, y0: Float, x1: Float, y1: Float, t0: Long, t1: Long): SwipeToken {
        val path = (0..10).map { k -> PathPoint(x0 + (x1 - x0) * k / 10, y0 + (y1 - y0) * k / 10, t0 + (t1 - t0) * k / 10) }
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, r)
        return SwipeToken(s, path, r, emptyList(), 1f, t0, t1)
    }

    /** A stroke through [pts] (x, y, t), sampled every 10 ms and at each corner. */
    private fun stroke(s: StreamId, vararg pts: Triple<Float, Float, Long>): SwipeToken {
        val path = ArrayList<PathPoint>()
        for (k in 1 until pts.size) {
            val (x0, y0, t0) = pts[k - 1]
            val (x1, y1, t1) = pts[k]
            var t = if (k == 1) t0 else t0 + 10
            while (t < t1) {
                val f = (t - t0).toFloat() / (t1 - t0)
                path.add(PathPoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t))
                t += 10
            }
            path.add(PathPoint(x1, y1, t1))
        }
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, r)
        return SwipeToken(s, path, r, emptyList(), 1f, path.first().t, path.last().t)
    }

    private fun p(x: Float, y: Float, t: Long) = Triple(x, y, t)

    private val r1 = swipe(StreamId.RIGHT, 9.6f, 0.4f, 7.4f, 1.0f, 0, 431)
    private val l1 = swipe(StreamId.LEFT, 1.7f, 2.3f, 4.5f, 1.6f, 132, 431)
    private val blip = TapToken(StreamId.RIGHT, 5, 6.2f, 1.7f, false, 439, 447)
    private val r2 = swipe(StreamId.RIGHT, 7.3f, 0.7f, 7.1f, 0.6f, 456, 581)
    private val l2 = swipe(StreamId.LEFT, 4.9f, 3.3f, 2.8f, 1.2f, 456, 728)

    @Test
    fun aMergeBlipIsDroppedAndBothStrokesRejoined() {
        val out = ContactRepair.repair(listOf(r1, l1, blip, r2, l2))
        assertEquals(2, out.size)
        val r = out.single { it.streamId == StreamId.RIGHT } as SwipeToken
        val l = out.single { it.streamId == StreamId.LEFT } as SwipeToken
        assertEquals(0L to 581L, r.tStart to r.tEnd)
        assertEquals(132L to 728L, l.tStart to l.tEnd)
        assertEquals(r1.rawPath.size + r2.rawPath.size, r.rawPath.size)
    }

    @Test
    fun anOrdinaryLiftAndRetouchIsLeftAlone() {
        // One thumb lifts and comes back (a peck), the other keeps going: no blip.
        val l = swipe(StreamId.LEFT, 1.7f, 2.3f, 4.5f, 1.6f, 0, 600)
        val tokens: List<InputToken> = listOf(
            swipe(StreamId.RIGHT, 8f, 1f, 7f, 1f, 0, 200),
            TapToken(StreamId.RIGHT, 8, 7f, 1f, false, 230, 300),
            l,
        )
        assertSame(tokens, ContactRepair.repair(tokens))
    }

    @Test
    fun aSwapIsRegroupedByWhereEachThumbWas() {
        // Right lifts at (7,3); the left stroke slides there from (4.3,3) in 40 ms, and a
        // "new" right touch lands where the left thumb had been (the 10-21 "stronger" shape).
        val right = stroke(StreamId.RIGHT, p(9f, 2f, 0), p(7f, 3f, 300))
        val left = stroke(StreamId.LEFT, p(1f, 2f, 0), p(4.3f, 3f, 300), p(7f, 3f, 340), p(8f, 1f, 500))
        val landed = stroke(StreamId.RIGHT, p(4.3f, 3f, 330), p(2f, 1f, 500))
        val out = ContactRepair.regrouped(listOf(right, left, landed))!!
        assertEquals(2, out.size)
        val fromLeft = out.single { (it as SwipeToken).rawPath.first().x == 1f } as SwipeToken
        val fromRight = out.single { (it as SwipeToken).rawPath.first().x == 9f } as SwipeToken
        assertEquals(2f, fromLeft.rawPath.last().x, 1e-3f)
        assertEquals(8f, fromRight.rawPath.last().x, 1e-3f)
        assertEquals(500L, fromRight.tEnd)
    }

    @Test
    fun aRenumberedContactIsJoinedToItsStroke() {
        val left = stroke(StreamId.LEFT, p(1f, 2f, 0), p(4f, 3f, 290))
        val r = stroke(StreamId.RIGHT, p(9f, 1f, 0), p(6f, 3f, 300))
        val again = stroke(StreamId.RIGHT, p(6.1f, 3f, 305), p(8f, 1f, 500))
        val out = ContactRepair.regrouped(listOf(left, r, again))!!
        assertEquals(2, out.size)
        val right = out.single { it.streamId == StreamId.RIGHT } as SwipeToken
        assertEquals(0L to 500L, right.tStart to right.tEnd)
    }

    @Test
    fun bothThumbsLandingTogetherResumeTheirOwnStrokes() {
        val l = stroke(StreamId.LEFT, p(1f, 2f, 0), p(4f, 3f, 300))
        val r = stroke(StreamId.RIGHT, p(8f, 2f, 0), p(5f, 3f, 300))
        val l2 = stroke(StreamId.LEFT, p(3.8f, 2.9f, 315), p(2f, 1f, 500))
        val r2 = stroke(StreamId.RIGHT, p(5.3f, 2.9f, 320), p(8f, 1f, 500))
        val out = ContactRepair.regrouped(listOf(l, r, l2, r2))!!
        assertEquals(2, out.size)
        val left = out.single { it.streamId == StreamId.LEFT } as SwipeToken
        assertEquals(1f, left.rawPath.first().x, 1e-3f)
        assertEquals(2f, left.rawPath.last().x, 1e-3f)
    }

    @Test
    fun ordinaryInputHasNoRegroupedReading() {
        val l = swipe(StreamId.LEFT, 1.7f, 2.3f, 4.5f, 1.6f, 0, 600)
        val tokens: List<InputToken> = listOf(
            swipe(StreamId.RIGHT, 8f, 1f, 7f, 1f, 0, 200),
            TapToken(StreamId.RIGHT, 8, 7f, 1f, false, 230, 300),
            l,
        )
        assertEquals(null, ContactRepair.regrouped(tokens))
        assertEquals(null, ContactRepair.regrouped(ContactRepair.repair(listOf(r1, l1, blip, r2, l2))))
    }
}
