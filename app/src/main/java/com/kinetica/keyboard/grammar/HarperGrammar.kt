package com.kinetica.keyboard.grammar

import android.util.Log

/**
 * One Harper checker for the process, built on first use (Harper's curated dictionary takes
 * about half a second on a phone) and rebuilt when the dialect changes. Calls are serialized:
 * the native checker is not safe to share between threads, and the spell checker's binder
 * threads can ask at once.
 *
 * When the library is missing (a build made without the Rust step) every call returns null and
 * the grammar results simply never appear.
 */
object HarperGrammar {
    private const val TAG = "HarperGrammar"

    private var handle = 0L
    private var dialect = -1
    private var unavailable = false

    /** [text]'s problems, encoded for engine/HarperLints.parse, or null without the library. */
    @Synchronized
    fun lint(text: String, dialect: Int): String? {
        if (!ready(dialect)) return null
        return try {
            HarperNative.lint(handle, text)
        } catch (e: Throwable) {
            Log.w(TAG, "harper lint failed", e)
            null
        }
    }

    private fun ready(wanted: Int): Boolean {
        if (unavailable) return false
        if (handle != 0L && dialect == wanted) return true
        return try {
            if (handle == 0L) System.loadLibrary(HarperNative.LIBRARY) else HarperNative.destroy(handle)
            handle = HarperNative.create(wanted)
            dialect = wanted
            handle != 0L
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "harper library missing; grammar results off", e)
            unavailable = true
            handle = 0L
            false
        }
    }
}
