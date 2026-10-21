package com.kinetica.keyboard.ime

import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethod
import android.widget.EditText
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.ui.FloatingFrame
import com.kinetica.keyboard.ui.InputContainerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The service itself, under Robolectric: created, given its input view, started in fields and
 * fed preference changes, as the system and the settings screen do. The JVM suite reaches only
 * pure helpers, so before this the CI build was the one check on the service's wiring (init
 * order, preference listeners, view building).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeWiringTest {

    private fun service(): KineticaIME = Robolectric.buildService(KineticaIME::class.java).create().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun textField(type: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply {
        inputType = type
        packageName = "com.example.notes"
        fieldId = 1
    }

    /** A real editor behind the service's input connection, as an app's text box would be. */
    private fun bindEditor(ime: KineticaIME, text: String): EditText {
        val edit = EditText(ime)
        edit.setText(text)
        edit.setSelection(text.length)
        val ic: InputConnection = edit.onCreateInputConnection(EditorInfo())!!
        val method = ime.onCreateInputMethodInterface() as InputMethod
        method.bindInput(InputBinding(ic, null, 0, 0))
        return edit
    }

    private fun prefs(ime: KineticaIME) = PreferenceManager.getDefaultSharedPreferences(ime)

    @Test
    fun theServiceStartsAndBuildsItsInputView() {
        val ime = service()
        val view = ime.onCreateInputView()
        assertTrue(view is FloatingFrame)
        assertTrue((view as FloatingFrame).content is InputContainerView)
        idle()
    }

    @Test
    fun itStartsAndFinishesInTextAndPasswordFields() {
        val ime = service()
        ime.onCreateInputView()
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_NUMBER,
        )) {
            val info = textField(type)
            ime.onStartInput(info, false)
            ime.onStartInputView(info, false)
            idle()
            ime.onFinishInputView(true)
            ime.onFinishInput()
        }
        idle()
    }

    @Test
    fun settingsChangedWhileItRunsApplyWithoutATrip() {
        val ime = service()
        ime.onCreateInputView()
        val info = textField()
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        val p = prefs(ime)
        p.edit().putBoolean(Prefs.NUMBER_ROW, false).commit()
        p.edit().putBoolean(Prefs.EMOJI_SUGGESTIONS, true).commit()
        p.edit().putBoolean(Prefs.INCOGNITO, true).commit()
        p.edit().putBoolean(Prefs.PECK_MODE, true).commit()
        p.edit().putBoolean(Prefs.PECK_MODE, false).commit()
        p.edit().putBoolean(Prefs.RECENT_WORDS, true).commit()
        p.edit().putString(Prefs.LAYOUT_MODE, "left").commit()
        p.edit().putBoolean(Prefs.FLOATING, true).commit()
        p.edit().putInt(Prefs.FLOAT_WIDTH_PCT, 60).commit()
        p.edit().putBoolean(Prefs.FLOATING, false).commit()
        idle()
    }

    @Test
    fun theFloatingSettingFloatsAndDocksTheLiveView() {
        val ime = service()
        val frame = ime.onCreateInputView() as FloatingFrame
        assertFalse(frame.floating)
        prefs(ime).edit().putBoolean(Prefs.FLOATING, true).commit()
        idle()
        assertTrue(frame.floating)
        prefs(ime).edit().putBoolean(Prefs.FLOATING, false).commit()
        idle()
        assertFalse(frame.floating)
    }

    @Test
    fun emojiSuggestionsAreOffUntilTurnedOn() {
        val ime = service()
        val frame = ime.onCreateInputView() as FloatingFrame
        val bar = (frame.content as InputContainerView).suggestionBar
        bindEditor(ime, "I want pizza ")
        val info = textField()
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        ime.onUpdateSelection(0, 0, 13, 13, -1, -1)
        idle()
        assertEquals(emptyList<String>(), bar.emoji)
    }

    /** A tap at [x], [y], lifted plainly (flags 0) or as the system lifts a touch it rejected. */
    private fun tap(view: View, x: Float, y: Float, flags: Int) {
        val t = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        view.onTouchEvent(
            MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags),
        )
    }

    @Test
    fun aTouchTheSystemCancelsAsAccidentalTypesNothing() {
        val ime = service()
        val frame = ime.onCreateInputView() as FloatingFrame
        val kv = (frame.content as InputContainerView).keyboardView
        val edit = bindEditor(ime, "")
        val info = textField()
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        kv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        kv.layout(0, 0, 1080, 800)
        idle()
        val h = kv.keyCenter("h")!!
        tap(kv, h.x, h.y, MotionEvent.FLAG_CANCELED)
        idle()
        assertEquals("", edit.text.toString())
        // The same tap lifted plainly types its letter.
        tap(kv, h.x, h.y, 0)
        idle()
        assertTrue(edit.text.toString(), edit.text.toString().startsWith("h"))
    }

    @Test
    fun aBarEmojiGoesInAfterItsWord() {
        val ime = service()
        val frame = ime.onCreateInputView() as FloatingFrame
        val bar = (frame.content as InputContainerView).suggestionBar
        val edit = bindEditor(ime, "I want pizza")
        val info = textField()
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        idle()
        bar.listener!!.onEmojiPicked("🍕")
        idle()
        assertEquals("I want pizza 🍕 ", edit.text.toString())
    }
}
