package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.LoadedDictionary
import com.kinetica.keyboard.engine.BigramTable
import com.kinetica.keyboard.engine.CtcReranker
import com.kinetica.keyboard.engine.CtcScorer
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.io.File
import java.util.concurrent.Executor

/**
 * Replays [SwipeTrace] v1 lines through today's decoder and scores them.
 *
 * Two runs per line. The *shipping* run rebuilds the buffer from the raw samples
 * and decodes it the way the keyboard does - a [WordComposer] over the active
 * language and, when the trace had one, the other language, at the shipping
 * TOP_K - and is what top-1, top-3, MRR and the exactness check read. The *deep*
 * run decodes the same tokens at [deepK] for recall: a larger heap lowers the
 * heap minimum and loosens every early-abandon budget, so it is a different
 * search and must not be mistaken for the shipping list.
 *
 * Personal dictionary state is never loaded: bundled dictionaries only. A line
 * recorded with personal state (`cfg.personal`) or a replaced wordlist is still
 * scored, but excluded from the exactness check, since its live list came from
 * dictionaries the harness does not have.
 */
class ReplayHarness(
    private val assets: File,
    val deepK: Int = DEFAULT_DEEP_K,
    /**
     * The shipping run's optional stage-A term: a [CtcReranker] over this
     * encoder at [beta], reordering a heap [rerankDepth] deep. With a scorer
     * set, the exactness check still compares against the recording, so it
     * then reads as "unchanged from today" rather than "replayed".
     */
    private val ctc: CtcScorer? = null,
    val beta: Float = 0f,
    private val rerankDepth: Int = DEFAULT_DEEP_K,
    /** Interleaved two-thumb reading: off, or on at this weight (null = engine default). */
    private val interleave: Boolean = com.kinetica.keyboard.engine.KineticaConstants.INTERLEAVE_ENABLED,
    private val interleaveWeight: Float = com.kinetica.keyboard.engine.KineticaConstants.INTERLEAVE_WEIGHT,
    /** Held-key double letters: boost (1 = off) and the interior and last-key hold times. */
    private val heldDoubleBoost: Float = KineticaConstants.HELD_DOUBLE_BOOST,
    private val heldDoubleMs: Long = KineticaConstants.HELD_DOUBLE_MS,
    private val heldDoubleEndMs: Long = KineticaConstants.HELD_DOUBLE_END_MS,
    private val heldDoubleMissKeep: Float = 1f,
    /**
     * A per-user touch model learning online: each line decodes with what the lines before it
     * taught, then teaches its own label, as the keyboard learns from each commit. Null is off.
     */
    val touch: com.kinetica.keyboard.engine.TouchModel? = null,
    /** Cap of the two-word context boost; 0 decodes without the trigram table. */
    private val trigramBoost: Float = com.kinetica.keyboard.engine.KineticaConstants.TRIGRAM_BOOST_MAX,
    /**
     * Personal learning as the keyboard does it, in trace order: each line decodes with what
     * the lines before it taught, then teaches its own label. Null replays bundled only.
     */
    val learner: Learner? = null,
    private val heldDoubleFree: Boolean = KineticaConstants.HELD_DOUBLE_FREE_TIME,
    /** The near-edge tap pass's gap; 0 is off. */
    private val edgeTapGap: Float = com.kinetica.keyboard.engine.KineticaConstants.EDGE_TAP_GAP_KW,
    /** Whether a tapped word followed by a swipe may split, as with automatic spaces on. */
    private val peckSplit: Boolean = true,
) {
    /**
     * The personal store one replay builds up: commit counts (merged into the trie at each
     * [reloadEvery] lines, as a dictionary reload does), and with [phrases] the learned pairs.
     */
    class Learner(
        val phrases: Boolean = false,
        val reloadEvery: Int = 300,
        /** Words merged into the trie from the start, as an imported user dictionary would. */
        val seedWords: Map<String, List<String>> = emptyMap(),
        /** Units a word learns when the decode didn't put it first: a pick from the bar, or a retype. */
        val pickWeight: Int = com.kinetica.keyboard.engine.KineticaConstants.PICK_LEARN_UNITS,
    ) {
        val counts = HashMap<String, java.util.concurrent.ConcurrentHashMap<String, Int>>()
        val pairs = HashMap<String, java.util.concurrent.ConcurrentHashMap<String, Int>>()
        internal var sinceReload = 0

        fun countsOf(lang: String) = counts.getOrPut(lang) { java.util.concurrent.ConcurrentHashMap() }
        fun pairsOf(lang: String) = pairs.getOrPut(lang) { java.util.concurrent.ConcurrentHashMap() }

        fun learn(lang: String, context: List<String>, label: String, units: Int = 1) {
            val w = label.lowercase()
            countsOf(lang).merge(w, units, Int::plus)
            if (phrases && context.isNotEmpty()) pairsOf(lang).merge(WordPredictor.pairKey(context.last(), w), 1, Int::plus)
        }
    }

    private class Dict(
        val d: LoadedDictionary,
        val bigrams: BigramTable,
        val trigrams: com.kinetica.keyboard.engine.TrigramTable,
    )

    private val dicts = HashMap<Pair<String, Boolean>, Dict>()

    private fun dict(lang: String, british: Boolean): Dict = dicts.getOrPut(lang to british) {
        val dir = File(assets, "dictionaries")
        val swaps = if (lang == "en" && british) {
            File(dir, "en_gb_variants.txt").bufferedReader().use { DictionaryLoader.loadSpellingSwaps(it) }
        } else {
            emptyMap()
        }
        val extra = learner?.let { l ->
            DictionaryLoader.userWordsForMerge(l.countsOf(lang).entries.map { it.key to it.value }) +
                l.seedWords[lang].orEmpty().map { it to DictionaryLoader.NEW_WORD_MIN_WEIGHT }
        }.orEmpty()
        val d = File(dir, "${lang}_wordlist.txt").bufferedReader().use {
            DictionaryLoader.load(it, extra, spellingSwaps = swaps, newWordMinWeight = if (learner != null) DictionaryLoader.NEW_WORD_MIN_WEIGHT else 0)
        }
        val b = File(dir, "${lang}_bigrams.txt").bufferedReader().use { DictionaryLoader.loadBigrams(it, d.trie) }
        val tf = File(dir, "${lang}_trigrams.txt")
        val t = if (tf.exists()) {
            tf.bufferedReader().use { DictionaryLoader.loadTrigrams(it, d.trie) }
        } else {
            com.kinetica.keyboard.engine.TrigramTable.EMPTY
        }
        Dict(d, b, t)
    }

    /** The shipping-depth predictor for [w]'s language and board, as replay builds it. */
    fun predictorFor(w: SwipeTrace.Word): WordPredictor =
        predictor(w.config.language, w.config.britishSpelling, w.geometry.build(), KineticaConstants.TOP_K)

    /** The bundled trie for [lang], as replay loads it. */
    fun trieFor(lang: String, british: Boolean): com.kinetica.keyboard.engine.Trie = dict(lang, british).d.trie

    private fun predictor(
        lang: String,
        british: Boolean,
        g: KeyboardGeometry,
        topK: Int,
        rerank: Boolean = false,
    ): WordPredictor {
        val d = dict(lang, british)
        // Beta 0 means today: no reranker at all, so the heap stays TOP_K deep too.
        val r = if (rerank && ctc != null && beta != 0f) CtcReranker(ctc, { g }, beta) else null
        return WordPredictor(
            d.d.trie, d.bigrams, g, d.d.forms, language = lang, topK = topK,
            reranker = r, rerankDepth = rerankDepth,
            interleave = interleave, interleaveWeight = interleaveWeight,
            heldDoubleBoost = heldDoubleBoost, heldDoubleMs = heldDoubleMs, heldDoubleEndMs = heldDoubleEndMs,
            heldDoubleMissKeep = heldDoubleMissKeep, trigramBoostMax = trigramBoost, edgeTapGapKw = edgeTapGap,
            personalCounts = learner?.countsOf(lang) ?: emptyMap(),
            personalBigrams = if (learner?.phrases == true) learner.pairsOf(lang) else emptyMap(),
            heldDoubleFreeTime = heldDoubleFree,
        ).also {
            it.touchModel = touch
            it.trigrams = d.trigrams
        }
    }

    /** One replayed line. [rank] and [deepRank] are 1-based, 0 when the label is absent. */
    class Result(
        val word: SwipeTrace.Word,
        val tokens: List<InputToken>,
        val shipping: List<WordCandidate>,
        val deep: List<WordCandidate>,
        val rank: Int,
        val deepRank: Int,
        /** Null when the line is not comparable; see [SwipeTrace.Word.comparable]. */
        val exact: Boolean?,
        val buckets: Set<String>,
        /** Wall time of the shipping decode on this JVM; a relative figure, not a phone timing. */
        val micros: Long,
        /**
         * The label is not a dictionary word and the taps spell it letter for letter: the
         * keyboard typed exactly what was tapped, which is right even with no list entry.
         */
        val typedAsIs: Boolean = false,
    )

    fun replay(w: SwipeTrace.Word): Result {
        val tokens = SwipeTrace.replayTokens(w)
        val g = w.geometry.build()
        val cfg = w.config
        val active = predictor(cfg.language, cfg.britishSpelling, g, KineticaConstants.TOP_K, rerank = true)
        val alt = cfg.alternate?.let { predictor(it, cfg.britishSpelling, g, KineticaConstants.TOP_K, rerank = true) }
        val t0 = System.nanoTime()
        val shipping = composerDecode(active, alt, tokens, w.context, w.apostropheMark)
        val micros = (System.nanoTime() - t0) / 1000

        val deepActive = predictor(cfg.language, cfg.britishSpelling, g, deepK)
        val deepAlt = cfg.alternate?.let { predictor(it, cfg.britishSpelling, g, deepK) }
        val deep = deepDecode(deepActive, deepAlt, tokens, w.context, w.apostropheMark)

        val label = w.label?.lowercase()
        if (learner != null && label != null) {
            // A pick from the bar: on the list, but not first. A typo committed as tapped isn't one.
            val at = shipping.indexOfFirst { it.word.lowercase() == label }
            val units = if (at >= 1) learner.pickWeight else 1
            learner.learn(cfg.language, w.context, label, units)
            if (++learner.sinceReload >= learner.reloadEvery) {
                learner.sinceReload = 0
                dicts.clear()
            }
        }
        if (touch != null && label != null) {
            val codes = active.alphabet.encode(com.kinetica.keyboard.engine.AccentFolder.fold(label))
            if (codes != null) touch.learn(codes, tokens, g)
        }
        val exact = if (w.comparable) sameList(shipping, w.shown.candidates) else null
        val typedAsIs = label != null && tokens.isNotEmpty() && tokens.all { it is TapToken } &&
            !active.isWord(label) &&
            tokens.sortedBy { it.tStart }.joinToString("") { active.alphabet.charOf((it as TapToken).code).toString() } == label
        return Result(
            w, tokens, shipping, deep,
            rankOf(label, shipping), rankOf(label, deep), exact,
            if (label == null) emptySet() else buckets(tokens, label),
            micros, typedAsIs,
        )
    }

    /** The keyboard's own path: context committed, buffer seeded, one merged decode. */
    private fun composerDecode(
        active: WordPredictor,
        alt: WordPredictor?,
        tokens: List<InputToken>,
        context: List<String>,
        apostrophe: Boolean,
    ): List<WordCandidate> {
        var out: List<WordCandidate> = emptyList()
        val direct = Executor { it.run() }
        val c = WordComposer(active, direct, direct, object : WordComposer.Callbacks {
            override fun onCandidates(
                candidates: List<WordCandidate>,
                tentative: WordCandidate?,
                literal: String,
                generation: Int,
            ) { out = candidates }
        })
        c.alternatePredictor = alt
        for (w in context) c.commitWord(w)
        if (apostrophe) c.markApostrophe()
        if (!peckSplit || com.kinetica.keyboard.engine.PeckSplit.tapHead(tokens) == 0) {
            c.seed(tokens)
            return out
        }
        // A tapped word then a swipe: fed as touches, since a seeded buffer is a reloaded word
        // and never splits. A split at the end loses this line's word.
        c.oneLetterWord = { ch -> ch in ONE_LETTER_WORDS[active.language].orEmpty() }
        c.peckSplit = true
        for (t in tokens.sortedBy { it.tEnd }) c.onToken(t)
        return if (c.split != null) emptyList() else out
    }

    /**
     * Both languages at depth, ranked together the way [WordComposer] ranks them
     * (best score per word, other-language words the active dictionary holds
     * dropped) but without the bar's TOP_K cut.
     */
    private fun deepDecode(
        active: WordPredictor,
        alt: WordPredictor?,
        tokens: List<InputToken>,
        context: List<String>,
        apostrophe: Boolean,
    ): List<WordCandidate> {
        val a = active.decode(tokens, context, apostrophe = apostrophe)
        if (alt == null) return a
        val foreign = alt.decode(tokens, context, apostrophe = apostrophe).filter { !active.isWord(it.word) }
        val seen = HashSet<String>()
        return (a + foreign).sortedByDescending { it.score }.filter { seen.add(it.word) }.take(deepK)
    }

    companion object {
        const val DEFAULT_DEEP_K = 50

        /** StandaloneLetters' sets for the traced languages; that object is outside the engine. */
        val ONE_LETTER_WORDS = mapOf("en" to setOf('a', 'i'), "es" to setOf('a', 'e', 'o', 'y'))

        /** Labels of this length or less count as short words. */
        const val SHORT_MAX = 3

        val BUCKETS = listOf("single-swipe", "multi-swipe", "two-thumb-overlap", "tap+swipe", "taps", "double-letter", "short")

        fun rankOf(label: String?, list: List<WordCandidate>): Int {
            if (label == null) return 0
            val i = list.indexOfFirst { it.word.lowercase() == label }
            return i + 1
        }

        fun sameList(a: List<WordCandidate>, b: List<SwipeTrace.Candidate>): Boolean =
            a.size == b.size && a.indices.all {
                a[it].word == b[it].word && a[it].language == b[it].language &&
                    a[it].score.toRawBits() == b[it].score.toRawBits()
            }

        /**
         * The reference doc's buckets. Input shape is exclusive (exactly one of
         * the first five); double-letter and short are attributes of the label
         * and overlap with them.
         */
        fun buckets(tokens: List<InputToken>, label: String): Set<String> {
            val out = LinkedHashSet<String>()
            val swipes = tokens.filterIsInstance<SwipeToken>()
            val taps = tokens.count { it is TapToken }
            out.add(
                when {
                    swipes.isEmpty() -> "taps"
                    taps > 0 -> "tap+swipe"
                    swipes.size == 1 -> "single-swipe"
                    overlapsAcrossThumbs(swipes) -> "two-thumb-overlap"
                    else -> "multi-swipe"
                },
            )
            if ((1 until label.length).any { label[it] == label[it - 1] }) out.add("double-letter")
            if (label.length <= SHORT_MAX) out.add("short")
            return out
        }

        private fun overlapsAcrossThumbs(s: List<SwipeToken>): Boolean {
            val l = s.filter { it.streamId == StreamId.LEFT }
            val r = s.filter { it.streamId == StreamId.RIGHT }
            return l.any { a -> r.any { b -> a.tStart < b.tEnd && b.tStart < a.tEnd } }
        }
    }
}
