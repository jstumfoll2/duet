package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.KineticaConstants
import java.util.Locale

/**
 * Top-1, top-3, MRR, presence anywhere in the shipping list, and recall at the
 * deep K, overall and per bucket, plus the
 * exactness count. Only committed lines are scored: an abandoned buffer has no
 * label of its own.
 */
class ReplayReport(private val deepK: Int) {

    class Tally {
        var n = 0
        var top1 = 0
        var top3 = 0
        var rr = 0.0
        var in10 = 0
        var recall = 0

        fun add(r: ReplayHarness.Result) {
            n++
            if (r.rank == 1) top1++
            if (r.rank in 1..3) top3++
            if (r.rank > 0) in10++
            if (r.rank > 0) rr += 1.0 / r.rank
            if (r.deepRank > 0) recall++
        }
    }

    val all = Tally()

    /**
     * The scorecard: lines by who chose the label. `practice` is the prompted word; `you typed`
     * is what the user committed by hand (typed, picked, accepted autocorrect, or corrected
     * later); `space commit` is a decode the space bar committed, so its label is the keyboard's
     * own answer and flatters any replay that changes nothing. `as typed` scores the hand-chosen
     * lines again, counting a non-dictionary word tapped letter for letter as right.
     */
    val bySource = LinkedHashMap<String, Tally>().apply { for (s in SOURCES) put(s, Tally()) }
    var asTypedHits = 0

    /** Lines by label length in letters: short words and long ones fail for different reasons. */
    val byLength = LinkedHashMap<String, Tally>().apply { for (l in LENGTHS) put(l, Tally()) }

    val byBucket = LinkedHashMap<String, Tally>().apply { for (b in ReplayHarness.BUCKETS) put(b, Tally()) }
    var lines = 0
    var unlabelled = 0
    var comparable = 0
    var exact = 0
    var failed = 0
    val mismatches = ArrayList<String>()
    /** Lines whose label the shipping list holds and the deep list lost; see [format]. */
    var deepLost = 0
    val deepLostExamples = ArrayList<String>()
    val errors = ArrayList<String>()
    private val micros = ArrayList<Long>()

    fun add(r: ReplayHarness.Result) {
        lines++
        micros.add(r.micros)
        when (r.exact) {
            null -> Unit
            true -> { comparable++; exact++ }
            false -> {
                comparable++
                if (mismatches.size < MAX_EXAMPLES) {
                    mismatches.add(
                        "${r.word.label}: live=${r.word.shown.candidates.take(3).map { it.word }} " +
                            "replay=${r.shipping.take(3).map { it.word }}",
                    )
                }
            }
        }
        if (r.word.label == null) { unlabelled++; return }
        if (r.rank > 0 && r.deepRank == 0) {
            deepLost++
            if (deepLostExamples.size < MAX_EXAMPLES) deepLostExamples.add("${r.word.label} (shipping rank ${r.rank})")
        }
        all.add(r)
        for (b in r.buckets) byBucket.getValue(b).add(r)
        val n = r.word.label?.length ?: 0
        byLength.getValue(if (n <= 2) "1-2 letters" else if (n <= 6) "3-6 letters" else if (n <= 9) "7-9 letters" else "10+ letters").add(r)
        val source = when {
            r.word.target != null -> "practice"
            r.word.how == "tentative" -> "space commit"
            else -> "you typed"
        }
        bySource.getValue(source).add(r)
        if (source == "you typed" && (r.rank == 1 || r.typedAsIs)) asTypedHits++
    }

    fun error(lineNo: Int, e: Exception) {
        failed++
        if (errors.size < MAX_EXAMPLES) errors.add("line $lineNo: ${e.message}")
    }

    fun format(): String = buildString {
        appendLine("lines=$lines scored=${all.n} abandoned=$unlabelled unreadable=$failed")
        appendLine("exact replay: $exact/$comparable comparable lines (identical list, scores bit for bit)")
        appendLine()
        appendLine(
            String.format(
                Locale.ROOT,
                "%-18s %6s %7s %7s %7s %8s %9s", "bucket", "n", "top1", "top3", "MRR",
                "in@${KineticaConstants.TOP_K}", "recall@$deepK",
            ),
        )
        row("all", all)
        for ((b, t) in byBucket) if (t.n > 0) row(b, t)
        appendLine()
        appendLine("by label length")
        for ((b, t) in byLength) if (t.n > 0) row(b, t)
        appendLine()
        appendLine("by label source")
        for ((b, t) in bySource) if (t.n > 0) row(b, t)
        val typed = bySource.getValue("you typed")
        if (typed.n > 0) {
            appendLine(
                String.format(
                    Locale.ROOT, "%-18s %6d %7s   (non-dictionary words tapped letter for letter count as right)",
                    "as typed", typed.n, String.format(Locale.ROOT, "%.1f%%", 100.0 * asTypedHits / typed.n),
                ),
            )
        }
        if (micros.isNotEmpty()) {
            val sorted = micros.sorted()
            fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / 1000.0
            appendLine()
            appendLine(
                String.format(
                    Locale.ROOT, "decode time on this JVM (not a phone): p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms",
                    pct(0.5), pct(0.95), pct(0.99), sorted.last() / 1000.0,
                ),
            )
        }
        if (deepLost > 0) {
            // Not a replay fault. The deep decode is a different search: a bigger
            // heap has a lower minimum, so more words pass and the fixed
            // MAX_EMIT_ATTEMPTS budget runs out earlier in the trie walk. Measured
            // on the synthetic set: "pants" is rank 1 at K=10 and 15 and gone from
            // the list at K=20, 30 and 50.
            appendLine()
            appendLine("deep list missed the label in $deepLost lines where the shipping list had it:")
            for (m in deepLostExamples) appendLine("  $m")
        }
        if (mismatches.isNotEmpty()) {
            appendLine()
            appendLine("first mismatches:")
            for (m in mismatches) appendLine("  $m")
        }
        if (errors.isNotEmpty()) {
            appendLine()
            appendLine("first unreadable lines:")
            for (m in errors) appendLine("  $m")
        }
    }

    private fun StringBuilder.row(name: String, t: Tally) {
        fun pct(k: Int) = if (t.n == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * k / t.n)
        val mrr = if (t.n == 0) "-" else String.format(Locale.ROOT, "%.3f", t.rr / t.n)
        appendLine(String.format(Locale.ROOT, "%-18s %6d %7s %7s %7s %8s %9s", name, t.n, pct(t.top1), pct(t.top3), mrr, pct(t.in10), pct(t.recall)))
    }

    private companion object {
        val SOURCES = listOf("practice", "you typed", "space commit")
        val LENGTHS = listOf("1-2 letters", "3-6 letters", "7-9 letters", "10+ letters")

        const val MAX_EXAMPLES = 10
    }
}
