package com.kinetica.keyboard

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinetica.keyboard.ime.SiteDemoActivity
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.ui.ViewProbe
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: a scripted tour that records the website's screenshots and videos on an emulator
 * (.github/workflows/site-media.yml). It turns Duet on, opens a plain note field, types with two
 * synthetic thumbs (real multi-touch events with real timing), and saves PNGs and MP4s to
 * /sdcard/Download/site for the workflow to pull. Each scene is independent: one that fails
 * logs and the tour goes on.
 */
@RunWith(AndroidJUnit4::class)
class SiteMediaTour {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui: UiAutomation = inst.uiAutomation
    private val app: Context = inst.targetContext
    private val out = "/sdcard/Download/site"
    private var activity: SiteDemoActivity? = null

    @Test
    fun tour() {
        sh("mkdir -p $out")
        val ime = "${app.packageName}/com.kinetica.keyboard.ime.KineticaIME"
        sh("ime enable $ime")
        sh("ime set $ime")
        // The emulator has a hardware keyboard, which hides soft keyboards unless this is on.
        sh("settings put secure show_ime_with_hard_keyboard 1")
        sh("settings get secure default_input_method")
        statusBarDemoMode()
        prefs {
            putBoolean(Prefs.SETUP_DONE, true)
            putBoolean(Prefs.AUTOSPACE, false)
            putBoolean(Prefs.HARPER_GRAMMAR, false)
        }

        // The dictionary loads in the background; swipes before it are dropped.
        open("Notes")
        pause(25_000)

        scene("two-thumbs") {
            open("Notes")
            typeWords("we can type")
            // Hold both thumbs down mid-word so both trails are on screen.
            typeWord("something", pauseAtFraction = 0.72) { shot("two-thumbs") }
            tapKey("space")
        }

        scene("duet-demo") {
            open("Notes")
            startRecording("duet-demo")
            pause(900)
            typeWords("this keyboard reads both thumbs at the same time and it feels like magic")
            pause(1500)
            stopRecording()
        }

        scene("duet-spacing") {
            prefs { putBoolean(Prefs.AUTOSPACE, true) }
            open("Notes")
            startRecording("duet-spacing")
            pause(900)
            // A pecked word, then a swipe straight after with no space: two words.
            tapWord("to"); pause(120); typeWord("help"); pause(700)
            typeWord("you"); pause(700)
            typeWord("today"); pause(1800)
            stopRecording()
            prefs { putBoolean(Prefs.AUTOSPACE, false) }
        }

        for (theme in listOf("preset_ocean", "preset_sunset", "preset_paper", "preset_lavender", "preset_forest")) {
            scene("theme-$theme") {
                prefs { putString(Prefs.THEME_MODE, theme) }
                open("Notes")
                typeWords("pick a look that suits you")
                pause(600)
                shot("themes-${theme.removePrefix("preset_")}")
            }
        }
        prefs { remove(Prefs.THEME_MODE) }

        scene("grammar") {
            prefs { putBoolean(Prefs.HARPER_GRAMMAR, true) }
            open("Notes")
            setText("This is a example of the ")
            typeWords("grammar")
            pause(1500)
            shot("grammar")
            prefs { putBoolean(Prefs.HARPER_GRAMMAR, false) }
        }

        scene("clipboard") {
            open("Notes")
            onMain {
                val cm = app.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("address", "221B Baker Street, London"))
            }
            pause(1200)
            shot("clipboard")
        }

        scene("word-menu") {
            open("Notes")
            typeWords("see you")
            typeWord("there")
            pause(500)
            val bar = onMain { ViewProbe.bar?.get()?.let { v -> IntArray(2).also { v.getLocationOnScreen(it) } to (v.width to v.height) } }
            if (bar != null) {
                val (loc, size) = bar
                val p = PointF(loc[0] + size.first * 0.25f, loc[1] + size.second / 2f)
                hold(p, 2600) { shot("word-menu") }
            }
        }

        activity?.finish()
    }

    // ---- scenes and app state ----

    private fun scene(name: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            Log.e(TAG, "scene $name failed", t)
            stopRecording()
        }
    }

    private fun prefs(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        PreferenceManager.getDefaultSharedPreferences(app).edit().apply(edit).commit()
    }

    /** A fresh note screen, so the keyboard restarts its input view with the current settings. */
    private fun open(title: String) {
        activity?.let { a -> onMain { a.finish() } }
        pause(500)
        val intent = Intent(app, SiteDemoActivity::class.java)
            .putExtra(SiteDemoActivity.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = inst.startActivitySync(intent) as SiteDemoActivity
        activity = a
        pause(800)
        onMain {
            a.field.requestFocus()
            a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(a.field, 0)
        }
        // Wait for the keyboard to lay out its keys.
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (keys().containsKey("space")) break
            pause(200)
        }
        pause(600)
        val n = keys().size
        Log.i(TAG, "open($title): ${n} keys on screen")
        if (n == 0) {
            shot("debug-no-keyboard")
        }
    }

    private fun setText(text: String) {
        val a = activity ?: return
        onMain {
            a.field.setText(text)
            a.field.setSelection(text.length)
        }
        pause(400)
    }

    private fun keys(): Map<String, PointF> =
        onMain { ViewProbe.keyboard?.get()?.takeIf { it.isShown }?.keyCentersOnScreen() } ?: emptyMap()

    private fun statusBarDemoMode() {
        sh("settings put global sysui_demo_allowed 1")
        val b = "am broadcast -a com.android.systemui.demo -e command"
        sh("$b enter")
        sh("$b clock -e hhmm 0941")
        sh("$b battery -e level 100 -e plugged false")
        sh("$b network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4")
        sh("$b notifications -e visible false")
    }

    private fun shot(name: String) {
        sh("screencap -p $out/$name.png")
    }

    private fun startRecording(name: String) {
        // Not waited on: it runs until stopRecording() or its own time limit.
        ui.executeShellCommand("screenrecord --bit-rate 8000000 --time-limit 60 $out/$name.mp4").close()
        pause(500)
    }

    private fun stopRecording() {
        sh("pkill -INT screenrecord")
        pause(1500)
    }

    // ---- typing with two thumbs ----

    private fun typeWords(text: String) {
        for (w in text.split(' ')) {
            typeWord(w)
            // The emulator decodes far slower than a phone: let the word settle before the space.
            pause(900)
            tapKey("space")
            pause(350)
        }
    }

    private fun tapWord(word: String) {
        for (c in word) {
            val side = if (c in LEFT) 0 else 1
            val p = keys()[c.toString()] ?: continue
            inject(listOf(Stroke(side, listOf(p), 0, 55)))
            pause(110)
        }
    }

    private fun tapKey(id: String) {
        val p = keys()[id] ?: return
        inject(listOf(Stroke(1, listOf(p), 0, 50)))
    }

    private fun hold(p: PointF, ms: Long, whileHeld: () -> Unit) {
        inject(listOf(Stroke(1, listOf(p), 0, ms)), pauseAt = ms - 150, whileHeld)
    }

    /**
     * One word: its letters split into runs by board half, each run a swipe (or a tap) by that
     * side's thumb, the next run starting a little before the last one lifts, as real thumbs do.
     */
    private fun typeWord(word: String, pauseAtFraction: Double? = null, whilePaused: () -> Unit = {}) {
        val k = keys()
        val runs = ArrayList<Pair<Int, MutableList<PointF>>>()
        for (c in word.lowercase()) {
            val p = k[c.toString()] ?: continue
            val side = if (c in LEFT) 0 else 1
            if (runs.isNotEmpty() && runs.last().first == side) runs.last().second.add(p)
            else runs.add(side to mutableListOf(p))
        }
        if (runs.isEmpty()) {
            Log.w(TAG, "typeWord($word): no keys")
            return
        }
        val strokes = ArrayList<Stroke>()
        var t = 0L
        for ((side, pts) in runs) {
            val dur = if (pts.size == 1) 70L else 90L + 110L * (pts.size - 1)
            strokes.add(Stroke(side, pts, t, dur))
            t += maxOf(60L, dur - 30L)
        }
        val end = strokes.maxOf { it.start + it.dur }
        inject(strokes, pauseAtFraction?.let { (end * it).toLong() }, whilePaused)
    }

    private class Stroke(val pointer: Int, val path: List<PointF>, val start: Long, val dur: Long) {
        fun at(t: Long): PointF {
            if (path.size == 1) return path[0]
            val f = ((t - start).toFloat() / dur).coerceIn(0f, 1f) * (path.size - 1)
            val i = f.toInt().coerceAtMost(path.size - 2)
            val u = f - i
            // Ease within each segment so the thumb slows at keys, as a real swipe does.
            val e = (u * u * (3 - 2 * u))
            val a = path[i]; val b = path[i + 1]
            return PointF(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
        }
    }

    /** Plays [strokes] as real multi-touch events, in real time, at an 8 ms tick. */
    private fun inject(strokes: List<Stroke>, pauseAt: Long? = null, whilePaused: () -> Unit = {}) {
        if (strokes.isEmpty()) return
        val end = strokes.maxOf { it.start + it.dur }
        var base = SystemClock.uptimeMillis() + 20
        val down = LinkedHashMap<Int, PointF>()
        var downTime = 0L
        var paused = false
        var t = 0L
        while (t <= end) {
            val now = base + t
            val wait = now - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            // Moves for every pointer already down.
            var moved = false
            for (s in strokes) if (s.pointer in down && t > s.start && t < s.start + s.dur) {
                down[s.pointer] = s.at(t); moved = true
            }
            if (moved) send(MotionEvent.ACTION_MOVE, -1, down, downTime, now)
            // Lifts.
            for (s in strokes) if (s.pointer in down && t >= s.start + s.dur) {
                down[s.pointer] = s.at(s.start + s.dur)
                val action = if (down.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP
                send(action, s.pointer, down, downTime, now)
                down.remove(s.pointer)
            }
            // Touches.
            for (s in strokes) if (s.pointer !in down && t >= s.start && t < s.start + s.dur) {
                if (down.isEmpty()) downTime = now
                down[s.pointer] = s.at(s.start)
                val action = if (down.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
                send(action, s.pointer, down, downTime, now)
            }
            if (!paused && pauseAt != null && t >= pauseAt && down.isNotEmpty()) {
                paused = true
                val before = SystemClock.uptimeMillis()
                whilePaused()
                base += SystemClock.uptimeMillis() - before
            }
            t += 8
        }
        // Anything still down lifts at the end.
        for (id in down.keys.toList()) {
            val now = SystemClock.uptimeMillis()
            send(if (down.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id, down, downTime, now)
            down.remove(id)
        }
    }

    private fun send(action: Int, pointer: Int, down: Map<Int, PointF>, downTime: Long, time: Long) {
        val ids = down.keys.toList()
        val props = Array(ids.size) { i ->
            MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(ids.size) { i ->
            MotionEvent.PointerCoords().apply {
                val p = down.getValue(ids[i]); x = p.x; y = p.y; pressure = 1f; size = 0.1f
            }
        }
        val full = if (pointer >= 0 && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
            action or (ids.indexOf(pointer) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
        val ev = MotionEvent.obtain(
            downTime, time, full, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            ui.injectInputEvent(ev, false)
        } finally {
            ev.recycle()
        }
    }

    // ---- plumbing ----

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun sh(cmd: String): String {
        val out = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use {
            String(it.readBytes())
        }
        if (cmd.startsWith("ime") || cmd.startsWith("settings get")) Log.i(TAG, "$ $cmd -> ${out.trim()}")
        return out
    }

    private fun pause(ms: Long) = SystemClock.sleep(ms)

    private companion object {
        const val TAG = "SiteMediaTour"
        const val LEFT = "qwertasdfgzxcvb"
    }
}
