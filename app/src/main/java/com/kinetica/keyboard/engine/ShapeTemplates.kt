package com.kinetica.keyboard.engine

/**
 * The user's own swipe paths for words they swipe often, matched beside the key-centre path
 * (issue #138).
 *
 * The decoder scores a swipe against the polyline through a word's key centres. Nobody draws
 * that line: corners are cut the same way every time, a far key is habitually undershot. For a
 * word the user has swiped and kept several times, the last few of their own paths are a
 * better picture of the next one than the ideal. A swipe is matched against them with the
 * same DTW, and the word is scored by its nearest template when that beats the ideal path.
 *
 * Paths are in key widths on the layout they were drawn on and mean nothing on another, so a
 * store belongs to one [layout] ([layoutOf]: the letters and where their keys sit) and is
 * neither matched nor added to on a geometry that does not [fit][fits] it.
 *
 * Immutable, like [WordPenalties]: every change returns a new value, so a decode thread holding
 * the old one never sees it half-written. One string preference, so the settings backup carries
 * it: `layout`, then rows `word|path|path`, a path being its [N] points as two bytes each at
 * 1/[SCALE] kw, base64.
 */
class ShapeTemplates private constructor(
    val layout: String,
    private val byWord: Map<String, List<FloatArray>>,
) {

    val size: Int get() = byWord.size

    /**
     * Whether [g] is the layout these paths were drawn on: the same letters, every key centre
     * within [LAYOUT_SLACK_KW] of where it was. The slack lets a learned touch offset move.
     */
    fun fits(g: KeyboardGeometry): Boolean {
        val at = layout.indexOf(':')
        if (at < 0 || layout.substring(0, at) != g.alphabet.letters) return false
        val b = try { java.util.Base64.getDecoder().decode(layout.substring(at + 1)) } catch (e: IllegalArgumentException) { return false }
        val c = floats(b)
        if (c.size != 2 * g.letterCount) return false
        for (code in 0 until g.letterCount) {
            if (!g.hasKey(code)) continue
            val dx = g.centerX(code) - c[2 * code]
            val dy = g.centerY(code) - c[2 * code + 1]
            if (dx * dx + dy * dy > LAYOUT_SLACK_KW * LAYOUT_SLACK_KW) return false
        }
        return true
    }

    val isEmpty: Boolean get() = byWord.isEmpty()

    fun count(word: String): Int = byWord[word.lowercase()]?.size ?: 0

    /** The words holding at least [MIN_TEMPLATES] paths: the ones [nearest] answers for. */
    val ready: Int get() = byWord.values.count { it.size >= MIN_TEMPLATES }

    /**
     * With one more path for [word], [resampled] being [N] (x, y) pairs in kw. The oldest of a
     * word's [PER_WORD] paths makes room; past [MAX_WORDS] words the one with the fewest paths
     * (then the first by name) goes. A word that cannot be stored, or a path of the wrong
     * length, changes nothing.
     */
    fun with(word: String, resampled: FloatArray): ShapeTemplates {
        val w = word.lowercase()
        if (!WordPenalties.storable(w) || resampled.size != 2 * N) return this
        val out = HashMap(byWord)
        val paths = ArrayList(out[w] ?: emptyList())
        paths.add(quantized(resampled))
        while (paths.size > PER_WORD) paths.removeAt(0)
        out[w] = paths
        if (out.size > MAX_WORDS) {
            val drop = out.entries.filter { it.key != w }
                .minWithOrNull(compareBy<Map.Entry<String, List<FloatArray>>> { it.value.size }.thenBy { it.key })
            if (drop != null) out.remove(drop.key)
        }
        return ShapeTemplates(layout, out)
    }

    /** Without [word]'s paths (it was blocked, forgotten, or its decode was taken back). */
    fun without(word: String): ShapeTemplates {
        val w = word.lowercase()
        if (w !in byWord) return this
        return ShapeTemplates(layout, HashMap(byWord).also { it.remove(w) })
    }

    /** Without [word]'s newest path: the commit that added it was taken back. */
    fun withoutLast(word: String): ShapeTemplates {
        val w = word.lowercase()
        val paths = byWord[w] ?: return this
        val out = HashMap(byWord)
        if (paths.size <= 1) out.remove(w) else out[w] = paths.dropLast(1)
        return ShapeTemplates(layout, out)
    }

    /** A word and the mean per-step DTW cost, in kw, of its nearest template. */
    class Match(val word: String, val distance: Float)

    /**
     * Every word with [MIN_TEMPLATES] paths or more whose nearest path is within [maxDistance]
     * (mean kw per step) of [observed], nearest first. Paths that start or end more than
     * [ENDPOINT_GATE_KW] from the swipe's own ends are not walked at all.
     */
    fun nearest(observed: FloatArray, dtw: DtwMatcher, maxDistance: Float): List<Match> {
        if (byWord.isEmpty() || observed.size != 2 * N) return emptyList()
        val out = ArrayList<Match>()
        val budget = maxDistance * N
        for ((word, paths) in byWord) {
            if (paths.size < MIN_TEMPLATES) continue
            var best = Float.POSITIVE_INFINITY
            for (p in paths) {
                if (far(observed, p, 0) || far(observed, p, N - 1)) continue
                val d = dtw.distanceAccum(observed, p, minOf(budget, best))
                if (d < best) best = d
            }
            if (best <= budget) out.add(Match(word, best / N))
        }
        out.sortWith(compareBy<Match> { it.distance }.thenBy { it.word })
        return out
    }

    private fun far(a: FloatArray, b: FloatArray, i: Int): Boolean {
        val dx = a[2 * i] - b[2 * i]
        val dy = a[2 * i + 1] - b[2 * i + 1]
        return dx * dx + dy * dy > ENDPOINT_GATE_KW * ENDPOINT_GATE_KW
    }

    fun serialize(): String {
        val rows = ArrayList<String>(byWord.size + 1)
        rows.add(layout)
        val enc = java.util.Base64.getEncoder().withoutPadding()
        for ((w, paths) in byWord.entries.sortedBy { it.key }) {
            rows.add(w + FIELD + paths.joinToString(FIELD) { enc.encodeToString(bytes(it)) })
        }
        return rows.joinToString(ROW)
    }

    companion object {
        const val N = KineticaConstants.RESAMPLE_N
        private const val ROW = ","
        private const val FIELD = "|"

        /** Stored steps per key width. Two bytes a coordinate, so about ±500 kw of range. */
        private const val SCALE = 64f

        /** Paths kept per word, newest last. */
        const val PER_WORD = 3

        /** Words kept. 3 paths of 128 bytes each: about 80 KB of preference at the most. */
        const val MAX_WORDS = 150

        /** Whether the decoder matches the templates (the pref, default off). */
        @Volatile @JvmField var enabled = false

        /** Paths a word needs before it is matched: one swipe is not a habit. */
        @Volatile @JvmField var MIN_TEMPLATES = 2

        /** A template further than this (mean kw per step) says nothing about the swipe. */
        @Volatile @JvmField var MAX_DISTANCE_KW = 0.5f

        /** How far a template's ends may sit from the swipe's ends and still be walked. */
        @Volatile @JvmField var ENDPOINT_GATE_KW = 1.0f

        /** How far a key may sit from where a store's layout has it and still be that layout. */
        const val LAYOUT_SLACK_KW = 0.2f

        /** [g] as a store's layout: its letters, then every key centre. No commas or bars. */
        fun layoutOf(g: KeyboardGeometry): String {
            val c = FloatArray(2 * g.letterCount)
            for (code in 0 until g.letterCount) {
                if (!g.hasKey(code)) continue
                c[2 * code] = g.centerX(code)
                c[2 * code + 1] = g.centerY(code)
            }
            return g.alphabet.letters + ":" + java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes(c))
        }

        fun empty(layout: String): ShapeTemplates = ShapeTemplates(layout, emptyMap())

        /** What [serialize] wrote, if it was written on [layout]; else an empty store for [layout]. */
        fun parse(stored: String?, layout: String): ShapeTemplates {
            if (stored.isNullOrEmpty()) return empty(layout)
            val rows = stored.split(ROW)
            if (rows[0] != layout) return empty(layout)
            val dec = java.util.Base64.getDecoder()
            val out = HashMap<String, List<FloatArray>>()
            for (row in rows.drop(1)) {
                val f = row.split(FIELD)
                if (f.size < 2 || !WordPenalties.storable(f[0]) || out.size >= MAX_WORDS) continue
                val paths = ArrayList<FloatArray>()
                for (s in f.drop(1).takeLast(PER_WORD)) {
                    val b = try { dec.decode(s) } catch (e: IllegalArgumentException) { continue }
                    if (b.size == 4 * N) paths.add(floats(b))
                }
                if (paths.isNotEmpty()) out[f[0]] = paths
            }
            return ShapeTemplates(layout, out)
        }

        /** [path] as it will read back from storage, so a fresh template and a stored one match alike. */
        private fun quantized(path: FloatArray): FloatArray = floats(bytes(path))

        private fun bytes(path: FloatArray): ByteArray {
            val b = ByteArray(2 * path.size)
            for (i in path.indices) {
                val v = Math.round(path[i] * SCALE).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                b[2 * i] = (v shr 8).toByte()
                b[2 * i + 1] = v.toByte()
            }
            return b
        }

        private fun floats(b: ByteArray): FloatArray =
            FloatArray(b.size / 2) { i -> ((b[2 * i].toInt() shl 8) or (b[2 * i + 1].toInt() and 0xff)).toShort() / SCALE }
    }
}
