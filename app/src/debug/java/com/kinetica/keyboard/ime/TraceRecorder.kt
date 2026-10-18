package com.kinetica.keyboard.ime

import android.content.Context
import android.util.Log
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.trace.SwipeTrace
import com.kinetica.keyboard.engine.trace.SwipeTraceRecorder
import java.io.File
import java.io.IOException

/**
 * Debug build: the decode trace goes to a file as well as to logcat, and a crash leaves its
 * stack beside it.
 *
 * A file takes the computer out of the loop: type normally for a week, then export. A trace taken
 * tethered to a desk records typing at a desk, and the engine work is about how two thumbs move
 * in ordinary use.
 */
object TraceRecorder {

    /**
     * One rotation at this size, so the file cannot grow without bound and still holds far more
     * than a session: the largest capture so far is 222 KB.
     */
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val DIR = "trace"
    private const val NAME = "kinetica_trace.log"
    private const val CRASH_NAME = "kinetica_crash.log"

    /**
     * The keyboard's state for a crash line, without any text: lengths and flags only. Set by
     * the service; read only when a crash is written.
     */
    @Volatile
    var crashState: (() -> String)? = null

    private val lock = Any()

    @Volatile
    private var file: File? = null

    @Volatile
    var lines: Long = 0L
        private set

    /**
     * On by default: this build has one purpose, and a recorder armed by hand gets forgotten and
     * a week of typing yields no data.
     */
    @Volatile
    var recording: Boolean = true

    /**
     * Idempotent: the keyboard and the trace screen both call it, and whichever runs first in a
     * fresh process sets things up. Before the screen called it, a screen opened before the
     * keyboard (right after a reinstall, or after the process was killed) showed word traces off
     * and dropped the switch, because there were no preferences to write it to, and exported
     * nothing.
     */
    @Synchronized
    fun install(context: Context) {
        if (file != null) return
        val dir = File(context.filesDir, DIR)
        val f = File(dir, NAME)
        file = f
        lines = countLines(f)
        words.install(context, File(dir, WORDS_NAME))
        DecodeTrace.sink = { m ->
            // A practice comparison re-decodes a finished buffer twice; its lines
            // would read as live decodes, so they are dropped here.
            if (!NeuralRerank.comparing.get()) {
                Log.d("KineticaTrace", m)
                append(m)
            }
        }
        // A restart reads as a gap in the trace otherwise: the line says where it began.
        append("process start at=${System.currentTimeMillis()}")
        installCrashHandler(File(dir, CRASH_NAME))
    }

    /**
     * Writes an uncaught exception's stack to its own file, then hands it on, so Android still
     * restarts the keyboard. Its own file and no lock: the crashing thread may hold [lock].
     */
    private fun installCrashHandler(crashFile: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val state = try {
                    crashState?.invoke() ?: "-"
                } catch (inner: RuntimeException) {
                    "unreadable"
                }
                crashFile.parentFile?.mkdirs()
                crashFile.appendText(
                    "CRASH at=${System.currentTimeMillis()} thread=${thread.name} state=$state\n" +
                        e.stackTraceToString() + "\n",
                )
            } catch (write: IOException) {
                Log.w("KineticaTrace", "crash write failed", write)
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun append(message: String) {
        if (!recording) return
        val f = file ?: return
        synchronized(lock) {
            try {
                f.parentFile?.mkdirs()
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText(message + "\n")
                lines++
            } catch (e: IOException) {
                // A failed write must never take the keyboard down with it.
                Log.w("KineticaTrace", "trace write failed", e)
            }
        }
    }

    private fun rotate(f: File) {
        val old = File(f.parentFile, "$NAME.1")
        if (old.exists()) old.delete()
        f.renameTo(old)
        lines = 0L
    }

    /** Oldest first, so an exported trace reads in the order it happened. */
    fun readAll(): String {
        val f = file ?: return ""
        synchronized(lock) {
            val previous = File(f.parentFile, "$NAME.1")
            val head = if (previous.exists()) previous.readText() else ""
            val tail = if (f.exists()) f.readText() else ""
            val crashes = File(f.parentFile, CRASH_NAME)
            val crashText = if (crashes.exists()) "\n--- crashes ---\n" + crashes.readText() else ""
            return head + tail + crashText
        }
    }

    fun sizeBytes(): Long {
        val f = file ?: return 0L
        val previous = File(f.parentFile, "$NAME.1")
        return (if (f.exists()) f.length() else 0L) + (if (previous.exists()) previous.length() else 0L)
    }

    fun clear() {
        val f = file ?: return
        synchronized(lock) {
            File(f.parentFile, "$NAME.1").delete()
            File(f.parentFile, CRASH_NAME).delete()
            f.delete()
            lines = 0L
        }
    }

    // ------------------------------------------------------ word traces (v1)

    /**
     * The v1 word trace: every raw touch sample and the committed word, one JSON
     * line per word (see SwipeTrace). Separate from the decode log above but, like
     * it, on by default in this developer build: the build exists to collect these,
     * and each new CI build must be installed fresh (its debug key changes), which
     * cleared a switch that defaulted off and lost whole batches. The trace screen
     * says what is recorded and turns it off. Practice mode records regardless.
     */
    val words = WordTraces()

    /** The keyboard's engine: its pointer samples become the gestures in each line. */
    fun attachEngine(engine: GestureEngine, info: TraceInfo) {
        words.attach(engine, info)
    }

    /** Each composer the keyboard builds (one per dictionary load). */
    fun attachComposer(composer: WordComposer) {
        composer.observer = words.recorder
    }

    /** How the next committed word was committed: picked, tentative, autocorrect or typed. */
    fun label(how: String) {
        words.recorder?.nextHow = how
    }

    fun correction(from: String, to: String) {
        words.recorder?.onCorrection(from, to)
    }

    /** A space, backspace, enter or punctuation key pressed, at uptime [t] ms (see SwipeTrace.encodeKey). */
    fun key(name: String, t: Long) {
        words.key(name, t)
    }

    class WordTraces {
        private val lock = Any()
        private var prefs: android.content.SharedPreferences? = null
        private var file: File? = null
        var recorder: SwipeTraceRecorder? = null
            private set

        @Volatile
        var lines: Long = 0L
            private set

        /** Set by the practice screen while it is showing: its current prompt. */
        @Volatile
        var practiceTarget: String? = null

        var enabled: Boolean
            get() = prefs?.getBoolean(PREF_ENABLED, true) != false
            set(on) { prefs?.edit()?.putBoolean(PREF_ENABLED, on)?.apply() }

        internal fun install(context: Context, f: File) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            file = f
            lines = TraceRecorder.countLines(f)
        }

        internal fun attach(engine: GestureEngine, info: TraceInfo) {
            this.info = info
            val r = SwipeTraceRecorder(
                {
                    val beta = NeuralRerank.liveBeta
                    SwipeTrace.Config(
                        info.language(), info.alternate(), info.britishSpelling(),
                        info.personal(), info.dictOverride(),
                        beta, if (beta != 0f) NeuralRerank.MODEL_NAME else null,
                    )
                },
                ::append,
            )
            r.enabled = { (enabled || practiceTarget != null) && !info.suppressed() }
            r.target = { practiceTarget }
            r.compare = NeuralRerank::compare
            recorder = r
            engine.observer = r
        }

        private var info: TraceInfo? = null

        /** Records a non-letter key press while word traces are recording. */
        fun key(name: String, t: Long) {
            val i = info ?: return
            if (!(enabled || practiceTarget != null) || i.suppressed()) return
            append(SwipeTrace.encodeKey(name, t))
        }

        /** Withdraws the most recent word line (the practice screen's "bad swipe"). */
        fun discardLast() {
            if (lines > 0) append(SwipeTrace.encodeDiscard())
        }

        private fun append(line: String) {
            val f = file ?: return
            synchronized(lock) {
                try {
                    f.parentFile?.mkdirs()
                    if (f.length() > MAX_WORD_BYTES) {
                        val old = File(f.parentFile, f.name + ".1")
                        if (old.exists()) old.delete()
                        f.renameTo(old)
                        lines = 0L
                    }
                    f.appendText(line + "\n")
                    lines++
                } catch (e: IOException) {
                    Log.w("KineticaTrace", "word trace write failed", e)
                }
            }
        }

        fun readAll(): String {
            val f = file ?: return ""
            synchronized(lock) {
                val previous = File(f.parentFile, f.name + ".1")
                return (if (previous.exists()) previous.readText() else "") +
                    (if (f.exists()) f.readText() else "")
            }
        }

        fun sizeBytes(): Long {
            val f = file ?: return 0L
            val previous = File(f.parentFile, f.name + ".1")
            return (if (f.exists()) f.length() else 0L) + (if (previous.exists()) previous.length() else 0L)
        }

        fun clear() {
            val f = file ?: return
            synchronized(lock) {
                File(f.parentFile, f.name + ".1").delete()
                f.delete()
                lines = 0L
            }
        }
    }

    private const val WORDS_NAME = "words_v1.jsonl"
    private const val PREFS = "trace_v1"
    private const val PREF_ENABLED = "enabled"

    /** Two files of this size: at ~3 KB a word, about 5 000 words before the oldest go. */
    private const val MAX_WORD_BYTES = 8L * 1024 * 1024

    private fun countLines(f: File): Long =
        if (!f.exists()) 0L else try {
            f.useLines { seq -> seq.count().toLong() }
        } catch (e: IOException) {
            0L
        }
}
