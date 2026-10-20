package com.kinetica.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The gap above the space row takes the top of the bottom row's keys and leaves every letter
 * where it was, so the decoder's geometry is unchanged and only stray space touches go.
 */
class SpaceRowGapTest {

    private fun qwerty(): KeyboardLayout {
        val keys = ArrayList<Key>()
        fun row(letters: String, y: Float, offset: Float) {
            letters.forEachIndexed { i, c ->
                keys.add(Key(c.toString(), KeyType.CHAR, c.toString(), c.toString(), (offset + i) * 0.1f, y, 0.1f, 0.25f))
            }
        }
        row("qwertyuiop", 0.0f, 0.0f)
        row("asdfghjkl", 0.25f, 0.5f)
        row("zxcvbnm", 0.5f, 1.5f)
        keys.add(Key("comma", KeyType.CHAR, ",", ",", 0.1f, 0.75f, 0.1f, 0.25f))
        keys.add(Key("space", KeyType.SPACE, " ", " ", 0.2f, 0.75f, 0.6f, 0.25f))
        return KeyboardLayout("qwerty", "en_US", keys)
    }

    @Test
    fun lettersStayPutAndTheBottomRowLosesItsTop() {
        val base = qwerty()
        val gapped = LayoutMutations.withSpaceRowGap(base, 0.25f)
        for ((a, b) in base.keys.zip(gapped.keys)) {
            if (a.isLetter) {
                assertEquals(a, b)
            } else {
                assertEquals(a.id, 0.75f + 0.25f * 0.25f, b.y, 1e-5f)
                assertEquals(a.id, 0.25f * 0.75f, b.h, 1e-5f)
                // The row still reaches the board's bottom edge.
                assertEquals(a.id, 1f, b.y + b.h, 1e-5f)
            }
        }
    }

    @Test
    fun noGapIsTheLayoutItself() {
        val base = qwerty()
        assertSame(base, LayoutMutations.withSpaceRowGap(base, 0f))
    }

    @Test
    fun theGapIsCapped() {
        val space = LayoutMutations.withSpaceRowGap(qwerty(), 0.9f).keys.first { it.id == "space" }
        assertEquals(0.25f * (1f - LayoutMutations.MAX_SPACE_ROW_GAP), space.h, 1e-5f)
    }
}
