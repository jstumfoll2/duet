package com.kinetica.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The gap above the space row is added between the letters and the bottom row, and the board
 * grows to hold it, so every letter key keeps its size (the decoder's geometry is unchanged) and
 * the space bar keeps a letter key's height unless its own setting changes it.
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

    /** [layout] in pixels of a board [growth] times as tall as a 1000 px letter area. */
    private fun px(layout: KeyboardLayout, growth: Float): Map<String, Pair<Float, Float>> =
        layout.keys.associate { it.id to Pair(it.y * 1000f * growth, it.h * 1000f * growth) }

    @Test
    fun theGapIsAddedAndEveryKeyKeepsALetterKeysHeight() {
        val base = qwerty()
        val growth = LayoutMutations.boardGrowth(numberRow = false, spaceGap = 0.25f)
        assertEquals(1f + 0.25f * 0.25f, growth, 1e-6f)
        val before = px(base, 1f)
        val after = px(LayoutMutations.withSpaceRowGap(base, 0.25f), growth)
        for (k in base.keys) {
            val (y0, h0) = before.getValue(k.id)
            val (y1, h1) = after.getValue(k.id)
            assertEquals(k.id, h0, h1, 1e-2f)
            if (k.isLetter) assertEquals(k.id, y0, y1, 1e-2f) else assertEquals(k.id, y0 + 62.5f, y1, 1e-2f)
        }
        // The row still reaches the board's bottom edge.
        assertEquals(1f, LayoutMutations.withSpaceRowGap(base, 0.25f).keys.maxOf { it.y + it.h }, 1e-5f)
    }

    @Test
    fun theBottomRowSizeChangesOnlyTheBottomRow() {
        val base = qwerty()
        val growth = LayoutMutations.boardGrowth(numberRow = false, spaceGap = 0.25f, spaceSize = 0.8f)
        val before = px(base, 1f)
        val after = px(LayoutMutations.withSpaceRowGap(base, 0.25f, 0.8f), growth)
        assertEquals(250f * 0.8f, after.getValue("space").second, 1e-2f)
        for (k in base.keys.filter { it.isLetter }) {
            assertEquals(k.id, before.getValue(k.id).first, after.getValue(k.id).first, 1e-2f)
            assertEquals(k.id, before.getValue(k.id).second, after.getValue(k.id).second, 1e-2f)
        }
    }

    @Test
    fun noGapIsTheLayoutItself() {
        val base = qwerty()
        assertSame(base, LayoutMutations.withSpaceRowGap(base, 0f))
    }

    @Test
    fun theGapAndSizeAreCapped() {
        val space = LayoutMutations.withSpaceRowGap(qwerty(), 0.9f, 9f).keys.first { it.id == "space" }
        val growth = LayoutMutations.boardGrowth(numberRow = false, spaceGap = 0.9f, spaceSize = 9f)
        assertEquals(1f + 0.25f * (LayoutMutations.MAX_SPACE_ROW_GAP + LayoutMutations.MAX_ROW_SIZE - 1f), growth, 1e-6f)
        assertEquals(0.25f * LayoutMutations.MAX_ROW_SIZE, space.h * growth, 1e-5f)
    }
}
