package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The portrait Split layout mode's gap is a setting: 0 is the full-width board with nothing
 * between the halves, the default is the 16% the mode always had, and at every gap the two
 * halves keep their own keys, never overlap and scale every distance inside a half alike.
 */
class SplitGapTest {

    private val viewW = 1080f
    private val viewH = 700f

    private fun qwerty(): List<Key> {
        val out = ArrayList<Key>(30)
        fun row(letters: String, y: Float, offset: Float) {
            letters.forEachIndexed { i, c ->
                out.add(Key("$c", KeyType.CHAR, "$c", "$c", (offset + i) * 0.1f, y, 0.1f, 0.25f))
            }
        }
        row("qwertyuiop", 0.0f, 0.0f)
        row("asdfghjkl", 0.25f, 0.5f)
        row("zxcvbnm", 0.5f, 1.5f)
        return out
    }

    private val space = Key("space", KeyType.SPACE, " ", " ", 0.2f, 0.75f, 0.6f, 0.25f)

    private fun span(k: Key, gap: Float) = LayoutTransforms.splitX(k.x, k, gap) to LayoutTransforms.splitX(k.x + k.w, k, gap)

    private fun geometry(gap: Float): KeyboardGeometry {
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        var minW = Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = 0f
        for (k in qwerty()) {
            val (l, r) = span(k, gap)
            rects.add(floatArrayOf(l * viewW, k.y * viewH, r * viewW, (k.y + k.h) * viewH))
            codes.add(k.output[0] - 'a')
            minW = minOf(minW, (r - l) * viewW)
            left = minOf(left, l * viewW)
            right = maxOf(right, r * viewW)
        }
        return KeyboardGeometry.fromPx(minW, (left + right) / 2f, rects, codes.toIntArray())
    }

    @Test
    fun zeroIsTheFullWidthBoard() {
        for (k in qwerty() + space) {
            assertEquals(k.id, k.x, LayoutTransforms.splitX(k.x, k, 0f), 1e-6f)
            assertEquals(k.id, k.x + k.w, LayoutTransforms.splitX(k.x + k.w, k, 0f), 1e-6f)
        }
        // T and Y touch: no seam at all.
        val keys = qwerty()
        assertEquals(span(keys.first { it.id == "t" }, 0f).second, span(keys.first { it.id == "y" }, 0f).first, 1e-6f)
    }

    @Test
    fun theDefaultIsTheGapSplitAlwaysHad() {
        assertEquals(Prefs.DEFAULT_SPLIT_GAP_PCT / 100f, LayoutTransforms.DEFAULT_SPLIT_GAP, 1e-6f)
        val t = qwerty().first { it.id == "t" }
        val y = qwerty().first { it.id == "y" }
        // The constants it replaced: left half into [0, 0.42], right half into [0.58, 1].
        assertEquals(0.42f, LayoutTransforms.splitX(t.x + t.w, t), 1e-6f)
        assertEquals(0.58f, LayoutTransforms.splitX(y.x, y), 1e-6f)
        assertEquals(1f, LayoutTransforms.splitX(1f, qwerty().first { it.id == "p" }), 1e-6f)
    }

    @Test
    fun theSeamIsExactlyTheGap() {
        val t = qwerty().first { it.id == "t" }
        val y = qwerty().first { it.id == "y" }
        for (gap in listOf(0f, 0.02f, 0.05f, 0.1f, 0.16f, 0.3f, 0.4f)) {
            assertEquals("gap $gap", gap, span(y, gap).first - span(t, gap).second, 1e-5f)
        }
    }

    @Test
    fun theHalvesNeverOverlapInARow() {
        for (gap in listOf(0f, 0.01f, 0.05f, 0.16f, 0.4f)) {
            for (a in qwerty()) for (b in qwerty()) {
                if (a === b || a.y != b.y) continue
                val (al, ar) = span(a, gap)
                val (bl, br) = span(b, gap)
                assertTrue("${a.id}/${b.id} overlap at gap $gap", ar <= bl + 1e-6f || br <= al + 1e-6f)
            }
        }
    }

    @Test
    fun everyDistanceInsideAHalfScalesAlike() {
        // Like the side inset, a gap shrinks both halves by one factor, so no key's
        // neighbours move relative to each other inside a half.
        val full = geometry(0f)
        for (gap in listOf(0.05f, 0.16f, 0.4f)) {
            val g = geometry(gap)
            val keys = qwerty()
            fun leftHalf(k: Key) = k.x + k.w / 2f < 0.5f
            for (a in keys) for (b in keys) {
                if (leftHalf(a) != leftHalf(b)) continue
                val ca = a.output[0] - 'a'
                val cb = b.output[0] - 'a'
                val wantPx = full.keyDist(ca, cb) * full.keyWidthPx * (1f - gap)
                assertEquals("${a.id}->${b.id} at $gap", wantPx, g.keyDist(ca, cb) * g.keyWidthPx, 0.05f)
            }
            // The decoder's midline stays the view's centre, so each thumb keeps its own half.
            assertEquals(viewW / 2f, g.midlinePx, 1e-2f)
        }
    }

    @Test
    fun outOfRangeGapsAreClamped() {
        val p = qwerty().first { it.id == "p" }
        assertEquals(p.x, LayoutTransforms.splitX(p.x, p, -0.3f), 1e-6f)
        assertEquals(
            LayoutTransforms.splitX(p.x, p, LayoutTransforms.MAX_SPLIT_GAP),
            LayoutTransforms.splitX(p.x, p, 0.9f), 1e-6f,
        )
        assertTrue(Prefs.MAX_SPLIT_GAP_PCT / 100f <= LayoutTransforms.MAX_SPLIT_GAP)
    }

    @Test
    fun theSpacebarStillBridgesTheHalves() {
        for (gap in listOf(0f, 0.16f, 0.4f)) {
            assertEquals(space.x, LayoutTransforms.splitX(space.x, space, gap), 0f)
            assertEquals(space.x + space.w, LayoutTransforms.splitX(space.x + space.w, space, gap), 0f)
        }
    }
}
