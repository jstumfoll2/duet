package com.kinetica.keyboard.ime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.widget.Toast
import com.kinetica.keyboard.R

/**
 * An invisible screen that asks the phone's speech recognizer (Google, FUTO Voice Input, ...)
 * for one utterance and hands the words to the keyboard, which writes them once the field it
 * was asked in has focus again. Used when no voice typing keyboard is turned on. Duet itself
 * never listens: the recognizer app does, with its own microphone permission.
 */
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(VoiceInput.recognizerIntent(intent.getStringExtra(EXTRA_LANGUAGE)), REQUEST)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_none, Toast.LENGTH_LONG).show()
            VoiceInput.deliver(null, SystemClock.uptimeMillis())
            finish()
        }
    }

    @Deprecated("Activity result API needs AndroidX activity; this screen has nothing else.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        val heard = if (resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        VoiceInput.deliver(heard, SystemClock.uptimeMillis())
        finish()
    }

    companion object {
        const val EXTRA_LANGUAGE = "language"
        private const val REQUEST = 1
    }
}
