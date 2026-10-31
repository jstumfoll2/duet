package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * Where this user's thumbs land relative to the keys they mean, learned from committed words.
 *
 * Thumbs hit off-centre in a consistent way: in the developer's traces taps landed 0.15 kw low
 * on the left thumb and 0.08 kw low on the right, 0.28 kw low on `h`, and 3.9% of the taps in
 * correctly typed words fell outside their key. The keyboard draws the keys where they are; this
 * model moves the touch back towards the key it was meant for before the decoder reads it.
 *
 * One offset per (thumb, key), estimated with two levels of shrinkage so a key seen a few times
 * borrows its thumb's average and a thumb seen a few times borrows nothing:
 *
 *     thumb = sum / (n + THUMB_PRIOR)
 *     key   = (keySum + KEY_PRIOR * thumb) / (keyN + KEY_PRIOR)
 *
 * capped at [MAX_OFFSET_KW]. Counts saturate at [MAX_COUNT] by scaling the sums down, so old
 * habits fade once the user's grip or phone changes.
 *
 * Correction ([correct]) picks, among the keys near the touch, the one whose shifted touch
 * lands closest to its centre, and returns that shifted point. A touch is never moved across
 * more than one key: the candidates are the keys within [SEARCH_KW] of the raw point, and a tap
 * changes key only when the shifted point sits [SWITCH_MARGIN_KW] inside the new key.
 *
 * The model checks itself before it acts. Every learned tap is first scored both ways, raw key
 * and corrected key against the letter the user meant, and correction switches on only once it
 * has been right more often than the raw key by [GAIN_MIN] of recent taps. The developer's own
 * offsets are real but small against their scatter (0.1 to 0.2 kw mean, 0.25 to 0.35 kw spread),
 * and on his traces no variant beat the raw key (94.65% of taps on the right key raw, 93.6% to
 * 94.7% corrected), so for him it stays off; a user with a stronger habit gets it.
 *
 * Learning ([learn]) takes a committed word and the taps that typed it, and only uses words where
 * every token is a tap, the count matches the letters, and each tap is within [LEARN_GATE_KW]
 * of its letter, so a mistyped or partly swiped word teaches nothing.
 *
 * Hints ([hint]) work while correction is still off. Each (thumb, key) also keeps the spread
 * of its taps, and a tap gets as hints the other keys it is plausible for: within [HINT_Z]
 * standard deviations of where that thumb's taps on that key land. The decoder tries a hinted
 * key at the fuzzy charge but never in place of the key that was hit, so frequency and context
 * decide and the raw reading is never lost. Words with swipes in them teach the spread too:
 * a tap counts for the letter of the word it sits nearest, when no other letter is close.
 * In the split layout the developer's right thumb lands 0.2 kw right and 0.3 kw low of `h`,
 * and a quarter of those taps read as `j` or `b`, past the fuzzy radius: `the` came out `ten`.
 *
 * Thread contract: [learn] and [restore] on one thread (main); [correct] and [apply] from any
 * thread, reading an immutable snapshot published through a volatile field.
 */
class TouchModel(private val letterCount: Int = Alphabet.MAX_SIZE) {

    private class Snapshot(
        val dx: FloatArray,
        val dy: FloatArray,
        val learned: Int,
        // Per key for hints: taps seen, mean offset and standard deviation (kw), lightly shrunk.
        val hn: FloatArray = FloatArray(dx.size),
        val mx: FloatArray = FloatArray(dx.size),
        val my: FloatArray = FloatArray(dx.size),
        val sdx: FloatArray = FloatArray(dx.size),
        val sdy: FloatArray = FloatArray(dx.size),
    )

    // Sums per [stream * letterCount + code]: n, sum dx, sum dy. Main thread only.
    private val n = FloatArray(2 * letterCount)
    private val sx = FloatArray(2 * letterCount)
    private val sy = FloatArray(2 * letterCount)

    // The same for hints, from swiped words too, plus sums of squares. Kept apart so the
    // correction and its self-check see only all-tap words, as before hints. Main thread only.
    private val hN = FloatArray(2 * letterCount)
    private val hSx = FloatArray(2 * letterCount)
    private val hSy = FloatArray(2 * letterCount)
    private val hSxx = FloatArray(2 * letterCount)
    private val hSyy = FloatArray(2 * letterCount)
    private var learnedWords = 0

    // Decayed counts of learned taps the raw key and the corrected key got right. Main thread.
    private var rawHits = 0f
    private var corHits = 0f
    private var scored = 0f

    @Volatile
    private var snap = Snapshot(FloatArray(2 * letterCount), FloatArray(2 * letterCount), 0)

    @Volatile
    private var earned = false

    /** Words learned so far (for the developer screen and the trace). */
    val learned: Int get() = snap.learned

    /**
     * True once correction has proven itself on this user's own taps (see the class comment).
     * Off, every tap is left where it landed. [force] skips the check, for tests and sweeps.
     */
    val isActive: Boolean get() = snap.learned > 0 && (earned || force)

    /** Applies the offsets whether or not they have earned it. */
    @Volatile var force: Boolean = false

    /** Share of recent learned taps the corrected key got right minus the raw key's share. */
    val gain: Float get() = if (scored <= 0f) 0f else (corHits - rawHits) / scored

    /** The current offset (dx, dy) in kw for [code] on [stream]: where its taps land from the centre. */
    fun offset(stream: StreamId, code: Int): Pair<Float, Float> {
        val s = snap
        val i = stream.ordinal * letterCount + code
        return s.dx[i] to s.dy[i]
    }

    /**
     * The point the user meant, for a touch at ([x], [y]) kw on [stream], as (code, x, y). Null
     * when the model has nothing learned, or no key is near the touch.
     */
    fun correct(stream: StreamId, x: Float, y: Float, g: KeyboardGeometry): Triple<Int, Float, Float>? {
        if (!isActive) return null
        return shifted(snap, stream, x, y, g)
    }

    private fun shifted(s: Snapshot, stream: StreamId, x: Float, y: Float, g: KeyboardGeometry): Triple<Int, Float, Float>? {
        if (s.learned == 0) return null
        val base = stream.ordinal * letterCount
        var best = -1
        var bestD = Float.MAX_VALUE
        var bx = x
        var by = y
        for (code in 0 until minOf(letterCount, g.letterCount)) {
            if (!g.hasKey(code)) continue
            if (g.distToCenter(x, y, code) > SEARCH_KW) continue
            val cx = x - s.dx[base + code]
            val cy = y - s.dy[base + code]
            val d = g.distToCenter(cx, cy, code)
            if (d < bestD) {
                bestD = d
                best = code
                bx = cx
                by = cy
            }
        }
        if (best < 0) return null
        // The shifted point keyed by where it lands, so the exact pass and the fuzzy pass agree,
        // but a tap leaves the key it hit only for a point well inside the new one: a point near
        // a border is the spread, not the habit, and moving it there broke more words than it fixed.
        val raw = g.keyAt(x, y).takeIf { it >= 0 } ?: g.nearestKey(x, y)
        val landed = g.keyAt(bx, by).takeIf { it >= 0 } ?: best
        val key = if (landed == raw || g.insideInflated(bx, by, landed, -SWITCH_MARGIN_KW)) landed else raw
        return Triple(key, bx, by)
    }

    /** [tokens] with every tap moved by [correct]; swipes are returned as they are. */
    fun apply(tokens: List<InputToken>, g: KeyboardGeometry): List<InputToken> {
        if (!isActive || tokens.none { it is TapToken }) return tokens
        return tokens.map { t ->
            if (t !is TapToken) return@map t
            val c = correct(t.streamId, t.x, t.y, g) ?: return@map t
            if (c.first == t.code && c.second == t.x && c.third == t.y) t else t.copy(code = c.first, x = c.second, y = c.third)
        }
    }

    /**
     * Learns from a committed [word] (codes in [g]'s alphabet) typed by [tokens]. Returns true
     * when the word was used. Main thread.
     */
    fun learn(word: IntArray, tokens: List<InputToken>, g: KeyboardGeometry): Boolean {
        if (word.isEmpty() || tokens.isEmpty()) return false
        if (tokens.size != word.size || tokens.any { it !is TapToken }) return learnMixed(word, tokens, g)
        val taps = tokens.sortedBy { it.tStart }.map { it as TapToken }
        for (i in taps.indices) {
            val c = word[i]
            if (c !in 0 until minOf(letterCount, g.letterCount) || !g.hasKey(c)) return false
            if (g.distToCenter(taps[i].x, taps[i].y, c) > LEARN_GATE_KW) return false
        }
        // Score the model on these taps before it learns from them: raw key versus corrected key.
        val s = snap
        for (i in taps.indices) {
            val raw = g.keyAt(taps[i].x, taps[i].y).takeIf { it >= 0 } ?: g.nearestKey(taps[i].x, taps[i].y)
            val cor = shifted(s, taps[i].streamId, taps[i].x, taps[i].y, g)?.first ?: raw
            rawHits = rawHits * HIT_DECAY + if (raw == word[i]) 1f else 0f
            corHits = corHits * HIT_DECAY + if (cor == word[i]) 1f else 0f
            scored = scored * HIT_DECAY + 1f
        }
        earned = scored >= MIN_SCORED && gain >= GAIN_MIN
        for (i in taps.indices) {
            val c = word[i]
            val k = taps[i].streamId.ordinal * letterCount + c
            if (n[k] >= MAX_COUNT) {
                val f = (MAX_COUNT - 1f) / n[k]
                n[k] *= f
                sx[k] *= f
                sy[k] *= f
            }
            n[k] += 1f
            sx[k] += taps[i].x - g.centerX(c)
            sy[k] += taps[i].y - g.centerY(c)
            addHint(taps[i].streamId, c, taps[i].x, taps[i].y, g)
        }
        learnedWords++
        publish()
        return true
    }

    /**
     * A word with swipes in it, or taps that don't match its letters one for one: each tap
     * teaches the letter of [word] it sits nearest, when that is within [LEARN_GATE_KW] and
     * every other letter of the word is [MIXED_MARGIN_KW] farther. Only the hint statistics
     * learn; correction and its self-check stay on all-tap words, where every tap's letter is known.
     */
    private fun learnMixed(word: IntArray, tokens: List<InputToken>, g: KeyboardGeometry): Boolean {
        var used = false
        for (t in tokens) {
            if (t !is TapToken) continue
            var best = -1
            var bestD = Float.MAX_VALUE
            var second = Float.MAX_VALUE
            for (c in word) {
                if (c !in 0 until minOf(letterCount, g.letterCount) || !g.hasKey(c)) return used
                if (c == best) continue
                val d = g.distToCenter(t.x, t.y, c)
                if (d < bestD) {
                    if (best >= 0) second = bestD
                    bestD = d
                    best = c
                } else if (d < second) {
                    second = d
                }
            }
            if (best < 0 || bestD > LEARN_GATE_KW || second - bestD < MIXED_MARGIN_KW) continue
            addHint(t.streamId, best, t.x, t.y, g)
            used = true
        }
        if (used) {
            learnedWords++
            publish()
        }
        return used
    }

    private fun addHint(stream: StreamId, c: Int, x: Float, y: Float, g: KeyboardGeometry) {
        val k = stream.ordinal * letterCount + c
        if (hN[k] >= MAX_COUNT) {
            val f = (MAX_COUNT - 1f) / hN[k]
            hN[k] *= f
            hSx[k] *= f
            hSy[k] *= f
            hSxx[k] *= f
            hSyy[k] *= f
        }
        val ox = x - g.centerX(c)
        val oy = y - g.centerY(c)
        hN[k] += 1f
        hSx[k] += ox
        hSy[k] += oy
        hSxx[k] += ox * ox
        hSyy[k] += oy * oy
    }

    /**
     * [tokens] with each tap's [TapToken.hints] set to the other keys it is plausible for (see
     * the class comment). Keys with fewer than [HINT_MIN_TAPS] learned taps on that thumb never
     * hint. Swipes are returned as they are.
     */
    fun hint(tokens: List<InputToken>, g: KeyboardGeometry): List<InputToken> {
        val s = snap
        if (s.learned == 0 || tokens.none { it is TapToken }) return tokens
        var changed = false
        val out = tokens.map { t ->
            if (t !is TapToken) return@map t
            val base = t.streamId.ordinal * letterCount
            var mask = 0L
            for (code in 0 until minOf(letterCount, g.letterCount, 64)) {
                if (code == t.code || !g.hasKey(code)) continue
                val i = base + code
                if (s.hn[i] < HINT_MIN_TAPS) continue
                // Only a habit hints: a key whose taps land well off its centre on average.
                if (s.mx[i] * s.mx[i] + s.my[i] * s.my[i] < HINT_MIN_SHIFT_KW * HINT_MIN_SHIFT_KW) continue
                if (g.distToCenter(t.x, t.y, code) > HINT_SEARCH_KW) continue
                val zx = (t.x - g.centerX(code) - s.mx[i]) / s.sdx[i]
                val zy = (t.y - g.centerY(code) - s.my[i]) / s.sdy[i]
                if (zx * zx + zy * zy <= HINT_Z * HINT_Z) mask = mask or (1L shl code)
            }
            if (mask == 0L && t.hints == 0L) t else { changed = true; t.copy(hints = mask) }
        }
        return if (changed) out else tokens
    }

    /** Clears everything learned. Main thread. */
    fun reset() {
        n.fill(0f)
        sx.fill(0f)
        sy.fill(0f)
        hN.fill(0f)
        hSx.fill(0f)
        hSy.fill(0f)
        hSxx.fill(0f)
        hSyy.fill(0f)
        learnedWords = 0
        rawHits = 0f
        corHits = 0f
        scored = 0f
        earned = false
        publish()
    }

    /**
     * The learned sums as text, for on-device storage: `v1;words;stream,code,n,sx,sy,hn,hsx,hsy,hsxx,hsyy;...`
     * (entries saved before hints have the first five fields only).
     * Nothing here leaves the device; it is per-key averages, not what was typed.
     */
    fun save(): String {
        val sb = StringBuilder("v1;").append(learnedWords).append(',').append(rawHits)
            .append(',').append(corHits).append(',').append(scored)
        for (k in n.indices) {
            if (n[k] <= 0f && hN[k] <= 0f) continue
            sb.append(';').append(k / letterCount).append(',').append(k % letterCount)
                .append(',').append(n[k]).append(',').append(sx[k]).append(',').append(sy[k])
                .append(',').append(hN[k]).append(',').append(hSx[k]).append(',').append(hSy[k])
                .append(',').append(hSxx[k]).append(',').append(hSyy[k])
        }
        return sb.toString()
    }

    /** Restores [save] output; ignores anything malformed. Main thread. */
    fun restore(text: String?) {
        reset()
        if (text == null || !text.startsWith("v1;")) return
        val parts = text.split(';')
        val head = parts.getOrNull(1)?.split(',').orEmpty()
        learnedWords = head.getOrNull(0)?.toIntOrNull() ?: 0
        rawHits = head.getOrNull(1)?.toFloatOrNull() ?: 0f
        corHits = head.getOrNull(2)?.toFloatOrNull() ?: 0f
        scored = head.getOrNull(3)?.toFloatOrNull() ?: 0f
        earned = scored >= MIN_SCORED && gain >= GAIN_MIN
        for (p in parts.drop(2)) {
            val f = p.split(',')
            if (f.size != 5 && f.size != 10) continue
            val stream = f[0].toIntOrNull() ?: continue
            val code = f[1].toIntOrNull() ?: continue
            if (stream !in 0..1 || code !in 0 until letterCount) continue
            val k = stream * letterCount + code
            n[k] = f[2].toFloatOrNull() ?: continue
            sx[k] = f[3].toFloatOrNull() ?: 0f
            sy[k] = f[4].toFloatOrNull() ?: 0f
            if (f.size == 10) {
                hN[k] = f[5].toFloatOrNull() ?: 0f
                hSx[k] = f[6].toFloatOrNull() ?: 0f
                hSy[k] = f[7].toFloatOrNull() ?: 0f
                hSxx[k] = f[8].toFloatOrNull() ?: 0f
                hSyy[k] = f[9].toFloatOrNull() ?: 0f
            } else {
                // Saved before hints: start them from the all-tap sums at the prior spread.
                hN[k] = n[k]
                hSx[k] = sx[k]
                hSy[k] = sy[k]
                val nk = n[k].coerceAtLeast(1f)
                hSxx[k] = sx[k] * sx[k] / nk + n[k] * HINT_PRIOR_SD * HINT_PRIOR_SD
                hSyy[k] = sy[k] * sy[k] / nk + n[k] * HINT_PRIOR_SD * HINT_PRIOR_SD
            }
        }
        publish()
    }

    private fun publish() {
        val dx = FloatArray(2 * letterCount)
        val dy = FloatArray(2 * letterCount)
        for (stream in 0..1) {
            val base = stream * letterCount
            var tn = 0f
            var tx = 0f
            var ty = 0f
            for (c in 0 until letterCount) {
                tn += n[base + c]
                tx += sx[base + c]
                ty += sy[base + c]
            }
            val thumbX = tx / (tn + THUMB_PRIOR)
            val thumbY = ty / (tn + THUMB_PRIOR)
            for (c in 0 until letterCount) {
                val k = base + c
                var ox = (sx[k] + KEY_PRIOR * thumbX) / (n[k] + KEY_PRIOR)
                var oy = (sy[k] + KEY_PRIOR * thumbY) / (n[k] + KEY_PRIOR)
                val len = sqrt(ox * ox + oy * oy)
                if (len > MAX_OFFSET_KW) {
                    ox *= MAX_OFFSET_KW / len
                    oy *= MAX_OFFSET_KW / len
                }
                dx[k] = ox * strength
                dy[k] = oy * strength
            }
        }
        val hn = FloatArray(2 * letterCount)
        val mx = FloatArray(2 * letterCount)
        val my = FloatArray(2 * letterCount)
        val sdx = FloatArray(2 * letterCount)
        val sdy = FloatArray(2 * letterCount)
        val v0 = HINT_PRIOR * HINT_PRIOR_SD * HINT_PRIOR_SD
        for (k in hN.indices) {
            val nk = hN[k]
            hn[k] = nk
            mx[k] = hSx[k] / (nk + HINT_PRIOR)
            my[k] = hSy[k] / (nk + HINT_PRIOR)
            // Scatter about the key's own mean, shrunk towards the prior spread.
            val cx = if (nk > 0f) (hSxx[k] - hSx[k] * hSx[k] / nk).coerceAtLeast(0f) else 0f
            val cy = if (nk > 0f) (hSyy[k] - hSy[k] * hSy[k] / nk).coerceAtLeast(0f) else 0f
            sdx[k] = sqrt((cx + v0) / (nk + HINT_PRIOR)).coerceAtLeast(HINT_MIN_SD)
            sdy[k] = sqrt((cy + v0) / (nk + HINT_PRIOR)).coerceAtLeast(HINT_MIN_SD)
        }
        snap = Snapshot(dx, dy, learnedWords, hn, mx, my, sdx, sdy)
    }

    /**
     * How much of the learned offset to apply, 0 to 1. Only the replay harness sweeps it; the
     * keyboard keeps the default.
     */
    var strength: Float = KineticaConstants.TOUCH_MODEL_STRENGTH
        set(value) {
            field = value
            publish()
        }

    companion object {
        /** Pseudo-taps pulling a key's offset towards its thumb's average. */
        const val KEY_PRIOR = 30f

        /** Pseudo-taps pulling a thumb's average towards zero. */
        const val THUMB_PRIOR = 20f

        /** Offsets never exceed this (kw): a third of a key. */
        const val MAX_OFFSET_KW = 0.35f

        /** Keys considered for a touch: within this of the raw point (kw). */
        const val SEARCH_KW = 1.2f

        /** A learning tap must be this close to its letter's centre (kw). */
        const val LEARN_GATE_KW = 0.9f

        /** Per-key count where old taps start to fade. */
        const val MAX_COUNT = 200f

        /** A tap changes key only for a shifted point at least this far inside the new key (kw). */
        const val SWITCH_MARGIN_KW = 0.1f

        /** Per-tap decay of the self-check, about the last 1,000 taps. */
        const val HIT_DECAY = 0.999f

        /** Taps scored before correction may switch on. */
        const val MIN_SCORED = 300f

        /** How much more often the corrected key must be right than the raw key. */
        const val GAIN_MIN = 0.005f

        /** A tap in a swiped word teaches its nearest letter only when the next is this much farther (kw). */
        const val MIXED_MARGIN_KW = 0.5f

        /** Learned taps a (thumb, key) needs before it hints. */
        const val HINT_MIN_TAPS = 20f

        /** Keys considered for a hint: centre within this of the touch (kw). */
        const val HINT_SEARCH_KW = 1.6f

        /**
         * A key is hinted for a tap within this many of its standard deviations. 3 missed the
         * developer's right-thumb `b` taps meant for `h`; 5 started to hint words he meant.
         * Variable for the replay sweeps (`--hintz`); the keyboard keeps the default.
         */
        @JvmStatic var HINT_Z = 4f

        /**
         * A key hints only when its taps land at least this far off its centre on average (kw).
         * On the developer's traces right-thumb `h` is far ahead (0.38 kw split, 0.46 kw not);
         * left `c` and `n` reach 0.31 on the plain layout and change no replayed line at 0.35.
         * Variable for the replay sweeps (`--hintshift`).
         */
        @JvmStatic var HINT_MIN_SHIFT_KW = 0.3f

        /** Pseudo-taps for the hint mean (towards 0) and spread (towards [HINT_PRIOR_SD]). */
        const val HINT_PRIOR = 10f
        const val HINT_PRIOR_SD = 0.3f
        const val HINT_MIN_SD = 0.15f
    }
}
