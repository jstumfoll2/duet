package com.kinetica.keyboard.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.provider.Telephony
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo

/**
 * Reads the one-time codes of recent text messages ([OneTimeCodes]) for the bar.
 *
 * Only with the user's opt-in ([com.kinetica.keyboard.settings.Prefs.SMS_CODES]) and the
 * READ_SMS permission, and only the inbox rows of the last [OneTimeCodes.MAX_AGE_MS]: body and
 * date, nothing else, nothing kept. While a code field is open, [watch] re-reads when a message
 * arrives, so a code that comes after the field opened still reaches the bar.
 */
class SmsCodes(private val context: Context, private val handler: Handler) {

    private var observer: ContentObserver? = null

    fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** The codes of messages received in the last [OneTimeCodes.MAX_AGE_MS], newest first. Blocking: call off the main thread. */
    fun read(nowMs: Long = System.currentTimeMillis()): List<OneTimeCodes.Code> {
        if (!allowed()) return emptyList()
        val messages = ArrayList<OneTimeCodes.Message>()
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?",
                arrayOf((nowMs - OneTimeCodes.MAX_AGE_MS).toString()),
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && messages.size < MAX_MESSAGES) {
                    val body = c.getString(0) ?: continue
                    messages.add(OneTimeCodes.Message(body, c.getLong(1)))
                }
            }
        } catch (e: RuntimeException) {
            // A revoked permission or a phone with no SMS provider: no codes, never a crash.
            Log.w(TAG, "could not read messages", e)
            return emptyList()
        }
        return OneTimeCodes.recent(messages, nowMs)
    }

    /** Calls [callback] on the main thread whenever the message store changes, until [unwatch]. */
    fun watch(callback: () -> Unit) {
        unwatch()
        if (!allowed()) return
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = callback()
        }
        try {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o)
            observer = o
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not watch messages", e)
        }
    }

    fun unwatch() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    companion object {
        private const val TAG = "SmsCodes"
        private const val MAX_MESSAGES = 10

        /**
         * Whether [info] is a field to offer a code in: one whose hint or name says code
         * ([OneTimeCodes.isCodeField]), or a number or PIN field. A number field alone is a
         * weak sign, but only a code from the last ten minutes is ever offered, so a price field
         * at worst shows a chip nobody taps.
         */
        fun isCodeField(info: EditorInfo?): Boolean {
            info ?: return false
            if (OneTimeCodes.isCodeField(emptyList(), info.hintText, info.fieldName)) return true
            return info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
        }
    }
}
