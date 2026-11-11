package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ContactRepair.stitch]: a thumb's strokes joined across a lift too brief to be meant. */
class ContactStitchTest {

    private fun swipe(stream: StreamId, x0: Float, x1: Float, t0: Long, t1: Long): SwipeToken {
        val path = (0..10).map { PathPoint(x0 + (x1 - x0) * it / 10f, 2f, t0 + (t1 - t0) * it / 10) }
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, r)
        return SwipeToken(stream, path, r, emptyList(), kotlin.math.abs(x1 - x0), t0, t1)
    }

    private fun tap(stream: StreamId, x: Float, t: Long) = TapToken(stream, 0, x, 2f, false, t, t + 40)

    @Test fun `a brief lift between two swipes of one thumb is joined`() {
        val out = ContactRepair.stitch(listOf(swipe(StreamId.LEFT, 1f, 3f, 0, 200), swipe(StreamId.LEFT, 3.4f, 5f, 260, 400)))
        assertEquals(1, out.size)
        val s = out[0] as SwipeToken
        assertEquals(0L, s.tStart)
        assertEquals(400L, s.tEnd)
        assertEquals(22, s.rawPath.size)
    }

    @Test fun `a bounce before a swipe joins it`() {
        val out = ContactRepair.stitch(listOf(tap(StreamId.LEFT, 1f, 0), swipe(StreamId.LEFT, 1.2f, 4f, 100, 300)))
        assertEquals(1, out.size)
        assertTrue(out[0] is SwipeToken)
    }

    @Test fun `a real pause, a far return, the other thumb and two taps are left alone`() {
        val paused: List<InputToken> = listOf(swipe(StreamId.LEFT, 1f, 3f, 0, 200), swipe(StreamId.LEFT, 3.4f, 5f, 330, 400))
        assertSame(paused, ContactRepair.stitch(paused))
        val far: List<InputToken> = listOf(swipe(StreamId.LEFT, 1f, 3f, 0, 200), swipe(StreamId.LEFT, 5f, 7f, 260, 400))
        assertSame(far, ContactRepair.stitch(far))
        val other: List<InputToken> = listOf(swipe(StreamId.LEFT, 1f, 3f, 0, 200), swipe(StreamId.RIGHT, 3.4f, 5f, 260, 400))
        assertSame(other, ContactRepair.stitch(other))
        val taps: List<InputToken> = listOf(tap(StreamId.LEFT, 1f, 0), tap(StreamId.LEFT, 1f, 100))
        assertSame(taps, ContactRepair.stitch(taps))
    }

    @Test fun `a join never reaches over another touch of the same thumb`() {
        val t: List<InputToken> = listOf(
            tap(StreamId.LEFT, 1f, 0), tap(StreamId.LEFT, 6f, 41), swipe(StreamId.LEFT, 1.2f, 4f, 100, 300),
        )
        assertSame(t, ContactRepair.stitch(t))
    }
}
