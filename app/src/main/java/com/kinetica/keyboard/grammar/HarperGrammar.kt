package com.kinetica.keyboard.grammar

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * One Harper checker for the process, built on first use (Harper's curated dictionary takes
 * about half a second on a phone) and rebuilt when the dialect changes or after Harper panics
 * (caught on the native side, never fatal). Calls are serialized:
 * the native checker is not safe to share between threads, and the spell checker's binder
 * threads can ask at once.
 *
 * When the library is missing (a build made without the Rust step) every call returns null and
 * the grammar results simply never appear.
 */
object HarperGrammar {
    private const val TAG = "HarperGrammar"

    /**
     * Longer text is not checked. The spell checker hands over any app's field whole, and a
     * cap keeps one call (and the lock it holds) short.
     */
    const val MAX_CHARS = 2_000

    /** The native side's answer when harper-core panicked (lib.rs PANICKED). */
    private const val PANICKED = "!panic"

    /** How long a caller waits for another's check by default before giving up on this one. */
    const val DEFAULT_WAIT_MS = 1_000L

    private val lock = ReentrantLock()
    private var handle = 0L
    private var dialect = -1
    private var unavailable = false

    /**
     * [text]'s problems, encoded for engine/HarperLints.parse, or null without the library,
     * over [MAX_CHARS], or when another call holds the checker for longer than [waitMs].
     */
    fun lint(text: String, dialect: Int, waitMs: Long = DEFAULT_WAIT_MS): String? {
        if (text.length > MAX_CHARS) return null
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            if (!ready(dialect)) return null
            val out = try {
                HarperNative.lint(handle, text)
            } catch (e: Throwable) {
                Log.w(TAG, "harper lint failed", e)
                return null
            }
            if (out == PANICKED) {
                // The checker may be half-updated after a panic: leave it (destroying it could
                // panic too) and build a fresh one on the next call.
                Log.w(TAG, "harper panicked on ${text.length} chars; checker rebuilt next call")
                handle = 0L
                this.dialect = -1
                return null
            }
            return out
        } finally {
            lock.unlock()
        }
    }

    private fun ready(wanted: Int): Boolean {
        if (unavailable) return false
        if (handle != 0L && dialect == wanted) return true
        return try {
            if (!loaded) {
                System.loadLibrary(HarperNative.LIBRARY)
                loaded = true
            } else if (handle != 0L) {
                HarperNative.destroy(handle)
            }
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

    private var loaded = false
}
