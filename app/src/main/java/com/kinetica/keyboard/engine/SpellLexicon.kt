package com.kinetica.keyboard.engine

/**
 * The spell checker's view of the keyboard's dictionaries: a word is spelled right when any
 * enabled language holds that exact spelling, and a wrong one is offered the closest words,
 * the active language first.
 *
 * Every enabled language counts, not only the active one, so a Spanish sentence typed with
 * the board left in English (or the system spell checker asking in its own locale) is not
 * flagged word by word. The owner's report that started this: correct Spanish underlined as
 * wrong while practising, from a spell checker that only knew English.
 *
 * [dictionaries] is in priority order: the active language first.
 */
class SpellLexicon(private val dictionaries: List<Pair<String, LoadedDictionary>>) {

    val languages: List<String> get() = dictionaries.map { it.first }

    /**
     * Whether some dictionary holds [word] as spelled, accents included, ignoring case: a
     * capitalised sentence start or an all-caps word is the same word. A spelling with its
     * accents left off (`olvido` for `olvidó`) is not, when the dictionary keeps the accented
     * form; the spell checker then offers it.
     */
    fun isCorrect(word: String): Boolean {
        val w = word.lowercase()
        if (w.isEmpty()) return true
        return dictionaries.any { (_, d) -> holds(d, w) }
    }

    /**
     * Up to [limit] words within [maxEdits(word)] edits of [word] (insert, delete, substitute,
     * swap two neighbours), on accent-folded letters, nearest first and then commonest. An
     * accent-only difference is distance 0, so the accented spelling leads. Case follows the
     * word: a capitalised word gets capitalised suggestions.
     */
    fun suggestions(word: String, limit: Int): List<String> {
        if (limit <= 0 || word.isEmpty()) return emptyList()
        val lower = word.lowercase()
        val folded = AccentFolder.fold(lower)
        val max = maxEdits(folded)
        // (distance, -frequency, language rank) -> spelling; the best spelling per word wins.
        val found = HashMap<String, Triple<Int, Int, Int>>()
        for ((rank, entry) in dictionaries.withIndex()) {
            val d = entry.second
            val codes = IntArray(folded.length)
            var ok = true
            for (i in folded.indices) {
                codes[i] = d.trie.alphabet.codeOf(folded[i])
                if (codes[i] < 0) ok = false
            }
            if (!ok) continue
            search(d, codes, max) { node, dist ->
                val forms = d.forms[node]
                if (forms == null) {
                    offer(found, d.trie.wordOf(node), dist, d.trie.frequency(node), rank)
                } else {
                    for (f in forms) offer(found, f.display.lowercase(), dist, f.freqByte, rank)
                }
            }
        }
        return found.entries
            .filter { it.key != lower }
            .sortedWith(compareBy({ it.value.first }, { -it.value.second }, { it.value.third }))
            .take(limit)
            .map { matchCase(it.key, word) }
    }

    private fun offer(found: HashMap<String, Triple<Int, Int, Int>>, w: String, dist: Int, freq: Int, rank: Int) {
        val t = Triple(dist, freq, rank)
        val old = found[w]
        if (old == null || dist < old.first || (dist == old.first && freq > old.second)) found[w] = t
    }

    /**
     * Every word node within [max] edits of [codes], with its distance: the classic trie walk
     * carrying one row of the edit-distance table per depth, extended with adjacent swaps
     * (`teh` -> `the`, one edit) since those are the commonest thumb typo after a neighbour key.
     */
    private fun search(d: LoadedDictionary, codes: IntArray, max: Int, hit: (Int, Int) -> Unit) {
        val n = codes.size
        val trie = d.trie
        val first = IntArray(n + 1) { it }
        fun walk(node: Int, letter: Int, prevLetter: Int, prevPrev: IntArray?, prev: IntArray) {
            val row = IntArray(n + 1)
            row[0] = prev[0] + 1
            var best = row[0]
            for (i in 1..n) {
                val cost = if (codes[i - 1] == letter) 0 else 1
                var v = minOf(prev[i] + 1, row[i - 1] + 1, prev[i - 1] + cost)
                if (prevPrev != null && i > 1 && codes[i - 1] == prevLetter && codes[i - 2] == letter) {
                    v = minOf(v, prevPrev[i - 2] + 1)
                }
                row[i] = v
                if (v < best) best = v
            }
            if (row[n] <= max && trie.isWord(node)) hit(node, row[n])
            if (best > max) return
            val c0 = trie.firstChild(node)
            for (k in 0 until trie.childCount(node)) {
                val c = c0 + k
                walk(c, trie.letter(c), letter, prev, row)
            }
        }
        val c0 = trie.firstChild(trie.root)
        for (k in 0 until trie.childCount(trie.root)) {
            val c = c0 + k
            walk(c, trie.letter(c), -1, null, first)
        }
    }

    companion object {
        /** One edit for words up to 4 letters, which have too many neighbours at two. */
        fun maxEdits(folded: String): Int = if (folded.length <= 4) 1 else 2

        private fun holds(d: LoadedDictionary, lower: String): Boolean {
            val folded = AccentFolder.fold(lower)
            val node = d.trie.nodeFor(folded)
            if (node == -1) return false
            val variants = d.forms[node] ?: return folded == lower
            return variants.any { it.display.lowercase() == lower }
        }

        private fun matchCase(suggestion: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
            typed.first().isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
            else -> suggestion
        }
    }
}
