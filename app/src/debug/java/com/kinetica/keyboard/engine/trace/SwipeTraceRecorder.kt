package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * Writes one [SwipeTrace] v1 line per word buffer.
 *
 * Attach it as both the [GestureEngine.Observer] and the [WordComposer.Observer]:
 * the engine side collects each pointer's samples and pairs them with the token
 * its lift produced, the composer side emits the line when the buffer ends. A
 * buffer token with no samples (reloaded taps, an accent letter, or a gesture
 * that began before the recorder was attached) is written as a literal token.
 *
 * Main thread only, like both observers. [sink] receives the finished line and
 * decides where it goes; [config] is read once per line.
 */
class SwipeTraceRecorder(
    private val config: () -> SwipeTrace.Config,
    private val sink: (String) -> Unit,
) : GestureEngine.Observer, WordComposer.Observer {

    /** Label for the next committed buffer, set by the owner just before commit when it knows. */
    var nextHow: String? = null

    /** The practice prompt in force, or null outside practice mode. Read at each buffer end. */
    var target: () -> String? = { null }

    /** Whether a finished buffer is written at all; read at each buffer end. */
    var enabled: () -> Boolean = { true }

    /**
     * The buffer decoded without and with the neural rerank, or null for none.
     * Called with the buffer's tokens, context and target at each written line.
     */
    var compare: (List<InputToken>, List<String>, String?) -> SwipeTrace.AB? = { _, _, _ -> null }

    /** A correction-strip pick replaced the last commit [from] with [to]. */
    fun onCorrection(from: String, to: String) {
        if (enabled()) sink(SwipeTrace.encodeCorrection(from, to))
    }

    private class Open(val stream: StreamId, val geometry: SwipeTrace.Geometry) {
        val samples = ArrayList<SwipeTrace.Sample>(64)
    }

    private class Done(val token: InputToken, val gesture: SwipeTrace.Gesture, val geometry: SwipeTrace.Geometry)

    private var geometry: SwipeTrace.Geometry? = null

    // The last line's word when it was all taps: each tap's key and real touch times, in time
    // order. A word reopened from the editor comes back as seeded taps with made-up times; when
    // they spell this word, the line gets the real ones beside them.
    private var lastTaps: List<Triple<Int, Long, Long>> = emptyList()
    private val open = HashMap<Int, Open>()

    // Finished gestures waiting for their buffer to end, oldest first. Matched by
    // identity: the token object is the one the composer holds. Bounded, because
    // not every token reaches a composer (peck mode drops swipes).
    private val done = ArrayDeque<Done>()

    override fun onGeometry(g: KeyboardGeometry, tapMaxDispPx: Float) {
        geometry = SwipeTrace.Geometry.of(g, tapMaxDispPx)
    }

    override fun onDown(pointerId: Int, streamId: StreamId, xPx: Float, yPx: Float, t: Long) {
        val g = geometry ?: return
        open[pointerId] = Open(streamId, g).also { it.samples.add(SwipeTrace.Sample(xPx, yPx, t)) }
    }

    override fun onMove(pointerId: Int, xPx: Float, yPx: Float, t: Long) {
        open[pointerId]?.samples?.add(SwipeTrace.Sample(xPx, yPx, t))
    }

    override fun onUp(pointerId: Int, xPx: Float, yPx: Float, t: Long, token: InputToken) {
        val o = open.remove(pointerId) ?: return
        o.samples.add(SwipeTrace.Sample(xPx, yPx, t))
        done.addLast(Done(token, SwipeTrace.Gesture(o.stream, o.samples), o.geometry))
        while (done.size > MAX_PENDING) done.removeFirst()
    }

    override fun onCancel(pointerId: Int) {
        open.remove(pointerId)
    }

    override fun onBufferEnd(
        tokens: List<InputToken>,
        context: List<String>,
        shown: List<WordCandidate>,
        shownFor: Int,
        committed: String?,
        apostropheMark: Boolean,
    ) {
        val how = if (committed != null) nextHow else null
        nextHow = null
        if (tokens.isEmpty()) return
        val g = geometry ?: return
        if (!enabled()) {
            for (t in tokens) done.removeAll { it.token === t }
            lastTaps = emptyList()
            return
        }
        val out = ArrayList<SwipeTrace.Token>(tokens.size)
        var geometryChanged = false
        for (t in tokens) {
            val d = done.firstOrNull { it.token === t }
            if (d != null) {
                done.remove(d)
                if (!d.geometry.sameAs(g)) geometryChanged = true
                out.add(d.gesture)
            } else {
                out.add(SwipeTrace.Literal(t))
            }
        }
        // A buffer whose gestures were drawn on another geometry than the one the
        // decode used cannot be replayed from one geometry; rare (a rotation
        // mid-word), and skipped rather than written wrong.
        if (geometryChanged) return
        withRealTimes(tokens, out)
        val goal = target()
        val word = SwipeTrace.Word(
            config(), g, context, out,
            SwipeTrace.Shown(shownFor, SwipeTrace.candidates(shown)), committed, how, goal,
            compare(tokens, context, goal), apostropheMark,
        )
        sink(SwipeTrace.encode(word))
    }

    // Gives the seeded taps that lead [tokens] the real times of [lastTaps] when they spell the
    // same keys, then remembers this line's taps for the next one.
    private fun withRealTimes(tokens: List<InputToken>, out: MutableList<SwipeTrace.Token>) {
        val previous = lastTaps
        val seeded = out.indexOfFirst { it !is SwipeTrace.Literal || it.token !is TapToken }.let { if (it < 0) out.size else it }
        if (previous.isNotEmpty() && seeded == previous.size &&
            (0 until seeded).all { (tokens[it] as TapToken).code == previous[it].first }
        ) {
            for (k in 0 until seeded) out[k] = SwipeTrace.Literal(tokens[k], previous[k].second to previous[k].third)
        }
        // Only taps whose times are all real: drawn, or seeded and given real times above.
        val known = tokens.indices.all { k -> tokens[k] is TapToken && (out[k] is SwipeTrace.Gesture || (out[k] as SwipeTrace.Literal).real != null) }
        lastTaps = if (known) {
            tokens.indices.map { k ->
                val t = tokens[k] as TapToken
                val real = (out[k] as? SwipeTrace.Literal)?.real
                Triple(t.code, real?.first ?: t.tStart, real?.second ?: t.tEnd)
            }.sortedBy { it.second }
        } else {
            emptyList()
        }
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}
