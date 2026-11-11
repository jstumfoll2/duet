package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where this user's thumbs aim for each key, learned from committed words, and the board moved
 * there for the decoder (issue #94).
 *
 * The decoder reads every touch against key centres. Thumbs do not aim at them: in the
 * developer's traces swipes and taps meant for `a` land 0.4 kw right of its centre, `p` 0.3 kw
 * left, `n` 0.3 kw low. [TouchModel] moves taps one at a time and switches itself off unless it
 * helps taps alone; this model moves the keys instead, so a swipe's ends, its corners and a tap
 * are all read against the same aimed board. The keyboard still draws the keys where they are.
 *
 * One offset per key, from two kinds of evidence where the letter meant is known:
 * - a word drawn as one swipe: its first sample against the word's first letter, its last
 *   against the last;
 * - a word typed as one tap per letter: each tap against its letter.
 * A touch more than [LEARN_GATE_KW] from its key on either axis teaches nothing (a wrong commit,
 * not a habit). The offset is the mean shrunk by [PRIOR] pseudo-touches at zero, times
 * [strength], capped at [MAX_OFFSET_KW]; counts saturate at [MAX_COUNT] so a new grip or layout
 * takes over.
 *
 * [aimed] and [learn] take the board as drawn: offsets are measured from the drawn centres,
 * never from an aimed board, or each round would learn only what the last left. The gesture
 * engine and the traces stay on the drawn board too; only the decoder reads the aimed one.
 *
 * Thread contract: [learn], [restore], [reset] on one thread; [aimed] from any, on a snapshot.
 */
class AimModel(private val letterCount: Int = Alphabet.MAX_SIZE) {
    private val n = FloatArray(letterCount)
    private val sx = FloatArray(letterCount)
    private val sy = FloatArray(letterCount)

    private class Aimed(val base: KeyboardGeometry, val version: Int, val g: KeyboardGeometry)

    @Volatile private var version = 0
    @Volatile private var dx = FloatArray(letterCount)
    @Volatile private var dy = FloatArray(letterCount)
    @Volatile private var cache: Aimed? = null

    /** Touches learned so far. */
    @Volatile var learned: Int = 0
        private set

    /** How much of the learned offset the board moves by, 0 (off) to 1. */
    @Volatile var strength: Float = KineticaConstants.AIM_MODEL_STRENGTH
        set(value) {
            field = value
            publish()
        }

    /** The offset (kw) [code]'s key is moved by. */
    fun offset(code: Int): Pair<Float, Float> = dx[code] to dy[code]

    /**
     * [base], the board as drawn, with its keys moved to where the thumbs aim; [base] itself
     * while nothing is learned or [strength] is 0.
     */
    fun aimed(base: KeyboardGeometry): KeyboardGeometry {
        if (learned == 0 || strength <= 0f) return base
        val c = cache
        val v = version
        if (c != null && c.base === base && c.version == v) return c.g
        val g = base.shifted(dx, dy)
        cache = Aimed(base, v, g)
        return g
    }

    /**
     * Learns from a committed [word] (codes of [base]'s alphabet) and the [tokens] that typed it.
     * True when the word taught something.
     */
    fun learn(word: IntArray, tokens: List<InputToken>, base: KeyboardGeometry): Boolean {
        if (word.isEmpty() || tokens.isEmpty()) return false
        if (word.any { it !in 0 until minOf(letterCount, base.letterCount) || !base.hasKey(it) }) return false
        var used = false
        if (tokens.size == 1 && tokens[0] is SwipeToken && word.size >= 2) {
            val path = (tokens[0] as SwipeToken).rawPath
            if (path.size >= 2) {
                used = add(word[0], path[0].x, path[0].y, base) or used
                used = add(word[word.size - 1], path[path.size - 1].x, path[path.size - 1].y, base) or used
            }
        } else if (tokens.size == word.size && tokens.all { it is TapToken }) {
            val taps = tokens.sortedBy { it.tStart }
            for (i in taps.indices) {
                val t = taps[i] as TapToken
                used = add(word[i], t.x, t.y, base) or used
            }
        }
        if (used) publish()
        return used
    }

    private fun add(code: Int, x: Float, y: Float, g: KeyboardGeometry): Boolean {
        val ox = x - g.centerX(code)
        val oy = y - g.centerY(code)
        if (abs(ox) > LEARN_GATE_KW || abs(oy) > LEARN_GATE_KW) return false
        if (n[code] >= MAX_COUNT) {
            val f = (MAX_COUNT - 1f) / n[code]
            n[code] *= f
            sx[code] *= f
            sy[code] *= f
        }
        n[code] += 1f
        sx[code] += ox
        sy[code] += oy
        learned++
        return true
    }

    private fun publish() {
        val x = FloatArray(letterCount)
        val y = FloatArray(letterCount)
        for (c in 0 until letterCount) {
            var ox = sx[c] / (n[c] + PRIOR) * strength
            var oy = sy[c] / (n[c] + PRIOR) * strength
            val len = sqrt(ox * ox + oy * oy)
            if (len > MAX_OFFSET_KW) {
                ox *= MAX_OFFSET_KW / len
                oy *= MAX_OFFSET_KW / len
            }
            x[c] = ox
            y[c] = oy
        }
        dx = x
        dy = y
        version++
    }

    fun reset() {
        n.fill(0f)
        sx.fill(0f)
        sy.fill(0f)
        learned = 0
        publish()
    }

    /**
     * The sums as text for on-device storage: `a1;touches;code,n,sx,sy;...`. Per-key averages
     * of where touches land, nothing of what was typed.
     */
    fun save(): String {
        val sb = StringBuilder("a1;").append(learned)
        for (c in 0 until letterCount) {
            if (n[c] <= 0f) continue
            sb.append(';').append(c).append(',').append(n[c]).append(',').append(sx[c]).append(',').append(sy[c])
        }
        return sb.toString()
    }

    /** Restores [save] output; anything malformed is ignored. */
    fun restore(text: String?) {
        reset()
        if (text == null || !text.startsWith("a1;")) return
        val parts = text.split(';')
        var total = 0
        for (p in parts.drop(2)) {
            val f = p.split(',')
            if (f.size != 4) continue
            val c = f[0].toIntOrNull() ?: continue
            if (c !in 0 until letterCount) continue
            n[c] = f[1].toFloatOrNull() ?: continue
            sx[c] = f[2].toFloatOrNull() ?: 0f
            sy[c] = f[3].toFloatOrNull() ?: 0f
            total += n[c].toInt()
        }
        learned = parts.getOrNull(1)?.toIntOrNull() ?: total
        publish()
    }

    companion object {
        /**
         * [tokens] with each tap keyed by the key of [aimed] it landed on. The gesture engine
         * keys a tap on the board as drawn; read against the aimed board, a tap 0.4 kw right of
         * `a` is an `a`, not the `s` whose drawn rect it touched. A tap on no aimed key keeps
         * its key. Swipes are returned as they are: the decoder reads their paths, not keys.
         */
        fun rekey(tokens: List<InputToken>, aimed: KeyboardGeometry): List<InputToken> {
            if (tokens.none { it is TapToken }) return tokens
            return tokens.map { t ->
                if (t !is TapToken) return@map t
                val k = aimed.keyAt(t.x, t.y)
                if (k < 0 || k == t.code) t else t.copy(code = k, hints = t.hints or (1L shl t.code))
            }
        }

        /** Pseudo-touches at the key's centre: a key seen a few times barely moves. */
        const val PRIOR = 20f

        /** Keys never move farther than this (kw). */
        const val MAX_OFFSET_KW = 0.5f

        /** A learning touch must be within this of its key's centre on each axis (kw). */
        const val LEARN_GATE_KW = 1.0f

        /** Per-key count where old touches start to fade. */
        const val MAX_COUNT = 400f
    }
}
