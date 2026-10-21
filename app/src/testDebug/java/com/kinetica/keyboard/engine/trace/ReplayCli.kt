package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.CtcScorer
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.ScoreTerms
import java.io.File

/**
 * Command line for the replay harness. Run through `tools/replay` (see its
 * README), which builds the engine and this harness as a plain JVM program.
 *
 *   replay [--assets DIR] [--k N] TRACE.jsonl...
 *       Replays every v1 line, checks exactness, prints top-1, top-3, MRR and
 *       recall@N (default 50) overall and per bucket. Exits 1 when any
 *       comparable line fails to reproduce. Every command relabels retyped
 *       words (see [read]); --plain keeps the committed labels. Every command skips a word
 *       line an earlier file already had; --keepdups scores it again.
 *       With --ctc MODEL.kctc [--beta B] [--depth D] the shipping run adds the
 *       stage-A CTC rerank over a heap D deep (default 50).
 *   ranks [--learn words|phrases] [--pickw N] [--seed FILE] [--hdfree] TRACE.jsonl...
 *       One tab-separated row per scored line. --learn replays the files in order and learns
 *       each label after decoding it, as the keyboard does; --seed merges lang<TAB>word rows
 *       from the start, as the phone's user dictionary and contact names do.
 *   tune --ctc MODEL.kctc [--betas 0,0.05,...] [--depth D] TRACE.jsonl...
 *       Picks beta on one half of the lines (by top-1, then MRR) and reports
 *       it once against beta 0 on the other half. With several files the
 *       split is by file, which keeps a session on one side; with one file it
 *       alternates lines.
 *   labelcost TRACE.jsonl...
 *       For each two-thumb overlapped line: the label's interleaved cost (Infinity when no
 *       reading exists), whether the interleaved search found it, and its best hit.
 *       --skip KW and --skipfrom N (any command) set Interleave.SKIP_KW / SKIP_FROM.
 *   splitdata TRACE.jsonl...
 *       One row per buffer of taps then a swipe: real ones (join), and ones built from a tapped
 *       word and the swiped word after it with the space left out (split), with the joined,
 *       tapped and swiped readings and whether PeckSplit splits it. Reads committed labels
 *       (no retype relabelling): it pairs each commit with the next word's context.
 *   rejoindata TRACE.jsonl...
 *       One row per buffer of a word an automatic space ended, reopened, and typed on: a word
 *       and the next word's tokens after its seeded taps (split), and a tapped word cut where its
 *       head is a word (join), with the head, rest and joined readings (PeckSplit.readRejoin).
 *   terms [--lines] TRACE.jsonl...
 *       For each line whose label is listed but not first: the score term it lost most on
 *       against the winner (ScoreTerms), tallied over all such lines.
 *   synth [--assets DIR] [--words N] [--alt LANG] [--noise X] [--seed S] OUT.jsonl
 *       Records a synthetic English session to OUT (default 300 words).
 */
object ReplayCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val a = args.toMutableList()
        val cmd = a.removeFirstOrNull() ?: usage()
        retypes = !a.remove("--plain")
        dedupe = !a.remove("--keepdups")
        opt(a, "--skipfrom")?.let { com.kinetica.keyboard.engine.Interleave.SKIP_FROM = it.toInt() }
        opt(a, "--skip")?.let { com.kinetica.keyboard.engine.Interleave.SKIP_KW = it.toFloat() }
        forceTouch = a.remove("--touchforce")
        val assets = File(opt(a, "--assets") ?: defaultAssets())
        when (cmd) {
            "replay" -> {
                val k = opt(a, "--k")?.toInt() ?: ReplayHarness.DEFAULT_DEEP_K
                val ctc = opt(a, "--ctc")?.let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
                val beta = opt(a, "--beta")?.toFloat() ?: 0f
                val depth = opt(a, "--depth")?.toInt() ?: ReplayHarness.DEFAULT_DEEP_K
                val ilw = opt(a, "--ilw")?.toFloat()
                // --tri B: the two-word context boost cap (0 = off).
                val tri = opt(a, "--tri")?.toFloat() ?: KineticaConstants.TRIGRAM_BOOST_MAX
                // --touch S: the per-user touch model, learning online at strength S (0 = off).
                val touch = touchModel(opt(a, "--touch"))
                // --edge G: the near-edge tap pass's gap in kw (0 = off).
                val edge = opt(a, "--edge")?.toFloat() ?: KineticaConstants.EDGE_TAP_GAP_KW
                // --nosplit: a tapped word followed by a swipe always stays one word.
                val split = !a.remove("--nosplit")
                // --ctcil: rerank two thumbs drawing at once too, each stroke on its own letters.
                // --ctcilonly: that, and no rerank of one-thumb buffers.
                val ctcilOnly = a.remove("--ctcilonly")
                val ctcil = a.remove("--ctcil") || ctcilOnly
                if (a.isEmpty()) usage()
                val harness = ReplayHarness(
                    assets, k, ctc, beta, depth,
                    interleave = ilw?.let { it > 0f } ?: KineticaConstants.INTERLEAVE_ENABLED,
                    interleaveWeight = ilw ?: KineticaConstants.INTERLEAVE_WEIGHT,
                    touch = touch,
                    trigramBoost = tri,
                    edgeTapGap = edge,
                    peckSplit = split,
                    rerankOverlap = ctcil,
                    rerankOverlapOnly = ctcilOnly,
                )
                val report = run(harness, a.map { File(it) })
                println(report.format())
                if (ctc == null && report.exact < report.comparable) System.exit(1)
            }
            "misses" -> {
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    if (r.rank == 1) continue
                    println(describe(n, r))
                }
            }
            "ranks" -> {
                // ranks [--edge G] [--ilw W] [--hdb B] [--hdms MS] [--hdend MS] [--hdmiss K] FILE...: one tab-separated
                // row per scored line, for slicing. --hd* set the held-double-letter terms.
                val ilw = opt(a, "--ilw")?.toFloat()
                // --tri B: the two-word context boost cap (0 = off).
                val tri = opt(a, "--tri")?.toFloat() ?: KineticaConstants.TRIGRAM_BOOST_MAX
                val hdb = opt(a, "--hdb")?.toFloat() ?: KineticaConstants.HELD_DOUBLE_BOOST
                val hdms = opt(a, "--hdms")?.toLong() ?: KineticaConstants.HELD_DOUBLE_MS
                val hdend = opt(a, "--hdend")?.toLong() ?: KineticaConstants.HELD_DOUBLE_END_MS
                val hdmiss = opt(a, "--hdmiss")?.toFloat() ?: 1f
                val touch = touchModel(opt(a, "--touch"))
                val learner = learnerOf(opt(a, "--learn"), opt(a, "--seed"), opt(a, "--pickw")?.toInt() ?: KineticaConstants.PICK_LEARN_UNITS)
                val hdfree = a.remove("--hdfree") || KineticaConstants.HELD_DOUBLE_FREE_TIME
                val edge = opt(a, "--edge")?.toFloat() ?: KineticaConstants.EDGE_TAP_GAP_KW
                val split = !a.remove("--nosplit")
                if (a.isEmpty()) usage()
                val h = ReplayHarness(
                    assets,
                    peckSplit = split,
                    interleave = ilw?.let { it > 0f } ?: KineticaConstants.INTERLEAVE_ENABLED,
                    interleaveWeight = ilw ?: KineticaConstants.INTERLEAVE_WEIGHT,
                    heldDoubleBoost = hdb, heldDoubleMs = hdms, heldDoubleEndMs = hdend,
                    heldDoubleMissKeep = hdmiss,
                    touch = touch,
                    trigramBoost = tri,
                    learner = learner,
                    heldDoubleFree = hdfree,
                    edgeTapGap = edge,
                )
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    println("${f.name}\t$n\t${r.word.label}\t${r.rank}\t${r.buckets.joinToString(",")}\t${r.shipping.firstOrNull()?.word ?: ""}\t${r.micros}")
                }
            }
            "terms" -> {
                // terms [--lines] FILE...: for each line whose label is listed but not first, the
                // score term (ScoreTerms) it lost most on against the winner, tallied. --lines
                // also prints each such line.
                val lines = a.remove("--lines")
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                val decided = java.util.EnumMap<ScoreTerms.Term, Int>(ScoreTerms.Term::class.java)
                val deficit = java.util.EnumMap<ScoreTerms.Term, Double>(ScoreTerms.Term::class.java)
                var top = 0
                var lost = 0
                var unlisted = 0
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val label = w.label?.lowercase() ?: continue
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    when {
                        r.rank == 1 || r.typedAsIs -> { top++; continue }
                        r.rank == 0 -> { unlisted++; continue }
                    }
                    val win = r.shipping[0]
                    val mine = r.shipping[r.rank - 1]
                    lost++
                    var worst = ScoreTerms.Term.FREQ
                    var worstD = Float.MAX_VALUE
                    for (t in ScoreTerms.Term.entries) {
                        val d = mine.terms[t] - win.terms[t]
                        if (d < 0f) deficit[t] = (deficit[t] ?: 0.0) - d
                        if (d < worstD) { worstD = d; worst = t }
                    }
                    decided[worst] = (decided[worst] ?: 0) + 1
                    if (lines) println("${f.name}\t$n\t$label\t${r.rank}\t${win.word}\t${worst.key}\t${"%.3f".format(worstD)}\t${mine.terms} || ${win.terms}")
                }
                println("top=$top lost-in-list=$lost unlisted=$unlisted")
                println("term          decided  mean deficit (ln, over lost lines)")
                for (t in ScoreTerms.Term.entries) {
                    val c = decided[t] ?: 0
                    val d = (deficit[t] ?: 0.0) / maxOf(lost, 1)
                    if (c == 0 && d == 0.0) continue
                    println("%-12s %8d  %.3f".format(t.key, c, d))
                }
            }
            "features" -> {
                // features FILE...: one JSON object per scored line, the shipping list with each
                // candidate's score factors, for fitting score weights offline.
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val label = w.label?.lowercase() ?: continue
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    val src = when { w.target != null -> "practice"; w.how == "tentative" -> "space"; else -> "typed" }
                    val sb = StringBuilder()
                    sb.append("{\"f\":\"").append(f.name).append("\",\"n\":").append(n)
                        .append(",\"label\":\"").append(label.replace("\"", "")).append("\",\"src\":\"").append(src)
                        .append("\",\"asis\":").append(r.typedAsIs)
                        .append(",\"b\":\"").append(r.buckets.joinToString(",")).append("\",\"ctx\":\"")
                        .append(w.context.joinToString(" ").replace("\"", "")).append("\",\"c\":[")
                    r.shipping.forEachIndexed { i, c ->
                        if (i > 0) sb.append(',')
                        sb.append("[\"").append(c.word.replace("\"", "")).append("\",").append(c.score).append(',')
                            .append(c.dtwDistance).append(',').append(c.frequencyWeight).append(',').append(c.bigramMultiplier)
                            .append(',').append(c.personalBoost).append(',').append(c.contactKeep).append(',')
                            .append(c.personalBigram).append(",\"").append(c.source.name).append("\",\"")
                            .append(c.language).append("\",\"").append(c.terms).append("\"]")
                    }
                    sb.append("]}")
                    println(sb)
                }
            }
            "trace" -> {
                // trace FILE N [N...]: replay those lines with DecodeTrace on.
                if (a.size < 2) usage()
                val h = ReplayHarness(assets)
                val want = a.drop(1).map { it.toInt() }.toSet()
                for ((n, w) in read(File(a[0]), ReplayReport(h.deepK))) {
                    if (n !in want) continue
                    println("=== line $n")
                    com.kinetica.keyboard.engine.DecodeTrace.sink = { println("  $it") }
                    val r = try { h.replay(w) } catch (e: RuntimeException) { println("  failed: $e"); null }
                    com.kinetica.keyboard.engine.DecodeTrace.sink = null
                    if (r != null) println(describe(n, r))
                }
            }
            "labelcost" -> {
                val h = ReplayHarness(assets)
                for (f in a.map { File(it) }) for ((n, w) in read(f, ReplayReport(h.deepK))) {
                    val label = w.label?.lowercase() ?: continue
                    val il = com.kinetica.keyboard.engine.Interleave.of(SwipeTrace.replayTokens(w), w.geometry.build())
                    if (il == null) { println("${f.name}\t$n\t$label\tnoIL"); continue }
                    val trie = h.trieFor(w.config.language, w.config.britishSpelling)
                    val hits = com.kinetica.keyboard.engine.InterleavedSearch(trie, il).run(400)
                    val best = hits.minByOrNull { it.cost }
                    println("${f.name}\t$n\t$label\t${il.cost(label)}\t${hits.any { it.word == label }}\t${best?.word}\t${best?.cost}\t${label.length}")
                }
            }
            "splitdata" -> {
                // splitdata FILE...: one tab-separated row per tapped-word-then-swipe buffer, real
                // (kind join: the swipe continued the tapped letters) or built from a tapped word
                // and the swiped word after it with the space left out (kind split).
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        val label = w.label?.lowercase() ?: continue
                        val tokens = try { SwipeTrace.replayTokens(w) } catch (e: RuntimeException) { continue }
                        if (com.kinetica.keyboard.engine.PeckSplit.tapHead(tokens) > 0) {
                            println(splitRow(h, f.name, n, "join", w, tokens, w.context, label, ""))
                        }
                        val next = words.getOrNull(i + 1)?.second ?: continue
                        val nextLabel = next.label?.lowercase() ?: continue
                        if (tokens.isEmpty() || tokens.any { it !is com.kinetica.keyboard.engine.models.TapToken }) continue
                        if (next.context.lastOrNull()?.lowercase() != w.committed?.lowercase()) continue
                        val nextTokens = try { SwipeTrace.replayTokens(next) } catch (e: RuntimeException) { continue }
                        if (nextTokens.minByOrNull { it.tStart } !is com.kinetica.keyboard.engine.models.SwipeToken) continue
                        println(splitRow(h, f.name, n, "split", w, tokens + nextTokens, w.context, label, nextLabel))
                    }
                }
            }
            "rejoindata" -> {
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        val label = w.label?.lowercase() ?: continue
                        if (!label.all { it in 'a'..'z' }) continue
                        val tokens = try { SwipeTrace.replayTokens(w) } catch (e: RuntimeException) { continue }
                        if (tokens.isEmpty()) continue
                        val p = h.predictorFor(w)
                        val one = { ch: Char -> ch in ReplayHarness.ONE_LETTER_WORDS[p.language].orEmpty() }
                        // Join: a tapped word whose letters are the label, cut after a head that is a word.
                        val taps = tokens.sortedBy { it.tStart }
                        val literal = taps.joinToString("") { t -> (t as? com.kinetica.keyboard.engine.models.TapToken)?.let { p.alphabet.charOf(it.code).toString() } ?: "?" }
                        if (literal == label && label.length >= 2 && p.isWord(label)) {
                            for (cut in 1 until label.length) {
                                val head = label.substring(0, cut)
                                if (if (cut == 1) !one(head[0]) else !p.isWord(head)) continue
                                val seeds = seedTaps(p, head, taps.first().tStart) ?: continue
                                println(rejoinRow(p, f.name, n, "join", seeds + taps.drop(cut), cut, w.context, head, label.substring(cut), label, one))
                            }
                        }
                        // Split: this word, then the next one's tokens typed onto it.
                        val next = words.getOrNull(i + 1)?.second ?: continue
                        val nextLabel = next.label?.lowercase() ?: continue
                        if (next.context.lastOrNull()?.lowercase() != w.committed?.lowercase()) continue
                        if (!nextLabel.all { it in 'a'..'z' }) continue
                        if (if (label.length == 1) !one(label[0]) else !p.isWord(label)) continue
                        val nextTokens = try { SwipeTrace.replayTokens(next) } catch (e: RuntimeException) { continue }
                        if (nextTokens.isEmpty()) continue
                        val seeds = seedTaps(p, label, nextTokens.minOf { it.tStart }) ?: continue
                        println(rejoinRow(p, f.name, n, "split", seeds + nextTokens, label.length, w.context, label, nextLabel, label + nextLabel, one))
                    }
                }
            }
            "interleave" -> {
                if (a.isEmpty()) usage()
                println(interleaveReport(assets, a.map { File(it) }))
            }
            "tune" -> {
                val ctc = File(opt(a, "--ctc") ?: usage()).inputStream().use { CtcScorer.load(it) }
                val betas = (opt(a, "--betas") ?: "0,0.02,0.05,0.1,0.2,0.3,0.5,0.8,1.2")
                    .split(",").map { it.trim().toFloat() }
                val depth = opt(a, "--depth")?.toInt() ?: ReplayHarness.DEFAULT_DEEP_K
                if (a.isEmpty()) usage()
                println(tune(assets, ctc, betas, depth, a.map { File(it) }))
            }
            "synth" -> {
                val n = opt(a, "--words")?.toInt() ?: 300
                val alt = opt(a, "--alt")
                val noise = opt(a, "--noise")?.toFloat() ?: 1f
                val seed = opt(a, "--seed")?.toLong() ?: 7L
                val out = File(a.singleOrNull() ?: usage())
                val s = SyntheticSession(assets, seed, noise)
                out.bufferedWriter().use { w -> s.record(s.words("en", n), "en", alt) { w.write(it); w.newLine() } }
                println("wrote ${out.path}")
            }
            else -> usage()
        }
    }

    fun run(harness: ReplayHarness, files: List<File>): ReplayReport {
        val report = ReplayReport(harness.deepK)
        for (f in files) {
            for ((lineNo, w) in read(f, report)) {
                try {
                    report.add(harness.replay(w))
                } catch (e: RuntimeException) {
                    report.error(lineNo, e)
                }
            }
        }
        return report
    }

    /**
     * Relabel retyped words (on by default; `--plain` turns it off). Plain labels score a typo
     * the user deleted and retyped as right, so a change that fixes it reads as a loss.
     */
    var retypes = true

    /** Skip a word line identical to one already read into the same report (see [ReplayReport.seenLines]). */
    var dedupe = true

    private fun digest(line: String): String =
        java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-1").digest(line.toByteArray()),
        )

    /**
     * Word lines of [f] with their line numbers, correction and discard lines
     * applied: a correction relabels the most recent earlier word committed as
     * its `from`, a discard drops the most recent earlier word.
     *
     * With [retypes], a free-typed commit that the user undid (a backspace, or a buffer
     * abandoned after it) is relabelled as the next word they committed, when that word is close
     * (see [retypeOf]). A retype that only extends the commit ("ca" then "can") means the
     * first commit was a stray space mid-word, so its line is left unlabelled.
     */
    fun read(f: File, report: ReplayReport): List<Pair<Int, SwipeTrace.Word>> {
        val words = ArrayList<Pair<Int, SwipeTrace.Word>>()
        // Indexes in [words] of the last free-typed commit and the earlier commits relabelled to it.
        var chain = ArrayList<Int>()
        var undone = false
        // The last word line was a repeat, so the key, correction and discard lines after it
        // belong to that repeat too.
        var skipped = false
        f.useLines { lines ->
            for ((i, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                try {
                    when (val l = SwipeTrace.decodeLine(line)) {
                        is SwipeTrace.Line.WordLine -> {
                            if (dedupe && !report.seenLines.add(digest(line))) {
                                report.duplicates++
                                skipped = true
                                continue
                            }
                            skipped = false
                            val w = l.word
                            val committed = w.committed
                            if (committed == null) {
                                // A buffer cleared without a commit: older traces log no keys,
                                // and backspaces inside a buffer aren't logged as keys either.
                                if (chain.isNotEmpty()) undone = true
                                words.add(i + 1 to w)
                                continue
                            }
                            if (retypes && undone && chain.isNotEmpty()) {
                                val prev = words[chain.last()].second.committed
                                when (retypeOf(prev, committed)) {
                                    Retype.CLOSE -> for (k in chain) {
                                        val (n, old) = words[k]
                                        words[k] = n to old.copy(committed = committed, how = "retyped")
                                        report.retyped++
                                    }
                                    Retype.EXTENDS -> {
                                        for (k in chain) {
                                            val (n, old) = words[k]
                                            words[k] = n to old.copy(committed = null)
                                            report.extended++
                                        }
                                        chain = ArrayList()
                                    }
                                    Retype.NONE -> chain = ArrayList()
                                }
                            } else {
                                chain = ArrayList()
                            }
                            undone = false
                            words.add(i + 1 to w)
                            if (w.target == null) chain.add(words.size - 1) else chain = ArrayList()
                        }
                        is SwipeTrace.Line.Discard -> if (!skipped) { words.removeLastOrNull(); chain = ArrayList() }
                        is SwipeTrace.Line.Key -> if (!skipped && l.name == "backspace" && chain.isNotEmpty()) undone = true
                        is SwipeTrace.Line.Correction -> if (!skipped) {
                            val k = words.indexOfLast { it.second.committed.equals(l.from, ignoreCase = true) }
                            if (k >= 0) {
                                val (n, w) = words[k]
                                words[k] = n to w.copy(committed = l.to, how = "corrected")
                            }
                        }
                    }
                } catch (e: RuntimeException) {
                    report.error(i + 1, e)
                }
            }
        }
        return words
    }

    enum class Retype { NONE, CLOSE, EXTENDS }

    /**
     * How [retyped] relates to the earlier commit [was]: CLOSE when it differs by at most half
     * the longer word's letters (go/to, gels/tells, bit/but), EXTENDS when it only adds letters
     * at the end, NONE otherwise (a stray "j" before "the", or a different word altogether).
     */
    fun retypeOf(was: String?, retyped: String): Retype {
        val a = was?.lowercase() ?: return Retype.NONE
        val b = retyped.lowercase()
        if (a == b || a.isEmpty()) return Retype.NONE
        if (b.startsWith(a)) return Retype.EXTENDS
        return if (editDistance(a, b) <= maxOf(1, maxOf(a.length, b.length) / 2)) Retype.CLOSE else Retype.NONE
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * One line per miss: label, shipping and deep rank, buckets, the top three,
     * then each token in time order - T for a tap (key), S for a swipe (its key
     * contacts, arc length) - with stream, start and end relative to the first.
     */
    fun describe(lineNo: Int, r: ReplayHarness.Result): String {
        val sb = StringBuilder()
        sb.append("#$lineNo ${r.word.label} rank=${r.rank} deep=${r.deepRank} ${r.buckets.joinToString(",")}")
        sb.append(" top=").append(r.shipping.take(3).joinToString("/") { it.word })
        val t0 = r.tokens.minOfOrNull { it.tStart } ?: 0L
        for (t in r.tokens.sortedBy { it.tStart }) {
            val s = if (t.streamId == com.kinetica.keyboard.engine.models.StreamId.LEFT) "L" else "R"
            sb.append("\n    ")
            when (t) {
                is com.kinetica.keyboard.engine.models.TapToken ->
                    sb.append("T$s ${com.kinetica.keyboard.engine.Alphabet.charOf(t.code)}")
                is com.kinetica.keyboard.engine.models.SwipeToken -> {
                    sb.append("S$s ")
                    t.keyContacts.forEach { sb.append(com.kinetica.keyboard.engine.Alphabet.charOf(it.code)) }
                    sb.append(String.format(java.util.Locale.ROOT, " arc=%.1f dwells=%d", t.arcLen, t.dwells.size))
                }
            }
            sb.append(" ${t.tStart - t0}..${t.tEnd - t0}ms")
        }
        return sb.toString()
    }

    /**
     * The interleaved two-thumb reading on its own: for every line it applies
     * to, the label's rank among [com.kinetica.keyboard.engine.InterleavedSearch]
     * hits under several geometric scales, next to the shipping rank.
     */
    fun interleaveReport(assets: File, files: List<File>): String {
        val h = ReplayHarness(assets)
        val sink = ReplayReport(h.deepK)
        val scales = listOf(1f, 2f, 3f, 5f, 8f, 12f)
        val top1 = IntArray(scales.size)
        val top3 = IntArray(scales.size)
        val any = IntArray(1)
        var n = 0
        var ship1 = 0
        var ship3 = 0
        var maxMs = 0L
        val times = ArrayList<Long>()
        val sb = StringBuilder()
        for (f in files) for ((lineNo, w) in read(f, sink)) {
            val label = w.label?.lowercase() ?: continue
            val tokens = SwipeTrace.replayTokens(w)
            val g = w.geometry.build()
            val il = com.kinetica.keyboard.engine.Interleave.of(tokens, g) ?: continue
            val trie = h.trieFor(w.config.language, w.config.britishSpelling)
            val t0 = System.nanoTime()
            val search = com.kinetica.keyboard.engine.InterleavedSearch(trie, il)
            val hits = search.run(400)
            val ms = (System.nanoTime() - t0) / 1_000_000
            times.add(ms)
            if (ms > maxMs) maxMs = ms
            val r = h.replay(w)
            n++
            if (r.rank == 1) ship1++
            if (r.rank in 1..3) ship3++
            if (hits.any { it.word == label }) any[0]++
            val ranks = scales.map { sc ->
                val sorted = hits.sortedByDescending { it.fw * kotlin.math.exp(-sc * it.cost * it.cost) }
                sorted.indexOfFirst { it.word == label } + 1
            }
            for ((i, rk) in ranks.withIndex()) {
                if (rk == 1) top1[i]++
                if (rk in 1..3) top3[i]++
            }
            val best = hits.sortedByDescending { it.fw * kotlin.math.exp(-3f * it.cost * it.cost) }.take(3)
            sb.appendLine("#$lineNo $label ship=${r.rank} il=${ranks.joinToString("/")} nodes=${search.visited} ${ms}ms top=${best.joinToString("/") { it.word + String.format(java.util.Locale.ROOT, ":%.2f", it.cost) }}")
        }
        times.sort()
        sb.appendLine("lines=$n shipping top1=${pct(ship1, n)} top3=${pct(ship3, n)}; label among hits ${pct(any[0], n)}")
        for ((i, sc) in scales.withIndex()) sb.appendLine("  scale $sc: top1=${pct(top1[i], n)} top3=${pct(top3[i], n)}")
        if (times.isNotEmpty()) sb.appendLine("search ms on this JVM: p50 ${times[times.size / 2]} p95 ${times[(times.size * 95) / 100]} max $maxMs")
        return sb.toString()
    }

    private fun splitRow(
        h: ReplayHarness,
        file: String,
        n: Int,
        kind: String,
        w: SwipeTrace.Word,
        tokens: List<com.kinetica.keyboard.engine.models.InputToken>,
        context: List<String>,
        label: String,
        label2: String,
    ): String {
        val p = h.predictorFor(w)
        val sorted = tokens.sortedBy { it.tStart }
        val head = com.kinetica.keyboard.engine.PeckSplit.tapHead(sorted)
        val taps = sorted.take(head)
        val rest = sorted.drop(head)
        val literal = taps.joinToString("") { p.alphabet.charOf((it as com.kinetica.keyboard.engine.models.TapToken).code).toString() }
        val joined = p.decode(sorted, context)
        val tapList = p.decode(taps, context)
        val tap = tapList.firstOrNull { it.source != com.kinetica.keyboard.engine.models.WordCandidate.Source.COMPLETION }
        val tapWord = tap?.word ?: literal
        val restList = p.decode(rest, (context.takeLast(1) + tapWord))
        val gap = rest.first().tStart - taps.maxOf { it.tEnd }
        fun c(x: com.kinetica.keyboard.engine.models.WordCandidate?) = if (x == null) "-\t0\t-" else "${x.word}\t${x.score}\t${x.source.name}"
        return listOf(
            file, n, kind, label, label2, literal, p.isWord(literal), gap, rest.count { it !is com.kinetica.keyboard.engine.models.TapToken },
            c(joined.firstOrNull()), ReplayHarness.rankOf(label, joined), c(tap),
            tapList.firstOrNull { it.word == literal }?.score ?: 0f,
            c(restList.firstOrNull()), ReplayHarness.rankOf(label2.ifEmpty { "-" }, restList),
            restList.firstOrNull { it.word == label }?.score ?: 0f,
            com.kinetica.keyboard.engine.PeckSplit.split(p, sorted, context, joined, { ch -> ch in ReplayHarness.ONE_LETTER_WORDS[p.language].orEmpty() }) != null,
        ).joinToString("\t")
    }

    // A reopened word's taps as the IME seeds them: key centres, 1 ms apart, just before [before].
    private fun seedTaps(p: WordPredictor, word: String, before: Long): List<com.kinetica.keyboard.engine.models.InputToken>? {
        val g = p.geometry ?: return null
        val codes = p.alphabet.encode(word) ?: return null
        return codes.mapIndexed { i, code ->
            if (!g.hasKey(code)) return null
            com.kinetica.keyboard.engine.models.TapToken(
                com.kinetica.keyboard.engine.models.StreamId.LEFT, code, g.centerX(code), g.centerY(code),
                longPress = false, tStart = before - 10 + i, tEnd = before - 9 + i,
            )
        }
    }

    private fun rejoinRow(
        p: WordPredictor,
        file: String,
        n: Int,
        kind: String,
        tokens: List<com.kinetica.keyboard.engine.models.InputToken>,
        head: Int,
        context: List<String>,
        headWord: String,
        restLabel: String,
        joinedLabel: String,
        one: (Char) -> Boolean,
    ): String {
        val joined = p.decode(tokens.sortedBy { it.tStart }, context)
        val s = com.kinetica.keyboard.engine.PeckSplit.readRejoin(p, tokens, head, context, joined, one)
        val restTokens = tokens.drop(head)
        val restTaps = restTokens.all { it is com.kinetica.keyboard.engine.models.TapToken }
        val firstRest = restTokens.minByOrNull { it.tStart }
        val liveFuse = firstRest is com.kinetica.keyboard.engine.models.TapToken &&
            p.isLivePrefix(headWord + p.alphabet.charOf(firstRest.code))
        val tap = s?.tap?.firstOrNull { it.source != com.kinetica.keyboard.engine.models.WordCandidate.Source.COMPLETION }
        val rest = s?.rest?.firstOrNull()
        return listOf(
            file, n, kind, headWord, restLabel, joinedLabel, restTaps, liveFuse,
            tap?.word ?: "-", tap?.score ?: 0f, rest?.word ?: "-", rest?.score ?: 0f,
            joined.firstOrNull()?.word ?: "-", joined.firstOrNull()?.score ?: 0f,
            s?.chosen ?: false, joined.firstOrNull { it.word == joinedLabel }?.score ?: 0f,
        ).joinToString("\t")
    }

    private fun pct(a: Int, n: Int) = String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * a / maxOf(n, 1))

    /** Beta chosen on the tuning half, then one comparison on the held-out half. */
    fun tune(assets: File, ctc: CtcScorer, betas: List<Float>, depth: Int, files: List<File>): String {
        val sink = ReplayReport(ReplayHarness.DEFAULT_DEEP_K)
        val perFile = files.map { f -> read(f, sink).map { it.second } }
        val (tuneSet, testSet) = if (files.size >= 2) {
            val k = files.size / 2
            perFile.take(k).flatten() to perFile.drop(k).flatten()
        } else {
            val all = perFile.flatten()
            all.filterIndexed { i, _ -> i % 2 == 0 } to all.filterIndexed { i, _ -> i % 2 == 1 }
        }
        fun eval(beta: Float, words: List<SwipeTrace.Word>): ReplayReport {
            val h = ReplayHarness(assets, ReplayHarness.DEFAULT_DEEP_K, ctc, beta, depth)
            val r = ReplayReport(h.deepK)
            for (w in words) try { r.add(h.replay(w)) } catch (e: RuntimeException) { r.error(0, e) }
            return r
        }
        val sb = StringBuilder()
        sb.appendLine("tuning on ${tuneSet.size} lines, holding out ${testSet.size}")
        var best = 0f
        var bestKey = -1.0
        for (b in betas) {
            val r = eval(b, tuneSet)
            val n = r.all.n.coerceAtLeast(1)
            val key = r.all.top1 * 1000.0 / n + r.all.rr / n
            sb.appendLine(String.format(java.util.Locale.ROOT, "  beta %.3f  top1 %.1f%%  MRR %.3f", b, 100.0 * r.all.top1 / n, r.all.rr / n))
            if (key > bestKey) { bestKey = key; best = b }
        }
        sb.appendLine("chosen beta $best; held-out result, reported once:")
        sb.appendLine("--- beta 0 (today) ---")
        sb.append(eval(0f, testSet).format())
        sb.appendLine("--- beta $best ---")
        sb.append(eval(best, testSet).format())
        return sb.toString()
    }

    private var forceTouch = false

    /**
     * --learn words|phrases: learn in trace order (see [ReplayHarness.Learner]).
     * --seed FILE: lang<TAB>word lines merged from the start, as an imported user dictionary.
     */
    private fun learnerOf(mode: String?, seed: String?, pickWeight: Int): ReplayHarness.Learner? {
        if (mode == null && seed == null) return null
        val words = seed?.let { f ->
            File(f).readLines().mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 } }
                .groupBy({ it[0] }, { it[1] })
        }.orEmpty()
        return ReplayHarness.Learner(
            phrases = mode == "phrases",
            seedWords = words,
            pickWeight = pickWeight,
        )
    }

    /** A fresh online-learning touch model at strength [s], or null when [s] is absent or 0. */
    private fun touchModel(s: String?): com.kinetica.keyboard.engine.TouchModel? {
        val v = s?.toFloat() ?: return null
        if (v <= 0f) return null
        // --touchforce: apply the offsets without the model's own self-check, for sweeps.
        return com.kinetica.keyboard.engine.TouchModel().also { it.strength = v; it.force = forceTouch }
    }

    private fun opt(a: MutableList<String>, name: String): String? {
        val i = a.indexOf(name)
        if (i < 0) return null
        require(i + 1 < a.size) { "$name needs a value" }
        val v = a[i + 1]
        a.removeAt(i + 1)
        a.removeAt(i)
        return v
    }

    private fun defaultAssets(): String =
        listOf("app/src/main/assets", "src/main/assets", "../../app/src/main/assets")
            .firstOrNull { File(it, "dictionaries").isDirectory } ?: "app/src/main/assets"

    private fun usage(): Nothing {
        System.err.println(
            "usage: replay [--assets DIR] [--k N] [--ctc MODEL --beta B --depth D] [--tri B] TRACE.jsonl...\n" +
                "       tune --ctc MODEL [--betas LIST] [--depth D] TRACE.jsonl...\n" +
                "       synth [--assets DIR] [--words N] [--alt LANG] [--noise X] [--seed S] OUT.jsonl",
        )
        System.exit(2)
        throw IllegalStateException()
    }
}
