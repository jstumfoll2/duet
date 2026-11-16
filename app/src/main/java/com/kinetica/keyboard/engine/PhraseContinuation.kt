package com.kinetica.keyboard.engine

/**
 * The words this user nearly always types next, as one phrase (issue #137): after "on my" the
 * bar can offer "way home" whole instead of one word at a time.
 *
 * Built from the learned pair counts alone, one link at a time. A link is followed only when it
 * was seen often and is by far the usual way on from its word, so the chip appears for the
 * user's fixed phrases and stays away everywhere else: a phrase that is wrong costs the bar a
 * zone and the eye a read.
 */
object PhraseContinuation {

    /** Fewest times a link must have been typed. Above the pair store's own floor of 2. */
    const val MIN_COUNT = 3

    /** A link's share of everything learned after its word, at the least. */
    const val MIN_SHARE = 0.6f

    /** A one-word phrase is the next-word bar's job. */
    const val MIN_WORDS = 2

    const val MAX_WORDS = 4

    /**
     * The phrase after [prev], as folded lowercase words, or empty when there is none.
     *
     * [pairs] is keyed as [WordPredictor.pairKey] builds it. [accept] says whether a word may be
     * shown at all (in the dictionary, not blocked); a link into a refused word ends the phrase.
     * No word repeats, [prev] included, so a loop ("ha ha ha") is never offered.
     */
    fun after(
        prev: String,
        pairs: Map<String, Int>,
        minCount: Int = MIN_COUNT,
        minShare: Float = MIN_SHARE,
        accept: (String) -> Boolean = { true },
    ): List<String> {
        if (pairs.isEmpty()) return emptyList()
        var word = AccentFolder.fold(prev.lowercase())
        if (word.isEmpty()) return emptyList()
        val seen = HashSet<String>().apply { add(word) }
        val out = ArrayList<String>(MAX_WORDS)
        while (out.size < MAX_WORDS) {
            val prefix = word + SEPARATOR
            var total = 0L
            var best: String? = null
            var bestCount = 0
            for ((key, count) in pairs) {
                if (count <= 0 || !key.startsWith(prefix)) continue
                total += count
                val next = key.substring(prefix.length)
                // The smaller word on a tie, so the answer does not hang on the map's order.
                if (count > bestCount || (count == bestCount && best != null && next < best)) {
                    best = next
                    bestCount = count
                }
            }
            val next = best ?: break
            if (bestCount < minCount || bestCount < minShare * total) break
            if (next.isEmpty() || next in seen || !accept(next)) break
            out.add(next)
            seen.add(next)
            word = next
        }
        return if (out.size >= MIN_WORDS) out else emptyList()
    }

    private const val SEPARATOR = "\u0000"
}
