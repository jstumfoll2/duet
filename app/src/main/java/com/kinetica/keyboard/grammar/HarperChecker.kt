package com.kinetica.keyboard.grammar

import android.os.Handler
import android.util.Log
import com.kinetica.keyboard.engine.DecodeTrace
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Harper off the main thread. The library loads and the checker builds on the first
 * request (Harper's curated dictionary takes most of a second), then each check of a
 * sentence takes a few milliseconds. Only the newest request runs: one queued behind it is
 * skipped, since the text it read has already moved on.
 *
 * When the library is missing (a build made without the Rust step) the checker turns itself
 * off and every request is dropped; typing is never affected.
 */
class HarperChecker(private val main: Handler, private val dialect: Int) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "duet-harper") }
    private val latest = AtomicInteger()
    private var handle = 0L

    @Volatile
    var unavailable = false
        private set

    /** Checks [text] and hands the encoded result to [onResult] on the main thread. */
    fun lint(text: String, onResult: (String) -> Unit) {
        if (unavailable) return
        val gen = latest.incrementAndGet()
        worker.execute {
            if (gen != latest.get() || !ready()) return@execute
            val started = System.nanoTime()
            val encoded = try {
                HarperNative.lint(handle, text)
            } catch (e: Throwable) {
                Log.w(TAG, "harper lint failed", e)
                return@execute
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            DecodeTrace.log { "  harper lint chars=${text.length} ms=$ms n=${encoded.count { it == '\n' } + if (encoded.isEmpty()) 0 else 1}" }
            main.post { if (gen == latest.get()) onResult(encoded) }
        }
    }

    /** Frees the native checker; the object is spent afterwards. */
    fun close() {
        latest.incrementAndGet()
        worker.execute {
            if (handle != 0L) HarperNative.destroy(handle)
            handle = 0L
        }
        worker.shutdown()
    }

    private fun ready(): Boolean {
        if (handle != 0L) return true
        if (unavailable) return false
        return try {
            System.loadLibrary(HarperNative.LIBRARY)
            val started = System.nanoTime()
            handle = HarperNative.create(dialect)
            DecodeTrace.log {
                "  harper ready version=${HarperNative.version()} ms=${(System.nanoTime() - started) / 1_000_000}"
            }
            handle != 0L
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "harper library missing; grammar suggestions off", e)
            unavailable = true
            false
        }
    }

    private companion object {
        const val TAG = "HarperChecker"
    }
}
