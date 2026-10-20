package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.CtcScorer
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.Interleave
import com.kinetica.keyboard.engine.InterleavedSearch
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * Decode latency on this JVM, for comparing two builds on the same traces (see README.md).
 *
 * `replay` times each line once, on a cold JIT, so most of its spread is warm-up. Here every
 * line is replayed once to warm up, then [REPS] more times, and its fastest run counts: the
 * machine's noise only ever adds time. Rows are per input shape, as in the replay report.
 */
object Bench {

    private const val REPS = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val a = args.toMutableList()
        fun opt(name: String): String? {
            val i = a.indexOf(name)
            if (i < 0 || i + 1 >= a.size) return null
            return a[i + 1].also { a.removeAt(i); a.removeAt(i) }
        }
        val ctc = opt("--ctc")?.let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
        val beta = opt("--beta")?.toFloat() ?: 0f
        val stages = a.remove("--stages")
        val alloc = a.remove("--alloc")
        val interleaveOnly = a.remove("--interleave")
        val assets = File(opt("--assets") ?: "app/src/main/assets")
        if (a.isEmpty()) {
            System.err.println("bench [--ctc MODEL --beta B] [--stages | --alloc | --interleave] FILE...")
            System.exit(2)
        }
        // The phone's NN switch: two thumbs drawing at once reranked stroke by stroke, the
        // rest in plain order (NeuralRerank.twoThumb).
        val h = ReplayHarness(
            assets, ReplayHarness.DEFAULT_DEEP_K, ctc, beta, ReplayHarness.DEFAULT_DEEP_K,
            rerankOverlap = ctc != null, rerankOverlapOnly = ctc != null,
        )
        val words = ArrayList<SwipeTrace.Word>()
        val sink = ReplayReport(h.deepK)
        for (f in a) for ((_, w) in ReplayCli.read(File(f), sink)) words.add(w)
        when {
            interleaveOnly -> interleave(h, words)
            alloc -> allocation(h, words)
            else -> latency(h, words, stages)
        }
    }

    private fun shape(r: ReplayHarness.Result): String = r.buckets.firstOrNull() ?: "unlabelled"

    private fun latency(h: ReplayHarness, words: List<SwipeTrace.Word>, stages: Boolean) {
        val best = LongArray(words.size) { Long.MAX_VALUE }
        val shapes = arrayOfNulls<String>(words.size)
        for (pass in 0..REPS) {
            for ((i, w) in words.withIndex()) {
                val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                shapes[i] = shape(r)
                if (pass > 0 && r.micros < best[i]) best[i] = r.micros
            }
        }
        val timed = words.indices.filter { best[it] != Long.MAX_VALUE }
        row("all", timed.map { best[it] })
        for (s in timed.mapNotNull { shapes[it] }.distinct().sorted()) row(s, timed.filter { shapes[it] == s }.map { best[it] })
        if (stages) stageTotals(h, words, shapes)
    }

    private fun row(name: String, micros: List<Long>) {
        val s = micros.sorted()
        fun p(q: Double) = s[minOf(s.size - 1, (q * s.size).toInt())] / 1000.0
        println(
            String.format(
                Locale.ROOT, "%-18s n=%5d mean %6.2f  p50 %6.2f  p90 %6.2f  p95 %6.2f  p99 %6.2f  max %7.2f ms",
                name, s.size, s.average() / 1000.0, p(0.5), p(0.9), p(0.95), p(0.99), s.last() / 1000.0,
            ),
        )
    }

    /**
     * One more pass under a trace sink, adding up the stages each decode time line names. It
     * counts the deep decode too, and tracing costs time of its own: a split, not a timing.
     */
    private fun stageTotals(h: ReplayHarness, words: List<SwipeTrace.Word>, shapes: Array<String?>) {
        val sum = java.util.TreeMap<String, Double>()
        var shape = ""
        DecodeTrace.sink = { l ->
            if (l.startsWith("decode time")) {
                val parts = l.substringAfter("ms").trim().split(" ")
                for (i in 0 until parts.size - 1 step 2) {
                    val k = "$shape ${parts[i]}"
                    sum[k] = (sum[k] ?: 0.0) + parts[i + 1].toDouble()
                }
            }
        }
        try {
            for ((i, w) in words.withIndex()) {
                shape = shapes[i] ?: continue
                try { h.replay(w) } catch (e: RuntimeException) { continue }
            }
        } finally {
            DecodeTrace.sink = null
        }
        for ((k, v) in sum) println(String.format(Locale.ROOT, "stage %-28s %9.1f ms", k, v))
    }

    /** Bytes each line's replay allocates (both decodes), after a warm-up pass: GC load on a phone. */
    private fun allocation(h: ReplayHarness, words: List<SwipeTrace.Word>) {
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        for (w in words) try { h.replay(w) } catch (e: RuntimeException) { continue }
        val per = java.util.TreeMap<String, ArrayList<Long>>()
        for (w in words) {
            val b0 = mx.getThreadAllocatedBytes(tid)
            val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
            val b = mx.getThreadAllocatedBytes(tid) - b0
            per.getOrPut("all") { ArrayList() }.add(b)
            per.getOrPut(shape(r)) { ArrayList() }.add(b)
        }
        for ((k, v) in per) {
            val s = v.sorted()
            println(
                String.format(
                    Locale.ROOT, "alloc %-18s n=%5d mean %7.0f KB  p95 %7.0f KB  max %7.0f KB",
                    k, s.size, s.average() / 1024, s[minOf(s.size - 1, (s.size * 0.95).toInt())] / 1024.0, s.last() / 1024.0,
                ),
            )
        }
    }

    /**
     * The interleaved search alone on every two-thumb buffer, ten rounds, the fastest round's
     * CPU time; and a hash of every hit's word and cost bits, so a speed-up that changes no
     * result prints the same hash.
     */
    private fun interleave(h: ReplayHarness, words: List<SwipeTrace.Word>) {
        val cases = words.mapNotNull { w ->
            val tokens = SwipeTrace.replayTokens(w)
            val g = w.geometry.build()
            if (Interleave.of(tokens, g) == null) null else Triple(h.trieFor(w.config.language, w.config.britishSpelling), tokens, g)
        }
        val mx = ManagementFactory.getThreadMXBean()
        var fastest = Long.MAX_VALUE
        var nodes = 0L
        var hash = 0L
        repeat(10) {
            val c0 = mx.currentThreadCpuTime
            nodes = 0L
            hash = 0L
            for ((trie, tokens, g) in cases) {
                val search = InterleavedSearch(trie, Interleave.of(tokens, g)!!)
                for (hit in search.run(com.kinetica.keyboard.engine.KineticaConstants.INTERLEAVE_KEEP)) {
                    hash = hash * 31 + hit.word.hashCode() + java.lang.Float.floatToRawIntBits(hit.cost)
                }
                nodes += search.visited
            }
            fastest = minOf(fastest, mx.currentThreadCpuTime - c0)
        }
        println(String.format(Locale.ROOT, "interleave: %d buffers, %d nodes, %.1f ms CPU, hits hash %016x", cases.size, nodes, fastest / 1e6, hash))
    }
}
