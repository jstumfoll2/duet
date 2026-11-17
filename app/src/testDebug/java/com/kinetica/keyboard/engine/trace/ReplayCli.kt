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
 *       --solobeta B, --sologate G, --soloshallow: the one-thumb rerank's beta, margin gate and
 *       shallow list (CtcReranker). --recent B: the session boost (SessionWords). --learn
 *       words|phrases: learn each label after decoding it. --dump OUT: a JSON row per line.
 *       --score loglinear [--lmA A] [--lmB B] [--lmSigma S] [--lmBackoff F] [--lmIl K] [--lmIlw W] (any command): the
 *       log-linear score with a real language model (ScoreModel, issue #124).
 *       --taps [--tapSigma S] [--tapW W] [--tapRadius R] [--tapMax N] (any command): continuous
 *       tap likelihood over the keys near each tap (ScoreModel.taps, issue #127).
 *   replay|ranks --personal BACKUP: decode with the learned words, counts and pairs of a
 *       settings backup (Settings > Back up), frozen unless ranks is given --learn as well.
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
 *   swipesplit TRACE.jsonl...
 *       A swiped word and the swiped word after it with the space left out, read back by
 *       PeckSplit.readSwipe as a backspace would: how often both words come back. Also the real
 *       buffers with a pause between swipes, and how often their joined word was right.
 *   spacesplit TRACE.jsonl...
 *       Tapped letters that are no word but cut into two words: today's split, the
 *       pause-aware split, each letter's pause and the next commits, one row each (#64).
 *   grammar TRACE.jsonl...
 *       The grammar pass over each committed English word and the two before it: every
 *       rewrite it would make, with the word pairs it read (#55).
 *   autofix [--lexicon] TRACE.jsonl...
 *       The grammar auto-fixes (GrammarAutoFix) over each committed English word and the two
 *       before it: every fix they would make and the next commits after it. --lexicon also
 *       lists every English dictionary word joinedTo would cut.
 *   rejoindata TRACE.jsonl...
 *       One row per buffer of a word an automatic space ended, reopened, and typed on: a word
 *       and the next word's tokens after its seeded taps (split), and a tapped word cut where its
 *       head is a word (join), with the head, rest and joined readings (PeckSplit.readRejoin).
 *   terms [--lines] TRACE.jsonl...
 *       For each line whose label is listed but not first: the score term it lost most on
 *       against the winner (ScoreTerms), tallied over all such lines.
 *   --boundary (any command) decides word boundaries with the fitted BoundaryModel.
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
        opt(a, "--streamgate")?.let { KineticaConstants.STREAM_SEARCH_GATE = it.toInt() }
        opt(a, "--rescued")?.let { KineticaConstants.RESCUE_MIN_LEAD_D = it.toFloat() }
        // --reuse KW: the charge for a reused letter whose key was visited once (#98); 0 = off.
        opt(a, "--reuse")?.let { com.kinetica.keyboard.engine.Interleave.REUSE_KW = it.toFloat() }
        opt(a, "--shortarc")?.let { KineticaConstants.SHORT_READING_ARC_KW = it.toFloat() }
        // --stitch MS: join a thumb's strokes across a lift shorter than MS (0 = off).
        opt(a, "--stitch")?.let { com.kinetica.keyboard.engine.ContactRepair.STITCH_GAP_MS = it.toLong() }
        // --aim S: per-key aim points learning online at strength S (0 = off); see AimModel.
        aimModel = opt(a, "--aim")?.toFloat()?.takeIf { it > 0f }?.let { v -> com.kinetica.keyboard.engine.AimModel().also { it.strength = v } }
        // --hintz Z: how many standard deviations of a key's learned touch spread still hint it.
        opt(a, "--hintz")?.let { com.kinetica.keyboard.engine.TouchModel.HINT_Z = it.toFloat() }
        opt(a, "--hintshift")?.let { com.kinetica.keyboard.engine.TouchModel.HINT_MIN_SHIFT_KW = it.toFloat() }
        // --boundary (any command): word boundaries decided by the fitted BoundaryModel.
        if (a.remove("--boundary")) com.kinetica.keyboard.engine.BoundaryModel.enabled = true
        // --score loglinear (any command): the log-linear score with a real language model (#124);
        // --lmA, --lmB, --lmSigma, --lmBackoff set its weights (ScoreModel).
        opt(a, "--score")?.let { com.kinetica.keyboard.engine.ScoreModel.mode = com.kinetica.keyboard.engine.ScoreModel.Mode.valueOf(it.uppercase()) }
        opt(a, "--lmA")?.let { com.kinetica.keyboard.engine.ScoreModel.A = it.toFloat() }
        opt(a, "--lmB")?.let { com.kinetica.keyboard.engine.ScoreModel.B = it.toFloat() }
        opt(a, "--lmSigma")?.let { com.kinetica.keyboard.engine.ScoreModel.SIGMA = it.toFloat() }
        opt(a, "--lmIl")?.let { com.kinetica.keyboard.engine.ScoreModel.IL_SCALE = it.toFloat() }
        opt(a, "--lmIlw")?.let { com.kinetica.keyboard.engine.ScoreModel.IL_WEIGHT = it.toFloat() }
        // --speed [--speedGamma G] [--speedMax M]: speed-weighted DTW (ShapeModel, issue #129).
        if (a.remove("--speed")) com.kinetica.keyboard.engine.ShapeModel.speed = true
        opt(a, "--speedGamma")?.let { com.kinetica.keyboard.engine.ShapeModel.GAMMA = it.toFloat() }
        opt(a, "--speedMax")?.let { com.kinetica.keyboard.engine.ShapeModel.MAX_W = it.toFloat() }
        opt(a, "--gateMargin")?.let {
            com.kinetica.keyboard.engine.ScoreModel.marginGate = true
            com.kinetica.keyboard.engine.ScoreModel.GATE_MARGIN = it.toFloat()
        }
        opt(a, "--lmIlB")?.let { com.kinetica.keyboard.engine.ScoreModel.IL_B = it.toFloat() }
        opt(a, "--lmSigmaLen")?.let { com.kinetica.keyboard.engine.ScoreModel.SIGMA_LEN = it.toFloat() }
        opt(a, "--lmSigmaFrom")?.let { com.kinetica.keyboard.engine.ScoreModel.SIGMA_LEN_FROM = it.toInt() }
        opt(a, "--lmBackoff")?.let { com.kinetica.keyboard.engine.ScoreModel.BACKOFF = it.toFloat() }
        // --taps: continuous tap likelihood (ScoreModel.taps, issue #127), --tapSigma, --tapW,
        // --tapRadius, --tapMax its constants.
        if (a.remove("--taps")) com.kinetica.keyboard.engine.ScoreModel.taps = true
        opt(a, "--tapSigma")?.let { com.kinetica.keyboard.engine.ScoreModel.TAP_SIGMA = it.toFloat() }
        opt(a, "--tapW")?.let { com.kinetica.keyboard.engine.ScoreModel.TAP_W = it.toFloat() }
        opt(a, "--tapRadius")?.let { com.kinetica.keyboard.engine.ScoreModel.TAP_RADIUS = it.toFloat() }
        opt(a, "--tapMax")?.let { com.kinetica.keyboard.engine.ScoreModel.TAP_MAX_NATS = it.toFloat() }
        // --latW E,B,G,PT,PTG,NT,NTG: the lattice's weights (LatticeDecoder.setWeights, #133).
        opt(a, "--latW")?.let { w -> com.kinetica.keyboard.engine.LatticeDecoder.setWeights(w.split(',').map { it.toFloat() }.toFloatArray()) }
        opt(a, "--latMargin")?.let { com.kinetica.keyboard.engine.LatticeDecoder.MARGIN = it.toFloat() }
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
                val duoModel = opt(a, "--duo")?.let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
                val duoBeta = opt(a, "--duoBeta")?.toFloat() ?: 0f
                val duoTaps = !a.remove("--duoNoTaps")
                val duoGate = opt(a, "--duoGate")?.toFloat() ?: 1.5f
                // --personal BACKUP: decode with the personal store of a settings backup (#97).
                // --recent B: session recency boost B (1 = off, the default); --recentfreq L its
                // frequency cap as ln of the weight.
                val recent = opt(a, "--recent")?.toFloat()?.takeIf { it != 1f }?.let {
                    KineticaConstants.SESSION_BOOST = it
                    com.kinetica.keyboard.engine.SessionWords()
                }
                opt(a, "--recentfreq")?.let { KineticaConstants.SESSION_MAX_LN_FREQ = it.toFloat() }
                // --learn words|phrases: learn each label after decoding it, as `ranks --learn` does.
                // --retypes SRC,SRC: learn "decoded Y, meant X" penalties (#131) in trace order from
                // lines labelled by these sources (retyped, corrected, reswiped, confirmed, picked).
                // --retypeW W: the penalty's exponent; --retypeMin N: net count it starts at.
                val retypeSources = opt(a, "--retypes")?.split(',')?.toSet()
                com.kinetica.keyboard.engine.RetypePenalties.enabled = retypeSources != null
                opt(a, "--retypeW")?.let { com.kinetica.keyboard.engine.RetypePenalties.WEIGHT = it.toFloat() }
                opt(a, "--retypeMin")?.let { com.kinetica.keyboard.engine.RetypePenalties.MIN_COUNT = it.toInt() }
                opt(a, "--retypePrior")?.let { com.kinetica.keyboard.engine.RetypePenalties.PRIOR = it.toFloat() }
                val learnMode = opt(a, "--learn")
                val personal = opt(a, "--personal").let { p ->
                    if (learnMode != null) learnerOf(learnMode, null, KineticaConstants.PICK_LEARN_UNITS, p)
                    else p?.let { personalOf(File(it)) }
                }
                // --templates SRC,SRC|all: learn the user's own swipe paths (#138) in trace order
                // and match them. --tplMin N paths before a word is matched, --tplMax D kw,
                // --tplGate G kw at the ends.
                val templateSources = opt(a, "--templates")?.split(',')?.toSet()
                com.kinetica.keyboard.engine.ShapeTemplates.enabled = templateSources != null
                opt(a, "--tplMin")?.let { com.kinetica.keyboard.engine.ShapeTemplates.MIN_TEMPLATES = it.toInt() }
                opt(a, "--tplMax")?.let { com.kinetica.keyboard.engine.ShapeTemplates.MAX_DISTANCE_KW = it.toFloat() }
                opt(a, "--tplGate")?.let { com.kinetica.keyboard.engine.ShapeTemplates.ENDPOINT_GATE_KW = it.toFloat() }
                // --dump OUT: one JSON object per scored line (label, source, context, rank and the
                // bar's list with each candidate's score terms), for studying rank 2-10 losses.
                dump = opt(a, "--dump")?.let { File(it).printWriter() }
                if (a.isEmpty()) usage()
                val harness = ReplayHarness(
                    assets, k, ctc, beta, depth,
                    interleave = ilw?.let { it > 0f } ?: KineticaConstants.INTERLEAVE_ENABLED,
                    interleaveWeight = ilw ?: KineticaConstants.INTERLEAVE_WEIGHT,
                    touch = touch,
                    aim = aimModel,
                    trigramBoost = tri,
                    edgeTapGap = edge,
                    peckSplit = split,
                    rerankOverlap = ctcil,
                    rerankOverlapOnly = ctcilOnly,
                    learner = personal,
                    recent = recent,
                    soloBeta = opt(a, "--solobeta")?.toFloat() ?: Float.NaN,
                    soloGate = opt(a, "--sologate")?.toFloat() ?: Float.POSITIVE_INFINITY,
                    soloShallow = a.remove("--soloshallow"),
                    retypeSources = retypeSources,
                    // --duo MODEL --duoBeta B [--duoGate G] [--duoNoTaps]: the two-thumb encoder over the candidates (#134).
                    duo = duoModel,
                    duoBeta = duoBeta,
                    duoTaps = duoTaps,
                    duoGate = duoGate,
                    templateSources = templateSources,
                )
                val report = run(harness, a.map { File(it) })
                dump?.close()
                println(report.format())
                if (templateSources != null) {
                    println("shape templates: " + harness.templateStores.values.joinToString(" ") { "${it.size} words (${it.ready} matched)" })
                }
                if (retypeSources != null) {
                    println("retype pairs learned: ${harness.retypeStore.size}")
                    val rows = harness.retypeStore.serialize().split(',').map { it.split('|') }.filter { it.size == 4 && it[2].isNotEmpty() }
                    println("most counted: " + rows.sortedByDescending { it[3].toInt() }.take(25).joinToString(" ") { "${it[1]}>${it[2]}x${it[3]}/${harness.retypeStore.seen("en", it[1])}" })
                }
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
                val learner = learnerOf(
                    opt(a, "--learn"), opt(a, "--seed"), opt(a, "--pickw")?.toInt() ?: KineticaConstants.PICK_LEARN_UNITS,
                    opt(a, "--personal"),
                )
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
                    aim = aimModel,
                    trigramBoost = tri,
                    learner = learner,
                    heldDoubleFree = hdfree,
                    edgeTapGap = edge,
                )
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    println("${f.name}\t$n\t${r.word.label}\t${r.rank}\t${r.buckets.joinToString(",")}\t${r.shipping.firstOrNull()?.word ?: ""}\t${r.micros}\t${r.word.how ?: ""}\t${shape(r)}\t${r.deepRank}\t${r.word.context.lastOrNull() ?: ""}\t${r.shipping.take(8).joinToString(",") { it.word }}")
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
            "deepfeatures" -> {
                // deepfeatures FILE...: one JSON object per scored line with the deep list (--k
                // deep), each candidate's score terms in ScoreTerms.Term order, its fit d, source,
                // and the centre-to-centre length of its letters, for refitting the score (#68).
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val label = w.label?.lowercase() ?: continue
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    val g = w.geometry.build()
                    val alphabet = h.predictorFor(w).alphabet
                    val swipes = r.tokens.filterIsInstance<com.kinetica.keyboard.engine.models.SwipeToken>()
                    fun ideal(word: String): Float {
                        val codes = alphabet.encode(com.kinetica.keyboard.engine.AccentFolder.fold(word.lowercase()).replace("'", "")) ?: return -1f
                        var len = 0f
                        for (i in 1 until codes.size) {
                            if (!g.hasKey(codes[i - 1]) || !g.hasKey(codes[i])) return -1f
                            len += g.keyDist(codes[i - 1], codes[i])
                        }
                        return len
                    }
                    val sb = StringBuilder()
                    sb.append("{\"f\":\"").append(f.name).append("\",\"n\":").append(n)
                        .append(",\"label\":\"").append(label.replace("\"", "")).append("\",\"asis\":").append(r.typedAsIs)
                        .append(",\"rank\":").append(r.rank)
                        .append(",\"b\":\"").append(r.buckets.joinToString(",")).append("\"")
                        .append(",\"taps\":").append(r.tokens.size - swipes.size)
                        .append(",\"arcs\":[").append(swipes.joinToString(",") { it.arcLen.toString() }).append("]")
                        .append(",\"c\":[")
                    r.deep.forEachIndexed { i, c ->
                        if (i > 0) sb.append(',')
                        sb.append("[\"").append(c.word.replace("\"", "")).append("\",").append(c.score).append(',')
                            .append(c.dtwDistance).append(",\"").append(c.source.name).append("\",").append(ideal(c.word)).append(",[")
                            .append(ScoreTerms.Term.entries.joinToString(",") { c.terms[it].toString() }).append("]]")
                    }
                    sb.append("]}")
                    println(sb)
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
            "prevfix" -> {
                // prevfix [replay flags] [--gap G] [--margin M] [--alts N] FILE...: the previous-word
                // fix (PreviousWordFix, issue #130) over each pair of consecutive free-typed words.
                // The first word is committed as replay's own lead; the second is decoded after it
                // and after each close runner-up. Counts flips that fix the first word (lead wrong,
                // flipped word is the label), break it (lead was the label) or swap one wrong word
                // for another, by whether the first word's label is the user's own or an accepted
                // decode, and the extra decodes it cost.
                val ctc = opt(a, "--ctc")?.let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
                val beta = opt(a, "--beta")?.toFloat() ?: 0f
                val ctcilOnly = a.remove("--ctcilonly")
                opt(a, "--gap")?.let { com.kinetica.keyboard.engine.PreviousWordFix.GAP = it.toFloat() }
                opt(a, "--margin")?.let { com.kinetica.keyboard.engine.PreviousWordFix.MARGIN = it.toFloat() }
                opt(a, "--alts")?.let { com.kinetica.keyboard.engine.PreviousWordFix.MAX_ALTERNATES = it.toInt() }
                val examples = a.remove("--examples")
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets, ctc = ctc, beta = beta, aim = aimModel, rerankOverlap = ctcilOnly, rerankOverlapOnly = ctcilOnly)
                val sink = ReplayReport(h.deepK)
                fun readings(l: List<com.kinetica.keyboard.engine.models.WordCandidate>) =
                    l.map { com.kinetica.keyboard.engine.PreviousWordFix.Reading(it.word, it.score) }
                // [true labels, accepted labels] x [pairs, tried, fixed, broke, swapped, lead right]
                val tally = Array(2) { IntArray(6) }
                var decodes = 0
                for (f in a.map { File(it) }) {
                    var prev: Pair<SwipeTrace.Word, ReplayHarness.Result>? = null
                    for ((_, w) in read(f, sink)) {
                        val r = try { h.replay(w) } catch (e: RuntimeException) { prev = null; continue }
                        val p = prev
                        prev = if (w.target == null && w.label != null && r.shipping.isNotEmpty()) w to r else null
                        // A buffer dropped without a commit is no evidence about the word before it.
                        if (p == null || w.target != null || r.shipping.isEmpty() || w.committed == null) continue
                        val (pw, pr) = p
                        // Consecutive in the text: this word's context ends in the word before it.
                        if (!w.context.lastOrNull().equals(pw.committed, ignoreCase = true)) continue
                        val label = pw.label!!.lowercase()
                        val lead = pr.shipping[0].word
                        val own = pw.how == "retyped" || pw.how == "reswiped" || pw.how == "corrected" || pw.how == "confirmed" || pw.how == "picked"
                        val t = tally[if (own) 0 else 1]
                        t[0]++
                        if (lead.lowercase() == label) t[5]++
                        val before = w.context.dropLast(1)
                        val prevList = readings(pr.shipping)
                        val alts = com.kinetica.keyboard.engine.PreviousWordFix.candidates(prevList, lead)
                        if (alts.isEmpty()) continue
                        t[1]++
                        val given = readings(h.shippingFor(w, before + lead))
                        val byAlt = alts.associate { alt -> alt.word to readings(h.shippingFor(w, before + alt.word)) }
                        decodes += 1 + alts.size
                        val flip = com.kinetica.keyboard.engine.PreviousWordFix.decide(prevList, lead, given, byAlt) ?: continue
                        val was = lead.lowercase() == label
                        val now = flip.lowercase() == label
                        when {
                            !was && now -> t[2]++
                            was && !now -> t[3]++
                            else -> t[4]++
                        }
                        if (examples) println("${if (!was && now) "FIX " else if (was) "BREAK" else "SWAP"} $lead -> $flip (label $label) before '${w.label}'")
                    }
                }
                for ((i, name) in listOf("your own labels", "accepted decodes").withIndex()) {
                    val t = tally[i]
                    println(String.format(java.util.Locale.ROOT, "%-17s pairs=%d lead right=%.1f%% tried=%d fixed=%d broke=%d swapped=%d -> %.1f%%",
                        name, t[0], 100.0 * t[5] / maxOf(t[0], 1), t[1], t[2], t[3], t[4], 100.0 * (t[5] + t[2] - t[3]) / maxOf(t[0], 1)))
                }
                println("extra decodes=$decodes")
            }
            "tappoints" -> {
                // tappoints FILE...: on gold all-tap words (practice prompts and the user's own
                // fixes, never words the decoder chose), which point of each tap lands nearest the
                // key meant: the down point the decoder reads, the lift, the mean of all samples,
                // or the midpoint of down and lift (issue #127). One row per point: taps, share
                // nearest the meant key's centre, mean distance to it (kw).
                if (a.isEmpty()) usage()
                val names = listOf("down", "lift", "centroid", "mid")
                val hit = IntArray(names.size)
                val dist = DoubleArray(names.size)
                var n = 0
                val sink = ReplayReport(ReplayHarness.DEFAULT_DEEP_K)
                for (f in a.map { File(it) }) for ((_, w) in read(f, sink)) {
                    val gold = if (w.target != null) !GoldLabels.isFragment(w) else w.how == "retyped" || w.how == "reswiped" || w.how == "corrected" || w.how == "confirmed"
                    if (!gold) continue
                    val label = w.label?.lowercase() ?: continue
                    val codes = com.kinetica.keyboard.engine.Alphabet.encode(label) ?: continue
                    val tokens = SwipeTrace.replayTokens(w)
                    if (tokens.size != codes.size || tokens.any { it !is com.kinetica.keyboard.engine.models.TapToken }) continue
                    if (w.tokens.size != codes.size || w.tokens.any { it !is SwipeTrace.Gesture }) continue
                    val g = w.geometry.build()
                    val kw = w.geometry.keyWidthPx
                    for (i in codes.indices) {
                        val meant = codes[i]
                        if (!g.hasKey(meant)) continue
                        val ss = (w.tokens[i] as SwipeTrace.Gesture).samples
                        if (ss.isEmpty()) continue
                        val pts = listOf(
                            ss.first().x to ss.first().y,
                            ss.last().x to ss.last().y,
                            ss.map { it.x }.average().toFloat() to ss.map { it.y }.average().toFloat(),
                            (ss.first().x + ss.last().x) / 2f to (ss.first().y + ss.last().y) / 2f,
                        )
                        n++
                        for ((k, pt) in pts.withIndex()) {
                            val x = pt.first / kw
                            val y = pt.second / kw
                            var best = -1
                            var bestD = Float.POSITIVE_INFINITY
                            for (c in 0 until com.kinetica.keyboard.engine.Alphabet.LETTERS) {
                                if (!g.hasKey(c)) continue
                                val d = g.distToCenter(x, y, c)
                                if (d < bestD) { bestD = d; best = c }
                            }
                            if (best == meant) hit[k]++
                            dist[k] += g.distToCenter(x, y, meant).toDouble()
                        }
                    }
                }
                println("taps=$n")
                for (k in names.indices) println(String.format(java.util.Locale.ROOT, "%-9s %6.2f%% %.3f kw", names[k], 100.0 * hit[k] / maxOf(n, 1), dist[k] / maxOf(n, 1)))
            }
            "refused" -> {
                // refused [replay flags] FILE...: every gold line (practice prompt or second attempt)
                // whose label a 50-deep list misses, read again by a decoder that knows only that
                // word. Listed there, the word is readable and lost to the search's budgets or to
                // rivals; not listed, the search lines name the gate that refused it. One TSV row
                // each: file, line, label, buckets, shape, state (lone:source:d:score:50th:size:1st:3rd:10th
                // scores, at the word's real frequency), letter coverage, gates.
                val ctc = opt(a, "--ctc")?.let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
                val beta = opt(a, "--beta")?.toFloat() ?: 0f
                val ctcilOnly = a.remove("--ctcilonly")
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets, ctc = ctc, beta = beta, aim = aimModel, rerankOverlap = ctcilOnly, rerankOverlapOnly = ctcilOnly)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    val gold = if (w.target != null) !GoldLabels.isFragment(w) else w.how == "retyped" || w.how == "reswiped" || w.how == "corrected" || w.how == "confirmed"
                    if (!gold) continue
                    val label = w.label?.lowercase() ?: continue
                    val head = "${f.name}\t$n\t$label\t${r.buckets.joinToString(",")}\t${shape(r)}"
                    if (r.deepRank != 0) { println("$head\tlisted\t${r.rank}\t${r.deepRank}"); continue }
                    if (!h.predictorFor(w).isWord(label)) { println("$head\tnodict"); continue }
                    val fb = h.predictorFor(w).frequencyByte(label).coerceAtLeast(1)
                    val cnt = (Math.exp(fb * Math.log(1.0 + 1e9) / 255.0)).toInt().coerceAtLeast(1)
                    val lone = com.kinetica.keyboard.engine.DictionaryLoader.load("$label\t$cnt\nqqqqqqqqqqqqqqqqqq\t1000000000\n".reader().buffered())
                    val p = com.kinetica.keyboard.engine.WordPredictor(
                        lone.trie, com.kinetica.keyboard.engine.BigramTable.EMPTY, w.geometry.build(), lone.forms, language = w.config.language,
                    ).also { it.aimModel = aimModel }
                    val lines = ArrayList<String>()
                    com.kinetica.keyboard.engine.DecodeTrace.sink = { lines.add(it) }
                    val out = try { p.decode(r.tokens, w.context, apostrophe = w.apostropheMark) } finally { com.kinetica.keyboard.engine.DecodeTrace.sink = null }
                    val hit = out.firstOrNull { it.word.lowercase() == label }
                    val gates = lines.filter { "gates(" in it || it.startsWith("interleave") }.joinToString(" | ") { l ->
                        if (l.startsWith("interleave")) l.take(60) else {
                            val kind = l.substringBefore(" attempts=")
                            val nz = Regex("(\\w+)=([1-9]\\d*)").findAll(l.substringAfter("gates(")).joinToString(",") { it.value }
                            val ab = Regex("abandon\\(([^)]*)\\)").find(l)?.groupValues?.get(1)?.split(',')?.filter { !it.endsWith("=0") }?.joinToString(",") ?: ""
                            "$kind cands=${Regex("cands=(\\d+)").find(l)?.groupValues?.get(1)} ab[$ab] g[$nz]"
                        }
                    }
                    // How far each label letter's key centre is from the nearest touched point, kw.
                    val g = w.geometry.build()
                    val pts = ArrayList<FloatArray>()
                    val starts = ArrayList<FloatArray>()
                    val ends = ArrayList<FloatArray>()
                    for (t in r.tokens) when (t) {
                        is com.kinetica.keyboard.engine.models.TapToken -> { val q = floatArrayOf(t.x, t.y); pts.add(q); starts.add(q); ends.add(q) }
                        is com.kinetica.keyboard.engine.models.SwipeToken -> {
                            for (q in t.rawPath) pts.add(floatArrayOf(q.x, q.y))
                            t.rawPath.firstOrNull()?.let { starts.add(floatArrayOf(it.x, it.y)) }
                            t.rawPath.lastOrNull()?.let { ends.add(floatArrayOf(it.x, it.y)) }
                        }
                    }
                    val codes = g.alphabet.encode(com.kinetica.keyboard.engine.AccentFolder.fold(label).replace("'", ""))?.filter { g.hasKey(it) } ?: emptyList()
                    fun near(code: Int, from: List<FloatArray>) = from.minOfOrNull { kotlin.math.hypot(it[0] - g.centerX(code), it[1] - g.centerY(code)) } ?: 9f
                    val miss = codes.map { near(it, pts) }
                    val cover = "max=${"%.2f".format(miss.maxOrNull() ?: 9f)} far=${miss.count { it > 1f }} first=${"%.2f".format(codes.firstOrNull()?.let { near(it, starts) } ?: 9f)} last=${"%.2f".format(codes.lastOrNull()?.let { near(it, ends) } ?: 9f)} n=${codes.size} tok=${r.tokens.size}"
                    val deepMin = r.deep.lastOrNull()?.score ?: 0f
                    val state = if (hit != null) "lone:${hit.source}:${"%.2f".format(hit.dtwDistance)}:${hit.score}:${deepMin}:${r.deep.size}:${r.deep.getOrNull(0)?.score}:${r.deep.getOrNull(2)?.score}:${r.deep.getOrNull(9)?.score}:$fb:${p.frequencyByte(label)}" else "refused"
                    println("$head\tunlisted\t$state\t$cover\t$gates")
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
            "latticedata" -> {
                // latticedata FILE...: one tab-separated row per place a word may end
                // (LatticeDecoder.boundaries), for fitting the lattice's break score. kind split:
                // a word and the word typed after it with the space left out, at the true break.
                // kind join: a real one-word buffer, at each lift inside it.
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                val L = com.kinetica.keyboard.engine.LatticeDecoder
                fun row(p: WordPredictor, file: String, n: Int, kind: String, label: String, label2: String,
                        sorted: List<com.kinetica.keyboard.engine.models.InputToken>, cut: Int, context: List<String>): String {
                    val one = { ch: Char -> ch in ReplayHarness.ONE_LETTER_WORDS[p.language].orEmpty() }
                    val joined = p.decode(sorted, context).firstOrNull()
                    val headTokens = sorted.subList(0, cut)
                    val restTokens = sorted.subList(cut, sorted.size)
                    val head = L.arcWord(p, headTokens, p.decode(headTokens, context), one)
                    val rest = head?.let { L.arcWord(p, restTokens, p.decode(restTokens, (context + it.word).takeLast(2)), one) }
                    return listOf(
                        file, n, kind, label, label2, sorted.size, cut, L.gapBefore(sorted, cut),
                        sorted[cut - 1] is com.kinetica.keyboard.engine.models.TapToken, sorted[cut] is com.kinetica.keyboard.engine.models.TapToken,
                        head?.word ?: "-", head?.score ?: 0f, rest?.word ?: "-", rest?.score ?: 0f,
                        joined?.word ?: "-", joined?.score ?: 0f,
                    ).joinToString("\t")
                }
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        val label = w.label?.lowercase() ?: continue
                        val tokens = try { SwipeTrace.replayTokens(w).sortedBy { it.tStart } } catch (e: RuntimeException) { continue }
                        if (tokens.isEmpty() || tokens.any { it is com.kinetica.keyboard.engine.models.TapToken && it.apostrophe }) continue
                        val p = h.predictorFor(w)
                        for (cut in L.boundaries(tokens)) println(row(p, f.name, n, "join", label, "", tokens, cut, w.context))
                        val next = words.getOrNull(i + 1)?.second ?: continue
                        val nextLabel = next.label?.lowercase() ?: continue
                        if (next.context.lastOrNull()?.lowercase() != w.committed?.lowercase()) continue
                        val nextTokens = try { SwipeTrace.replayTokens(next).sortedBy { it.tStart } } catch (e: RuntimeException) { continue }
                        if (nextTokens.isEmpty() || tokens.maxOf { it.tEnd } > nextTokens.first().tStart) continue
                        println(row(p, f.name, n, "split", label, nextLabel, tokens + nextTokens, tokens.size, w.context))
                    }
                }
            }
            "duodata" -> {
                // duodata FILE...: every labelled buffer as strokes in time (DuoFeatures.strokes),
                // one JSON object per line, for tools/experiment/duo_ctc.py. Trace-derived: the
                // output stays out of the repository. With --ctc DUO_MODEL each row also carries
                // the encoder's greedy reading here, to check it against duo_ctc.py's.
                val duo = opt(a, "--ctc")?.let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) {
                    for ((n, w) in read(f, sink)) {
                        val label = w.label?.lowercase() ?: continue
                        if (label.isEmpty() || label.length > 20 || !label.all { it in 'a'..'z' || it == '\'' }) continue
                        val tokens = try { SwipeTrace.replayTokens(w).sortedBy { it.tStart } } catch (e: RuntimeException) { continue }
                        if (tokens.isEmpty()) continue
                        val p = h.predictorFor(w)
                        val g = p.geometry ?: continue
                        if (p.language != "en") continue
                        val keys = (0 until com.kinetica.keyboard.engine.Alphabet.LETTERS).joinToString(",") { c ->
                            if (g.hasKey(c)) "[%.3f,%.3f]".format(java.util.Locale.ROOT, g.centerX(c), g.centerY(c)) else "null"
                        }
                        val strokes = com.kinetica.keyboard.engine.DuoFeatures.strokes(tokens).joinToString(",") { s ->
                            val t0 = tokens.first().tStart
                            "{\"s\":${s.thumb},\"p\":[" + s.t.indices.joinToString(",") { i ->
                                "[%.3f,%.3f,%d]".format(java.util.Locale.ROOT, s.x[i], s.y[i], s.t[i] - t0)
                            } + "]}"
                        }
                        val lead = if (duo != null) {
                            val lp = duo.logProbsDuo(com.kinetica.keyboard.engine.DuoFeatures.strokes(tokens), g)
                            val sb = StringBuilder(); var prev = 0
                            for (t in 0 until duo.frames) {
                                var best = 0
                                for (c in 1 until CtcScorer.CLASSES) if (lp[t * CtcScorer.CLASSES + c] > lp[t * CtcScorer.CLASSES + best]) best = c
                                if (best != prev && best != 0) sb.append(if (best == 27) '\'' else 'a' + (best - 1))
                                prev = best
                            }
                            sb.toString()
                        } else p.decode(tokens, w.context).firstOrNull()?.word?.lowercase()
                        println("{\"file\":${js(f.name)},\"n\":$n,\"label\":${js(label)},\"practice\":${w.target != null},\"lead\":${js(lead ?: "")},\"taps\":${tokens.count { it is com.kinetica.keyboard.engine.models.TapToken }},\"keys\":[$keys],\"strokes\":[$strokes]}")
                    }
                }
            }
            "ctcpropose" -> {
                // ctcpropose --ctc MODEL FILE...: what CtcProposer's lexicon beam over the encoder's
                // frames would add to the decoder's list (#134). With a one-stroke encoder,
                // all-swipe buffers only, strokes' frames set end to end in start order; with a
                // two-thumb encoder (duo_ctc.py), every buffer. --propDump OUT writes a line per buffer.
                val ctc = (opt(a, "--ctc") ?: usage()).let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
                opt(a, "--propWidth")?.let { com.kinetica.keyboard.engine.CtcProposer.WIDTH = it.toInt() }
                opt(a, "--propLm")?.let { com.kinetica.keyboard.engine.CtcProposer.LM_WEIGHT = it.toFloat() }
                val top = opt(a, "--propTop")?.toInt() ?: 20
                val dump = opt(a, "--propDump")?.let { File(it).printWriter() }
                // --propCands OUT: per buffer, the decoder's candidates with ln score and the
                // encoder's CTC cost of each word's letters, for fitting a combined score offline.
                val cands = opt(a, "--propCands")?.let { File(it).printWriter() }
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                class Tally { var n = 0; var lead = 0; var inList = 0; var p1 = 0; var p5 = 0; var pTop = 0; var added = 0; var addedTrue = 0; var missTrue = 0 }
                val tallies = LinkedHashMap<String, Tally>()
                var nanos = 0L; var runs = 0
                val examples = ArrayList<String>()
                for (f in a.map { File(it) }) {
                    for ((n, w) in read(f, sink)) {
                        val label = w.label?.lowercase() ?: continue
                        val tokens = try { SwipeTrace.replayTokens(w).sortedBy { it.tStart } } catch (e: RuntimeException) { continue }
                        val swipes = tokens.filterIsInstance<com.kinetica.keyboard.engine.models.SwipeToken>()
                        if (tokens.isEmpty() || (!ctc.duo && swipes.size != tokens.size)) continue
                        if (tokens.any { it is com.kinetica.keyboard.engine.models.TapToken && it.apostrophe }) continue
                        val p = h.predictorFor(w)
                        val g = p.geometry ?: continue
                        var lift = Long.MIN_VALUE; var overlap = false
                        for (t in tokens) { if (t.tStart < lift) overlap = true; lift = maxOf(lift, t.tEnd) }
                        val bucket = when {
                            swipes.isEmpty() -> "taps only"
                            swipes.size != tokens.size -> "taps and swipes"
                            tokens.size == 1 -> "one swipe"
                            overlap -> "overlapped swipes"
                            else -> "swipes in turn"
                        }
                        val decoded = p.decode(tokens, w.context)
                        val list = decoded.map { it.word.lowercase() }
                        val t0 = System.nanoTime()
                        val lp = if (ctc.duo) ctc.logProbsDuo(com.kinetica.keyboard.engine.DuoFeatures.strokes(tokens), g)
                        else com.kinetica.keyboard.engine.CtcProposer.frames(swipes.map { ctc.logProbs(it.resampled, g) })
                        val props = com.kinetica.keyboard.engine.CtcProposer.propose(p.trie, lp, lp.size / CtcScorer.CLASSES, top)
                            .map { p.trie.wordOf(it.node).lowercase() }
                        nanos += System.nanoTime() - t0; runs++
                        val rank = props.indexOf(label)
                        val practice = w.target != null
                        if (cands != null) {
                            val frames = lp.size / CtcScorer.CLASSES
                            cands.println(listOf(f.name, n, bucket, label, practice).joinToString("\t") + "\t" + decoded.joinToString("\t") { c ->
                                val codes = com.kinetica.keyboard.engine.AccentFolder.fold(c.word.lowercase()).mapNotNull { ch ->
                                    if (ch == '\'') null else p.alphabet.codeOf(ch).takeIf { it in 0 until com.kinetica.keyboard.engine.Alphabet.LETTERS }
                                }
                                val nll = if (codes.isEmpty()) Float.NaN else CtcScorer.nll(lp, frames, IntArray(codes.size) { CtcScorer.classOf(codes[it]) })
                                "${c.word.lowercase()}|${kotlin.math.ln(c.score)}|$nll|${codes.size}"
                            })
                        }
                        dump?.println(listOf(f.name, n, bucket, label, practice, list.indexOf(label), rank, list.firstOrNull() ?: "-", props.firstOrNull() ?: "-").joinToString("\t"))
                        for (key in listOf(bucket, "all")) {
                            val t = tallies.getOrPut(key) { Tally() }
                            t.n++
                            if (list.firstOrNull() == label) t.lead++
                            if (label in list) t.inList++
                            if (rank == 0) t.p1++
                            if (rank in 0..4) t.p5++
                            if (rank >= 0) t.pTop++
                            if (label !in list) {
                                if (practice) t.missTrue++
                                if (rank >= 0) { t.added++; if (practice) t.addedTrue++ }
                            }
                        }
                        if (label !in list && rank >= 0 && examples.size < 40) examples.add("${f.name}\t$n\t$bucket\t$label\trank ${rank + 1}\tlead ${list.firstOrNull()}")
                    }
                }
                fun nf(x: Int, of: Int) = "$x/$of (${if (of == 0) "-" else "%.1f%%".format(java.util.Locale.ROOT, 100.0 * x / of)})"
                for ((k, t) in tallies) {
                    println("$k: n=${t.n} decoder lead=${nf(t.lead, t.n)} in list=${nf(t.inList, t.n)} | proposer top1=${nf(t.p1, t.n)} top5=${nf(t.p5, t.n)} top$top=${nf(t.pTop, t.n)} | not in list=${t.n - t.inList}, proposer has it=${t.added} (practice: ${t.addedTrue} of ${t.missTrue})")
                }
                dump?.close(); cands?.close()
                println("proposer ms per buffer=%.2f".format(java.util.Locale.ROOT, nanos / 1e6 / maxOf(runs, 1)))
                examples.forEach { println(it) }
            }
            "lattice" -> {
                // lattice FILE...: LatticeDecoder end to end. Every real one-word buffer (its lead
                // must stay the label where it is today), every pair of consecutive words with the
                // space left out, and every three.
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                val L = com.kinetica.keyboard.engine.LatticeDecoder
                var singles = 0; var withCut = 0; var oneRight = 0; var latRight = 0; var broke = 0; var fixed = 0
                var pairs = 0; var pairRight = 0; var pairJoined = 0; var pairPeck = 0
                var triples = 0; var tripleRight = 0
                var decodes = 0L; var nanos = 0L
                val examples = ArrayList<String>()
                fun nf(x: Int, of: Int) = "$x/$of (${if (of == 0) "-" else "%.1f%%".format(100.0 * x / of)})"
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    val toks = words.map { (_, w) -> try { SwipeTrace.replayTokens(w).sortedBy { it.tStart } } catch (e: RuntimeException) { null } }
                    fun follows(i: Int): Boolean {
                        val w = words[i - 1].second; val next = words[i].second
                        val t0 = toks[i - 1]; val t1 = toks[i]
                        return w.label != null && next.label != null && t0 != null && t1 != null && t0.isNotEmpty() && t1.isNotEmpty() &&
                            next.context.lastOrNull()?.lowercase() == w.committed?.lowercase() && t0.maxOf { it.tEnd } <= t1.first().tStart
                    }
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        val label = w.label?.lowercase() ?: continue
                        val tokens = toks[i] ?: continue
                        if (tokens.isEmpty()) continue
                        val p = h.predictorFor(w)
                        val one = { ch: Char -> ch in ReplayHarness.ONE_LETTER_WORDS[p.language].orEmpty() }
                        val joined = p.decode(tokens, w.context)
                        val t0 = System.nanoTime()
                        val r = L.decode(p, tokens, w.context, joined, one)
                        nanos += System.nanoTime() - t0; decodes++
                        singles++
                        val was = joined.firstOrNull()?.word?.lowercase() == label
                        val now = if (r == null) was else (!r.split && was)
                        if (r != null) withCut++
                        if (was) oneRight++
                        if (now) latRight++
                        if (was && !now) { broke++; if (examples.size < 40) examples.add("broke\t${f.name}\t$n\t$label\t${r?.best?.words}") }
                        if (i + 1 < words.size && follows(i + 1)) {
                            val l2 = words[i + 1].second.label!!.lowercase()
                            val all = tokens + toks[i + 1]!!
                            val j2 = p.decode(all, w.context)
                            val r2 = L.decode(p, all, w.context, j2, one)
                            pairs++
                            val got = r2?.best?.words?.map { it.lowercase() } ?: listOfNotNull(j2.firstOrNull()?.word?.lowercase())
                            if (got == listOf(label, l2)) pairRight++ else if (examples.size < 40 && pairs % 50 == 0) examples.add("pair\t${f.name}\t$n\t$label $l2\t$got")
                            if (got.size == 1) pairJoined++
                            val peck = com.kinetica.keyboard.engine.PeckSplit.split(p, all, w.context, j2, one)
                            if (peck != null && peck.tapWord?.lowercase() == label && peck.restWord?.lowercase() == l2) pairPeck++
                            if (i + 2 < words.size && follows(i + 2)) {
                                val l3 = words[i + 2].second.label!!.lowercase()
                                val all3 = all + toks[i + 2]!!
                                val r3 = L.decode(p, all3, w.context, null, one)
                                triples++
                                if (r3?.best?.words?.map { it.lowercase() } == listOf(label, l2, l3)) tripleRight++
                            }
                        }
                    }
                }
                println("one-word buffers=$singles with a lift inside=$withCut lead right today=${nf(oneRight, singles)} with the lattice=${nf(latRight, singles)} broke=$broke")
                println("two words, no space: both right=${nf(pairRight, pairs)} left as one word=${nf(pairJoined, pairs)} today (peck split only)=${nf(pairPeck, pairs)}")
                println("three words, no space: all right=${nf(tripleRight, triples)}")
                println("lattice mean ${"%.1f".format(nanos / 1e6 / maxOf(decodes, 1))} ms on one-word buffers")
                examples.forEach { println(it) }
            }
            "swipesplit" -> {
                // swipesplit FILE...: what a backspace after a swipe-joined word gives back
                // (PeckSplit.readSwipe). split: a swiped word and the swiped word after it, the
                // space left out, scored on getting both back. join: real buffers with a pause
                // between swipes, where the split would be wrong if the joined word was meant.
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                var pairs = 0; var joinedWrong = 0; var both = 0; var bothWhenWrong = 0; var none = 0
                var real = 0; var realOne = 0
                fun nf(x: Int, of: Int) = "$x/$of (${if (of == 0) "-" else "%.1f%%".format(100.0 * x / of)})"
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        val label = w.label?.lowercase() ?: continue
                        val tokens = try { SwipeTrace.replayTokens(w) } catch (e: RuntimeException) { continue }
                        val p = h.predictorFor(w)
                        if (com.kinetica.keyboard.engine.PeckSplit.swipeHead(tokens.sortedBy { it.tStart }) > 0) {
                            val r = com.kinetica.keyboard.engine.PeckSplit.readSwipe(p, tokens, w.context, { ch -> ch in ReplayHarness.ONE_LETTER_WORDS[p.language].orEmpty() })
                            real++
                            if (p.decode(tokens, w.context).firstOrNull()?.word?.lowercase() == label) realOne++
                            println("join\t${f.name}\t$n\t$label\t${r?.tapWord}\t${r?.restWord}")
                        }
                        if (tokens.none { it !is com.kinetica.keyboard.engine.models.TapToken }) continue
                        val next = words.getOrNull(i + 1)?.second ?: continue
                        val nextLabel = next.label?.lowercase() ?: continue
                        if (next.context.lastOrNull()?.lowercase() != w.committed?.lowercase()) continue
                        val nextTokens = try { SwipeTrace.replayTokens(next) } catch (e: RuntimeException) { continue }
                        if (nextTokens.minByOrNull { it.tStart } !is com.kinetica.keyboard.engine.models.SwipeToken) continue
                        if (tokens.maxOf { it.tEnd } > nextTokens.minOf { it.tStart }) continue
                        val all = tokens + nextTokens
                        val joined = p.decode(all, w.context).firstOrNull()?.word?.lowercase()
                        val r = com.kinetica.keyboard.engine.PeckSplit.readSwipe(p, all, w.context, { ch -> ch in ReplayHarness.ONE_LETTER_WORDS[p.language].orEmpty() })
                        pairs++
                        val wrong = joined != label && joined != nextLabel
                        if (wrong) joinedWrong++
                        if (r == null) none++
                        val ok = r?.tapWord?.lowercase() == label && r.restWord?.lowercase() == nextLabel
                        if (ok) both++
                        if (ok && wrong) bothWhenWrong++
                        println("split\t${f.name}\t$n\t$label $nextLabel\t$joined\t${r?.tapWord} ${r?.restWord}\t$ok")
                    }
                }
                println("pairs=$pairs joined-wrong=${nf(joinedWrong, pairs)} no-reading=$none both-back=${nf(both, pairs)} both-back-when-joined-wrong=${nf(bothWhenWrong, joinedWrong)}")
                println("real buffers with a pause between swipes=$real, joined word right=${nf(realOne, real)}")
            }
            "spacedip" -> {
                // spacedip [--dipGap KW] [--dipDepth KW] FILE...: the spacebar dip (SpaceDip, #141).
                // No trace holds one, so two checks stand in. real: every recorded stroke, run
                // with the split on, must come back whole. synth: a swiped word and the swiped
                // word after it, joined by straight legs down to the spacebar and back, scored
                // on getting both words back against decoding the two strokes apart.
                val gap = opt(a, "--dipGap")?.toFloat() ?: 0f
                val depth = opt(a, "--dipDepth")?.toFloat() ?: 1.0f
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                fun nf(x: Int, of: Int) = "$x/$of (${if (of == 0) "-" else "%.1f%%".format(100.0 * x / of)})"
                fun spaceOf(geo: SwipeTrace.Geometry): FloatArray {
                    val letters = geo.keys.filterKeys { it != com.kinetica.keyboard.engine.Alphabet.APOSTROPHE }.values
                    val bottom = letters.maxOf { it[3] }
                    return floatArrayOf(letters.minOf { it[0] } + 2.5f, bottom + gap, letters.maxOf { it[2] } - 2.5f, bottom + gap + 1.4f)
                }
                fun run(geo: SwipeTrace.Geometry, samples: List<SwipeTrace.Sample>): List<com.kinetica.keyboard.engine.models.InputToken>? {
                    val out = ArrayList<com.kinetica.keyboard.engine.models.InputToken>()
                    val e = com.kinetica.keyboard.engine.GestureEngine(object : com.kinetica.keyboard.engine.GestureEngine.Listener {
                        override fun onTokenFinalized(token: com.kinetica.keyboard.engine.models.InputToken) { out.add(token) }
                        override fun onKeyTransition(streamId: com.kinetica.keyboard.engine.models.StreamId, code: Int) = Unit
                        override fun onAllPointersUp() = Unit
                    })
                    e.spaceDip = true
                    e.setGeometry(geo.build().withSpace(spaceOf(geo)), geo.tapMaxDispPx)
                    if (!e.onPointerDown(0, samples[0].x, samples[0].y, samples[0].t)) return null
                    for (i in 1 until samples.size - 1) e.onPointerMove(0, samples[i].x, samples[i].y, samples[i].t)
                    val up = samples.last()
                    e.onPointerUp(0, up.x, up.y, up.t)
                    return out
                }
                fun leg(from: SwipeTrace.Sample, toX: Float, toY: Float, stepPx: Float): List<SwipeTrace.Sample> {
                    val dx = toX - from.x
                    val dy = toY - from.y
                    val n = maxOf(1, (Math.hypot(dx.toDouble(), dy.toDouble()) / stepPx).toInt())
                    return (1..n).map { SwipeTrace.Sample(from.x + dx * it / n, from.y + dy * it / n, from.t + 8L * it) }
                }
                var strokes = 0; var falseSplits = 0
                var pairs = 0; var found = 0; var baseBoth = 0; var dipBoth = 0; var dip1 = 0; var dip2 = 0; var base1 = 0; var base2 = 0
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        for (t in w.tokens) {
                            if (t !is SwipeTrace.Gesture || t.samples.size < 2) continue
                            val out = run(w.geometry, t.samples) ?: continue
                            strokes++
                            if (out.size > 1) {
                                falseSplits++
                                println("real\t${f.name}\t$n\t${w.label}\tpieces=${out.size}")
                            }
                        }
                        val label = w.label?.lowercase() ?: continue
                        val g1 = w.tokens.singleOrNull() as? SwipeTrace.Gesture ?: continue
                        val next = words.getOrNull(i + 1)?.second ?: continue
                        val nextLabel = next.label?.lowercase() ?: continue
                        val g2 = next.tokens.singleOrNull() as? SwipeTrace.Gesture ?: continue
                        if (next.context.lastOrNull()?.lowercase() != w.committed?.lowercase()) continue
                        if (!w.geometry.sameAs(next.geometry)) continue
                        val t1 = try { SwipeTrace.replayTokens(w) } catch (e: RuntimeException) { continue }
                        val t2 = try { SwipeTrace.replayTokens(next) } catch (e: RuntimeException) { continue }
                        if (t1.single() !is com.kinetica.keyboard.engine.models.SwipeToken || t2.single() !is com.kinetica.keyboard.engine.models.SwipeToken) continue
                        val kw = w.geometry.keyWidthPx
                        val space = spaceOf(w.geometry)
                        val end = g1.samples.last()
                        val start = g2.samples.first()
                        val dipX = ((end.x + start.x) / 2f).coerceIn((space[0] + 0.3f) * kw, (space[2] - 0.3f) * kw)
                        val dipY = (space[1] + depth) * kw
                        val joined = ArrayList(g1.samples)
                        joined.addAll(leg(joined.last(), dipX, dipY, 0.15f * kw))
                        joined.addAll(leg(joined.last(), start.x, start.y, 0.15f * kw))
                        val shift = joined.last().t + 8L - start.t
                        for (k in 1 until g2.samples.size) joined.add(g2.samples[k].let { SwipeTrace.Sample(it.x, it.y, it.t + shift) })
                        val out = run(w.geometry, joined) ?: continue
                        val p = h.predictorFor(w)
                        pairs++
                        val b1 = p.decode(t1, w.context).firstOrNull()?.word?.lowercase() == label
                        val b2 = p.decode(t2, next.context).firstOrNull()?.word?.lowercase() == nextLabel
                        if (b1) base1++
                        if (b2) base2++
                        if (b1 && b2) baseBoth++
                        var d1: String? = null
                        var d2: String? = null
                        if (out.size == 2) {
                            found++
                            d1 = p.decode(listOf(out[0]), w.context).firstOrNull()?.word?.lowercase()
                            d2 = p.decode(listOf(out[1]), next.context).firstOrNull()?.word?.lowercase()
                        }
                        if (d1 == label) dip1++
                        if (d2 == nextLabel) dip2++
                        if (d1 == label && d2 == nextLabel) dipBoth++
                        println("synth\t${f.name}\t$n\t$label $nextLabel\tpieces=${out.size}\t$d1 $d2\t${if (b1) "+" else "-"}${if (b2) "+" else "-"}")
                    }
                }
                println("real strokes=$strokes split=${nf(falseSplits, strokes)}")
                println("synth pairs=$pairs split-in-two=${nf(found, pairs)} apart: first=${nf(base1, pairs)} second=${nf(base2, pairs)} both=${nf(baseBoth, pairs)}")
                println("synth dipped: first=${nf(dip1, pairs)} second=${nf(dip2, pairs)} both=${nf(dipBoth, pairs)}")
            }
            "spacesplit" -> {
                // spacesplit FILE...: every tapped word of four letters or more that is no word but
                // cuts into two, with today's split (pairs only), the pause-aware split, the pause
                // before each letter and the next words committed, for judging by eye (#64).
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        val tokens = try { SwipeTrace.replayTokens(w) } catch (e: RuntimeException) { continue }
                        if (tokens.size < 4 || tokens.any { it !is com.kinetica.keyboard.engine.models.TapToken }) continue
                        val p = h.predictorFor(w)
                        val taps = tokens.sortedBy { it.tStart }
                        val literal = taps.joinToString("") { p.alphabet.charOf((it as com.kinetica.keyboard.engine.models.TapToken).code).toString() }
                        if (p.isWordIgnoringCase(literal)) continue
                        val cuts = (2..literal.length - 2).filter { p.isWordIgnoringCase(literal.take(it)) && p.isWordIgnoringCase(literal.drop(it)) }
                        if (cuts.isEmpty()) continue
                        val pauses = LongArray(taps.size) { k -> if (k == 0) 0L else taps[k].tStart - taps[k - 1].tEnd }
                        val old = p.spaceSplit(literal)
                        val new = p.spaceSplit(literal, pausesMs = pauses)
                        val next = words.drop(i + 1).take(4).joinToString(" ") { it.second.committed ?: "?" }
                        val cutText = cuts.joinToString(",") { c ->
                            val hd = literal.take(c); val tl = literal.drop(c)
                            "$hd|$tl:pair=${p.pairByte(hd, tl)}:f=${p.frequencyByte(hd)}/${p.frequencyByte(tl)}:paused=${p.pausedAt(pauses, c)}"
                        }
                        println(listOf(f.name, n, literal, w.committed ?: "-", w.how ?: "-",
                            old?.let { "${it.first} ${it.second}" } ?: "-",
                            new?.let { "${it.first} ${it.second}" } ?: "-",
                            pauses.drop(1).joinToString(","), cutText, next).joinToString("\t"))
                    }
                }
            }
            "grammar" -> {
                // grammar FILE...: the grammar pass (GrammarCheck) over each committed English word
                // and the two before it, as the keyboard runs it when the word lands: every rewrite
                // it would make, with the word pairs it read (#55).
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                var checked = 0
                var fixed = 0
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    if (w.config.language != "en") continue
                    val word = w.committed?.lowercase() ?: continue
                    val ctx = w.context.map { it.lowercase() }
                    val prev = ctx.lastOrNull() ?: continue
                    val before = ctx.getOrNull(ctx.size - 2)
                    val p = h.predictorFor(w)
                    checked++
                    val fix = com.kinetica.keyboard.engine.GrammarCheck.fixPrevious(before, prev, word) { x, y -> p.pairByte(x, y) } ?: continue
                    fixed++
                    println(listOf(f.name, n, "${before ?: "^"} $prev $word", "$prev->$fix",
                        "pairs prev+next=${p.pairByte(prev, word)} fix+next=${p.pairByte(fix, word)} " +
                            "before+prev=${before?.let { p.pairByte(it, prev) } ?: "-"} before+fix=${before?.let { p.pairByte(it, fix) } ?: "-"}",
                    ).joinToString("\t"))
                }
                println("checked $checked rewrites $fixed")
            }
            "autofix" -> {
                val lexicon = a.remove("--lexicon")
                if (a.isEmpty()) usage()
                retypes = false
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                // The user's commits so far, as the keyboard's personal counts would hold them.
                val counts = HashMap<String, Int>()
                fun lexOf(p: WordPredictor) = object : com.kinetica.keyboard.engine.GrammarAutoFix.Lexicon {
                    override fun isWord(word: String) = p.isWordIgnoringCase(word)
                    override fun frequencyByte(word: String) = p.frequencyByte(word)
                    override fun pairByte(prev: String, next: String) = p.pairByte(prev, next)
                    override fun learned(word: String) = (counts[word] ?: 0) >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT
                }
                val tally = HashMap<String, Int>()
                var checked = 0
                var english: WordPredictor? = null
                for (f in a.map { File(it) }) {
                    val words = read(f, sink)
                    for ((i, pair) in words.withIndex()) {
                        val (n, w) = pair
                        if (w.config.language != "en") continue
                        val word = w.committed ?: continue
                        val ctx = w.context
                        val prev = ctx.lastOrNull()
                        val before = ctx.getOrNull(ctx.size - 2)
                        val lex = lexOf(h.predictorFor(w).also { english = it })
                        checked++
                        val fix = when {
                            prev != null && com.kinetica.keyboard.engine.GrammarAutoFix.doubled(prev, word) -> "doubled" to "$prev $word -> $prev"
                            prev != null -> com.kinetica.keyboard.engine.GrammarAutoFix.contraction(prev, word)?.let { "contraction" to "$prev $word -> $it" }
                                ?: com.kinetica.keyboard.engine.GrammarAutoFix.afterI(before, prev, word)?.let { "afterI" to "${before ?: "^"} $prev $word -> $it" }
                            else -> null
                        } ?: com.kinetica.keyboard.engine.GrammarAutoFix.joinedTo(word, lex)?.let { "joined" to "$word -> ${it.first} ${it.second}" }
                        counts.merge(word.lowercase(), 1, Int::plus)
                        if (fix == null) continue
                        tally.merge(fix.first, 1, Int::plus)
                        val next = words.drop(i + 1).take(3).joinToString(" ") { it.second.committed ?: "?" }
                        println(listOf(f.name.take(11), n, if (w.target != null) "practice" else "free", fix.first, fix.second, w.how ?: "-", "next: $next").joinToString("\t"))
                    }
                }
                println("checked $checked fixes $tally")
                val p = english
                if (lexicon && p != null) {
                    counts.clear()
                    val lex = lexOf(p)
                    val list = File(assets, "dictionaries/en_wordlist.txt").readLines().map { it.substringBefore('\t') }
                    for (word in list) {
                        val cut = com.kinetica.keyboard.engine.GrammarAutoFix.joinedTo(word, lex) ?: continue
                        println("lexicon\t$word\tf=${p.frequencyByte(word)}\t${cut.first} ${cut.second}\tpair=${p.pairByte(cut.first, cut.second)}")
                    }
                }
            }
            "slips" -> {
                // slips [--common BYTE] [--commongap N] FILE...: every tapped English word the short-slip
                // rule would change, with what the user ended up with (the retyped word where
                // there is one). Counts are learned in trace order, pairs too.
                opt(a, "--common")?.let { KineticaConstants.SLIP_COMMON_PAIR_BYTE = it.toInt() }
                opt(a, "--commonmax")?.let { KineticaConstants.SLIP_COMMON_MAX_TYPED_BYTE = it.toInt() }
                opt(a, "--commongap")?.let { KineticaConstants.SLIP_COMMON_FREQ_GAP = it.toInt() }
                opt(a, "--pair")?.let { KineticaConstants.SLIP_MIN_PAIR_BYTE = it.toInt() }
                opt(a, "--pairgap")?.let { KineticaConstants.SLIP_MIN_PAIR_GAP = it.toInt() }
                if (a.isEmpty()) usage()
                val learner = ReplayHarness.Learner(phrases = true)
                val h = ReplayHarness(assets, learner = learner)
                val sink = ReplayReport(h.deepK)
                val tally = java.util.TreeMap<String, Int>()
                var checked = 0
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    if (w.config.language != "en" || w.target != null) continue
                    val label = w.label?.lowercase() ?: continue
                    val tokens = try { SwipeTrace.replayTokens(w) } catch (e: RuntimeException) { continue }
                    if (tokens.isEmpty() || tokens.any { it !is com.kinetica.keyboard.engine.models.TapToken }) {
                        learner.learn("en", w.context, label); continue
                    }
                    val typed = tokens.sortedBy { it.tStart }.joinToString("") {
                        com.kinetica.keyboard.engine.Alphabet.charOf((it as com.kinetica.keyboard.engine.models.TapToken).code).toString()
                    }
                    val p = h.predictorFor(w)
                    checked++
                    val prev = w.context.lastOrNull()
                    val fix = com.kinetica.keyboard.engine.ShortSlips.correction(p, w.geometry.build(), typed, prev, setOf('a', 'i'))
                    if (fix != null) {
                        val kind = when {
                            label == fix -> "right"
                            label == typed -> "kept-as-typed"
                            else -> "other"
                        }
                        val tier = if (typed.length > 1 && p.frequencyByte(typed) > KineticaConstants.SLIP_MAX_TYPED_BYTE) "common" else "rare"
                        tally.merge("$tier $kind", 1, Int::plus)
                        println(listOf(f.name.take(11), n, tier, kind, "${prev ?: "^"} $typed -> $fix", "label=$label", w.how ?: "-").joinToString("\t"))
                    }
                    learner.learn("en", w.context, label)
                }
                println("checked $checked $tally")
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

    private var dump: java.io.PrintWriter? = null

    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** One `--dump` row: see the replay command. */
    private fun dumpLine(f: File, n: Int, r: ReplayHarness.Result): String {
        val w = r.word
        val src = when {
            w.target != null -> if (GoldLabels.isFragment(w)) "fragment" else "practice"
            w.how == null -> "typed"
            else -> w.how ?: "typed"
        }
        val sb = StringBuilder()
        sb.append("{\"f\":").append(js(f.name)).append(",\"n\":").append(n)
            .append(",\"label\":").append(js(w.label?.lowercase() ?: ""))
            .append(",\"src\":").append(js(src)).append(",\"asis\":").append(r.typedAsIs)
            .append(",\"rank\":").append(r.rank).append(",\"deep\":").append(r.deepRank)
            .append(",\"b\":").append(js(r.buckets.joinToString(",")))
            .append(",\"shape\":").append(js(shape(r)))
            .append(",\"ctx\":[").append(w.context.joinToString(",") { js(it) }).append("]")
            .append(",\"c\":[")
        r.shipping.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("[").append(js(c.word)).append(',').append(c.score).append(',').append(c.dtwDistance)
                .append(',').append(js(c.source.name)).append(',').append(js(c.language)).append(",[")
                .append(ScoreTerms.Term.entries.joinToString(",") { c.terms[it].toString() }).append("]]")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun run(harness: ReplayHarness, files: List<File>): ReplayReport {
        val report = ReplayReport(harness.deepK)
        for (f in files) {
            harness.newSession()
            for ((lineNo, w) in read(f, report)) {
                try {
                    val r = harness.replay(w)
                    report.add(r)
                    dump?.println(dumpLine(f, lineNo, r))
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
                        is SwipeTrace.Line.Confirm -> if (!skipped) confirm(words, l, report)
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
        if (retypes) report.reswiped += GoldLabels.relabelReswipes(words)
        return words
    }

    enum class Retype { NONE, CLOSE, EXTENDS }

    /**
     * Applies the user's answer to the developer build's retype prompt ([GoldPrompt]): the
     * lines just before the retyped commit that were relabelled to it (`how = "retyped"`) become
     * confirmed labels on a yes, and lose their label on a no (a change of mind, not a fix).
     */
    internal fun confirm(words: MutableList<Pair<Int, SwipeTrace.Word>>, l: SwipeTrace.Line.Confirm, report: ReplayReport) {
        // The retyped commit itself is the newest line spelled `to`; the relabelled tries sit
        // before it, with dropped buffers (no commit) between them.
        var k = words.indexOfLast { it.second.committed.equals(l.to, ignoreCase = true) && it.second.how != "retyped" }
        if (k < 0) return
        var hit = 0
        while (--k >= 0) {
            val (n, w) = words[k]
            if (w.committed == null) continue
            if (w.how != "retyped" || !w.committed.equals(l.to, ignoreCase = true)) break
            words[k] = n to if (l.yes) w.copy(how = "confirmed") else w.copy(committed = null)
            hit++
        }
        if (l.yes) report.confirmed += hit else report.denied += hit
    }

    /**
     * How [retyped] relates to the earlier commit [was]: CLOSE when it differs by at most half
     * the longer word's letters (go/to, gels/tells, bit/but), EXTENDS when it only adds letters
     * at the end, NONE otherwise (a stray "j" before "the", or a different word altogether).
     */
    fun retypeOf(was: String?, retyped: String): Retype =
        Retype.valueOf(GoldPrompt.retypeOf(was, retyped).name)

    /**
     * One line per miss: label, shipping and deep rank, buckets, the top three,
     * then each token in time order - T for a tap (key), S for a swipe (its key
     * contacts, arc length) - with stream, start and end relative to the first.
     */
    /** The buffer in one field: each token in time order, a tap as its letter, a swipe as (keys). */
    private fun shape(r: ReplayHarness.Result): String = r.tokens.sortedBy { it.tStart }.joinToString("") { t ->
        when (t) {
            is com.kinetica.keyboard.engine.models.TapToken -> com.kinetica.keyboard.engine.Alphabet.charOf(t.code).toString()
            is com.kinetica.keyboard.engine.models.SwipeToken -> "(" + t.keyContacts.joinToString("") { com.kinetica.keyboard.engine.Alphabet.charOf(it.code).toString() } + ")"
            else -> "?"
        }
    }

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
            // Whether the buffer may be read as two words at all (PeckSplit.read is not null).
            com.kinetica.keyboard.engine.PeckSplit.read(p, sorted, context, joined, { ch -> ch in ReplayHarness.ONE_LETTER_WORDS[p.language].orEmpty() }) != null,
            // The language model's say on two words against one (WordPredictor.splitLm, #132).
            restList.firstOrNull()?.let { p.splitLm(tapWord, it.word, joined.firstOrNull()?.word, context.lastOrNull()) } ?: 0f,
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
            // Whether the rest may stand as a word (a swipe, or taps that spell one).
            rest != null && (!restTaps || (rest.source != com.kinetica.keyboard.engine.models.WordCandidate.Source.COMPLETION &&
                (if (rest.word.length == 1) one(rest.word[0].lowercaseChar()) else p.isWord(rest.word)))),
            if (tap != null && rest != null) p.splitLm(tap.word, rest.word, joined.firstOrNull()?.word, context.lastOrNull()) else 0f,
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
    private var aimModel: com.kinetica.keyboard.engine.AimModel? = null

    /**
     * --learn words|phrases: learn in trace order (see [ReplayHarness.Learner]).
     * --seed FILE: lang<TAB>word lines merged from the start, as an imported user dictionary.
     */
    private fun learnerOf(mode: String?, seed: String?, pickWeight: Int, personal: String? = null): ReplayHarness.Learner? {
        if (personal != null) return personalOf(File(personal), learns = mode != null, pickWeight = pickWeight)
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

    /**
     * --personal BACKUP: the personal store of a settings backup (Settings > Back up; its
     * `word` and `phrase` rows) as the state every line decodes with: learned words in the
     * trie, commit counts and pairs. It is the store at export time, not at each line, so
     * early lines see words learned after them. Frozen unless --learn is given as well.
     */
    fun personalOf(backup: File, learns: Boolean = false, pickWeight: Int = KineticaConstants.PICK_LEARN_UNITS): ReplayHarness.Learner {
        val l = ReplayHarness.Learner(phrases = true, pickWeight = pickWeight, frozen = !learns)
        backup.forEachLine { line ->
            val f = line.split('\t')
            when {
                f.size >= 4 && f[0] == "word" -> f[3].toIntOrNull()?.let { l.countsOf(f[1])[f[2].lowercase()] = it }
                f.size >= 5 && f[0] == "phrase" -> f[4].toIntOrNull()?.let {
                    l.pairsOf(f[1])[com.kinetica.keyboard.engine.WordPredictor.pairKey(f[2].lowercase(), f[3].lowercase())] = it
                }
            }
        }
        return l
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
