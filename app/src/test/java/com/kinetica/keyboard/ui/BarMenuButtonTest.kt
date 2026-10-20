package com.kinetica.keyboard.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The menu button at the bar's left edge opens the shortcut row mid-sentence, over the last
 * word's alternatives, and the row gives way again to a shortcut, a second tap or the next word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarMenuButtonTest {

    private val picked = ArrayList<String>()
    private val actions = ArrayList<Int>()

    private fun bar(menu: Boolean = true): SuggestionBarView {
        val v = SuggestionBarView(RuntimeEnvironment.getApplication())
        v.listener = object : SuggestionBarView.Listener {
            override fun onSuggestionPicked(word: String) { picked.add(word) }
            override fun onCorrectionPicked(replacement: String) { picked.add(replacement) }
            override fun onSuggestionReinforced(word: String, delta: Int) {}
            override fun onReinforceStep() {}
            override fun onRetype() {}
            override fun onBarAction(index: Int) { actions.add(index) }
            override fun onSuggestionBlocked(word: String, source: BarAdjust.BlockSource) {}
            override fun onRecentPicked(back: Int, word: String) {}
        }
        v.actions = listOf("a", "b", "c", "d")
        v.menuButton = menu
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1000, 120)
        // Mid-sentence: a word was just committed, so the bar shows its alternatives.
        v.showCorrection(listOf(SuggestionBarView.Suggestion("to", 0), SuggestionBarView.Suggestion("too", 0)), 0)
        return v
    }

    private fun tap(v: View, x: Float) {
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, 60f, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, 60f, 0))
    }

    @Test
    fun theButtonOpensTheRowOverTheWordsAndAShortcutClosesIt() {
        val v = bar()
        tap(v, 5f)
        assertTrue(v.menuOpen)
        // The far right cell is the last shortcut, not a correction.
        tap(v, 995f)
        assertEquals(listOf(3), actions)
        assertTrue(picked.isEmpty())
        assertFalse(v.menuOpen)
    }

    @Test
    fun aSecondTapClosesTheRowAndTheWordsAreBack() {
        val v = bar()
        tap(v, 5f)
        tap(v, 5f)
        assertFalse(v.menuOpen)
        tap(v, 995f)
        assertTrue(actions.isEmpty())
        assertEquals(listOf("too"), picked)
    }

    @Test
    fun theNextWordClosesTheRow() {
        val v = bar()
        tap(v, 5f)
        v.wordPending = true
        assertFalse(v.menuOpen)
        tap(v, 5f)
        v.setSuggestions(listOf(SuggestionBarView.Suggestion("feel", 0)))
        assertFalse(v.menuOpen)
    }

    @Test
    fun turnedOffTheEdgeIsAWordAgain() {
        val v = bar(menu = false)
        tap(v, 5f)
        assertFalse(v.menuOpen)
        assertTrue(actions.isEmpty())
    }
}
