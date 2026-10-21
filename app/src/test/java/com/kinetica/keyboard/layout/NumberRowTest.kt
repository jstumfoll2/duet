package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.KeyboardGeometry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers row leaves the letters as they were: the board grows a row, so every
 * distance in key widths is unchanged. TestData never goes through a layout, so
 * no golden can see this and it is checked here.
 */
class NumberRowTest {

    private val viewW = 1080f
    private val viewH = 600f

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
        keys.add(Key("space", KeyType.SPACE, " ", " ", 0.2f, 0.75f, 0.6f, 0.25f))
        return KeyboardLayout("qwerty", "en_US", keys)
    }

    /** kw-space letter geometry of [layout] drawn [heightPx] tall, FULL mode by arithmetic. */
    private fun geometry(layout: KeyboardLayout, heightPx: Float): KeyboardGeometry {
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        var minW = Float.MAX_VALUE
        for (k in layout.keys) {
            if (!k.isLetter) continue
            val r = floatArrayOf(k.x * viewW, k.y * heightPx, (k.x + k.w) * viewW, (k.y + k.h) * heightPx)
            rects.add(r)
            codes.add(k.output[0] - 'a')
            if (r[2] - r[0] < minW) minW = r[2] - r[0]
        }
        return KeyboardGeometry.fromPx(minW, viewW / 2f, rects, codes.toIntArray())
    }

    @Test
    fun everyLetterDistanceInKwSurvivesTheRowWhenTheBoardGrows() {
        val base = geometry(qwerty(), viewH)
        val grown = geometry(LayoutMutations.withNumberRow(qwerty()), viewH * LayoutMutations.NUMBER_ROW_GROWTH)
        for (a in 0 until 26) for (b in 0 until 26) {
            assertTrue(
                "${'a' + a}-${'a' + b}: ${base.keyDist(a, b)} vs ${grown.keyDist(a, b)}",
                abs(base.keyDist(a, b) - grown.keyDist(a, b)) < 1e-3f,
            )
        }
    }

    @Test
    fun theDigitsAreOneTapOnlyRowAboveEveryOtherKey() {
        val l = LayoutMutations.withNumberRow(qwerty())
        val digits = l.keys.filter { it.id.startsWith("num_") }
        assertEquals("1234567890", digits.joinToString("") { it.output })
        assertTrue(digits.all { it.y == 0f && !it.isLetter && it.type == KeyType.CHAR })
        val rowBottom = digits.first().y + digits.first().h
        assertTrue(l.keys.filter { !it.id.startsWith("num_") }.all { it.y >= rowBottom - 1e-6f })
        assertTrue(l.keys.all { it.y + it.h <= 1f + 1e-6f })
        assertEquals("every letter is still there", 26, l.keys.count { it.isLetter })
    }

    @Test
    fun theBoardGrowsByExactlyOneOfItsOwnRows() {
        val l = LayoutMutations.withNumberRow(qwerty())
        val q = l.keys.first { it.id == "q" }
        val one = l.keys.first { it.id == "num_1" }
        assertEquals(q.h, one.h, 1e-6f)
        assertEquals(0.25f * viewH, q.h * viewH * LayoutMutations.NUMBER_ROW_GROWTH, 1e-3f)
    }

    @Test
    fun withTheRowTheTopKeysOfferSymbolsNotDigits() {
        val base = qwerty()
        val keys = base.keys.map {
            when (it.id) {
                "q" -> it.copy(alternates = listOf("1"))
                "e" -> it.copy(alternates = listOf("è", "é", "3"))
                "a" -> it.copy(alternates = listOf("à"))
                else -> it
            }
        }
        val l = LayoutMutations.withNumberRowSymbols(base.copy(keys = keys))
        assertEquals(listOf("%"), l.keys.first { it.id == "q" }.alternates)
        assertEquals(listOf("|", "è", "é"), l.keys.first { it.id == "e" }.alternates)
        assertEquals("%", l.keys.first { it.id == "q" }.hintChar)
        assertEquals("}", l.keys.first { it.id == "p" }.alternates.first())
        // Only the top row.
        assertEquals(listOf("à"), l.keys.first { it.id == "a" }.alternates)
    }

    @Test
    fun theGapSitsUnderFullHeightDigitsAndTheLettersKeepTheirSize() {
        val growth = LayoutMutations.boardGrowth(numberRow = true, numberGap = 0.25f)
        assertEquals(1f + 0.25f * 1.25f, growth, 1e-6f)
        val gapped = LayoutMutations.withNumberRow(qwerty(), 0.25f)
        val digit = gapped.keys.first { it.id == "num_9" }
        val o = gapped.keys.first { it.id == "o" }
        assertEquals(0f, digit.y, 1e-6f)
        // In pixels, on the grown board: the digit is a letter key tall, the gap a quarter row.
        assertEquals(0.25f * viewH, digit.h * viewH * growth, 1e-2f)
        assertEquals(0.25f * viewH, o.h * viewH * growth, 1e-2f)
        assertEquals(0.25f * 1.25f * viewH, o.y * viewH * growth, 1e-2f)
        // Capped at half a row.
        val capped = LayoutMutations.withNumberRow(qwerty(), 0.9f).keys.first { it.id == "num_9" }
        assertEquals(capped.h * 0.5f, LayoutMutations.withNumberRow(qwerty(), 0.9f).keys.first { it.id == "q" }.y - capped.h, 1e-6f)
    }

    @Test
    fun theDigitsSizeChangesOnlyTheDigits() {
        val growth = LayoutMutations.boardGrowth(numberRow = true, numberGap = 0.25f, numberSize = 0.7f)
        val l = LayoutMutations.withNumberRow(qwerty(), 0.25f, 0.7f)
        assertEquals(0.25f * 0.7f * viewH, l.keys.first { it.id == "num_1" }.h * viewH * growth, 1e-2f)
        assertEquals(0.25f * viewH, l.keys.first { it.id == "q" }.h * viewH * growth, 1e-2f)
    }

    @Test
    fun bothRowsTogetherKeepEveryLetterDistanceInKw() {
        // The order AlphaLayouts applies them in, on the board the service grows to fit.
        val l = LayoutMutations.withNumberRow(LayoutMutations.withSpaceRowGap(qwerty(), 0.25f, 1.2f), 0.25f, 0.8f)
        val growth = LayoutMutations.boardGrowth(true, 0.25f, 0.8f, 0.25f, 1.2f)
        val base = geometry(qwerty(), viewH)
        val grown = geometry(l, viewH * growth)
        for (a in 0 until 26) for (b in 0 until 26) {
            assertTrue(abs(base.keyDist(a, b) - grown.keyDist(a, b)) < 1e-3f)
        }
        assertEquals(1f, l.keys.maxOf { it.y + it.h }, 1e-5f)
        assertEquals(0.25f * viewH, l.keys.first { it.id == "q" }.h * viewH * growth, 1e-2f)
    }

    @Test
    fun eachDigitSitsOverTheLetterItWasMeantFor() {
        val map = LayoutMutations.digitLetters(LayoutMutations.withNumberRow(qwerty(), 0.25f))
        assertEquals("1234567890".zip("qwertyuiop").toMap(), map)
        assertEquals(emptyMap<Char, Char>(), LayoutMutations.digitLetters(qwerty()))
    }
}
