package com.kinetica.keyboard.ime

import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethod
import android.widget.EditText
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.ui.FloatingFrame
import com.kinetica.keyboard.ui.InputContainerView
import com.kinetica.keyboard.ui.KeyboardView
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
 * The service's editing paths, driven as a user would drive them (#184). These replace source
 * scans of KineticaIME.kt that checked a call was written in the right place; here the
 * behaviour itself is checked, so moving code between classes cannot break the guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeEditingTest {

    private class Harness(val ime: KineticaIME, val kv: KeyboardView, val container: InputContainerView, val edit: EditText)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The service with its view laid out, started in a text field that holds [text], cursor at its end. */
    private fun start(text: String, type: Int = InputType.TYPE_CLASS_TEXT, prefs: Map<String, Boolean> = emptyMap()): Harness {
        val ime = Robolectric.buildService(KineticaIME::class.java).create().get()
        PreferenceManager.getDefaultSharedPreferences(ime).edit().apply {
            for ((k, v) in prefs) putBoolean(k, v)
        }.commit()
        val frame = ime.onCreateInputView() as FloatingFrame
        val container = frame.content as InputContainerView
        val kv = container.keyboardView
        val edit = EditText(ime)
        edit.setText(text)
        edit.setSelection(text.length)
        val ic: InputConnection = edit.onCreateInputConnection(EditorInfo())!!
        (ime.onCreateInputMethodInterface() as InputMethod).bindInput(InputBinding(ic, null, 0, 0))
        val info = EditorInfo().apply {
            inputType = type
            packageName = "com.example.notes"
            fieldId = 1
            initialSelStart = text.length
            initialSelEnd = text.length
        }
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        kv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        kv.layout(0, 0, 1080, 800)
        idle()
        return Harness(ime, kv, container, edit)
    }

    private fun key(type: KeyType, output: String = "") = Key(type.name, type, output, output, 0f, 0f, 1f, 1f)

    /** The user moves the cursor to [to]: the editor moves it, then reports the move. */
    private fun moveCursor(h: Harness, to: Int) {
        val from = h.edit.selectionStart
        h.edit.setSelection(to)
        h.ime.onUpdateSelection(from, from, to, to, -1, -1)
        idle()
    }

    @Test
    fun aCursorMovedByTheUserRedecidesTheShift() {
        // Was CursorMoveCapsSourceTest: a period typed and the cursor moved back into the
        // sentence capitalized the next letter, and the reverse kept it lowercase.
        val h = start("Hello. world", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertFalse("mid-sentence at the start", h.kv.shiftShown)
        moveCursor(h, 7)
        assertTrue("after the period: a new sentence", h.kv.shiftShown)
        moveCursor(h, 3)
        assertFalse("back inside the first word", h.kv.shiftShown)
    }

    @Test
    fun aSpaceOnASymbolPageIsWrittenThenTheLettersComeBack() {
        // Was LettersAfterSpaceTest's source scan.
        val h = start("hi", prefs = mapOf(Prefs.LETTERS_AFTER_SPACE to true))
        val keys = h.kv.listener!!
        val letters = h.kv.shownBoard
        keys.onKeyTap(key(KeyType.MODE_SYMBOLS))
        idle()
        assertEquals("symbols", h.kv.shownBoard)
        keys.onKeyTap(key(KeyType.SPACE, " "))
        idle()
        assertEquals("hi ", h.edit.text.toString())
        assertEquals(letters, h.kv.shownBoard)
    }

    @Test
    fun withTheSwitchOffTheSymbolPageStays() {
        val h = start("hi", prefs = mapOf(Prefs.LETTERS_AFTER_SPACE to false))
        val keys = h.kv.listener!!
        keys.onKeyTap(key(KeyType.MODE_SYMBOLS))
        keys.onKeyTap(key(KeyType.SPACE, " "))
        idle()
        assertEquals("hi ", h.edit.text.toString())
        assertEquals("symbols", h.kv.shownBoard)
    }

    @Test
    fun aBackspaceIntoAWordKeepsTheBarOnThatWord() {
        // Was SeedMarksPendingSourceTest: until the reopened word's decode landed, the bar held
        // no words and no pending word, the shortcut row's cue, so the row blinked on every
        // backspace into a word.
        val h = start("hello wor")
        var waited = 0
        while (!h.ime.wordsReady && waited < 30_000) {
            Thread.sleep(50)
            waited += 50
            idle()
        }
        assertTrue("the dictionary never loaded", h.ime.wordsReady)
        h.kv.listener!!.onKeyTap(key(KeyType.BACKSPACE))
        // Before the decode can land: the bar must already know a word is in hand.
        assertEquals("hello wo", h.edit.text.toString())
        assertTrue(h.container.suggestionBar.wordPending)
    }
}
