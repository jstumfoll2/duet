package com.kinetica.keyboard.ime

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/** Finding the voice typing keyboard the voice key hands input to. */
internal object VoiceInput {

    /** InputMethodSubtype.getMode() of a voice typing subtype. */
    const val VOICE_MODE = "voice"

    /** How long dictated text waits for its field to come back before it is dropped. */
    const val PENDING_MS = 10_000L

    /**
     * The first enabled input method other than [ownPackage] with a voice subtype, as its id
     * and that subtype, or else a voice-only one that declares no subtypes, with none; null
     * when there is neither.
     */
    fun target(imm: InputMethodManager, ownPackage: String): Pair<String, InputMethodSubtype?>? {
        var target: Pair<String, InputMethodSubtype?>? = null
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == ownPackage) continue
            val voice = imm.getEnabledInputMethodSubtypeList(imi, true).firstOrNull { it.mode == VOICE_MODE }
            if (voice != null) {
                target = imi.id to voice
                break
            }
            // A voice-only input method that declares no subtypes.
            if (target == null && imi.subtypeCount == 0 && imi.id.contains("voice", ignoreCase = true)) {
                target = imi.id to null
            }
        }
        return target
    }

    /** The speech recognizer prompt, for phones whose voice typing is no keyboard of its own. */
    fun recognizerIntent(language: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .apply { if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }

    /** Whether some app on the phone answers [recognizerIntent]. */
    fun hasRecognizer(context: Context): Boolean =
        context.packageManager.queryIntentActivities(recognizerIntent(null), 0).isNotEmpty()

    /** Dictated text on its way back to the field it was asked for in. */
    data class Pending(val text: String, val packageName: String?, val fieldId: Int, val at: Long)

    @Volatile private var awaiting: Pending? = null

    @Volatile private var pending: Pending? = null

    /** Remembers the field [VoiceActivity] is about to dictate into. */
    fun expect(packageName: String?, fieldId: Int, now: Long) {
        awaiting = Pending("", packageName, fieldId, now)
        pending = null
    }

    /** Called by [VoiceActivity] with what was heard; empty or cancelled drops the request. */
    fun deliver(text: String?, now: Long) {
        val field = awaiting ?: return
        awaiting = null
        if (!text.isNullOrBlank()) pending = field.copy(text = text.trim(), at = now)
    }

    /** The dictated text for the field just started, once; null for any other field. */
    fun take(packageName: String?, fieldId: Int, now: Long): String? {
        val p = pending ?: return null
        if (now - p.at > PENDING_MS) {
            pending = null
            return null
        }
        if (p.packageName != packageName || p.fieldId != fieldId) return null
        pending = null
        return p.text
    }

    /** [text] as written after [before]: a space between it and a word, none after a space. */
    fun joined(before: CharSequence?, text: String): String {
        val last = before?.lastOrNull() ?: return text
        val first = text.firstOrNull() ?: return text
        if (last.isWhitespace() || last in "([{\"'“‘" || !first.isLetterOrDigit()) return text
        return " $text"
    }
}
