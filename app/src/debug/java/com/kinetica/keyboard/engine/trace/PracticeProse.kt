package com.kinetica.keyboard.engine.trace

import java.util.Random

/**
 * Passages for prose practice: whole sentences copied by typing, so the trace sees words in
 * running text (context, autospace, capitals, punctuation, short glue words) instead of one
 * isolated prompt at a time.
 *
 * Each word of the passage is the label of the line its attempt writes, the same `target` the
 * word prompts give. Which passage word is being typed is read from the field: the finished
 * words of the text are aligned to the passage, and a word counts as finished once something
 * follows it. That is decided from the text alone ([progress]), so backspacing, retyping or the
 * keyboard's own spacing all land on the right word without any bookkeeping.
 *
 * The passages were written for this app (no third-party text). They lean on what the
 * decoder and the traces say is hard: short words, double letters ("too", "will", "coffee"),
 * contractions, and long words that cross both halves of the keyboard.
 */
object PracticeProse {

    /** One word of a passage: [shown] as written (case and punctuation), [label] as the trace target. */
    data class Word(val shown: String, val label: String)

    /** Where the typing stands against a passage. */
    data class Progress(
        /** Whether each finished word matched its passage word, in order. */
        val finished: List<Boolean>,
        /** The passage word being typed now, or null when every word is finished. */
        val current: Int?,
    ) {
        val hits: Int get() = finished.count { it }
    }

    val PASSAGES: List<String> = listOf(
        "I will be there soon. Can you wait a little longer for me?",
        "The coffee was too hot, so I let it cool for a minute.",
        "We need to talk about the meeting tomorrow morning.",
        "She said it would be fine, but I'm not so sure.",
        "Let me know when you get home, and don't forget your keys.",
        "It's been a long week. I think we should all take a break.",
        "Did you see the message I sent you this afternoon?",
        "The weather looks good for the weekend, so let's go to the beach.",
        "I'm running a few minutes late. Start without me if you need to.",
        "Thanks for your help yesterday. It made a real difference.",
        "My phone keeps dropping the call when I walk down the hill.",
        "Please call me back when you have a moment to spare.",
        "We should probably book the tickets before the price goes up.",
        "He wasn't sure what to say, so he just smiled and nodded.",
        "There is a small shop across the street that sells good bread.",
        "Could you pick up some milk and eggs on your way back?",
        "I didn't realize how late it was until the lights went off.",
        "The kids are asleep, the dishes are done, and the house is quiet.",
        "If it rains tomorrow, we can always watch a movie instead.",
        "Remember to water the plants while I'm away next week.",
        "That was the best dinner we have had in a really long time.",
        "Honestly, I think the first version was better than this one.",
        "The battery is almost empty, so I'll have to keep this short.",
        "We still have plenty of time, but let's not wait too long.",
        "Who left the door open? The cat got out again this morning.",
        "I'll be happy to help, as long as it doesn't take all day.",
        "Every summer we drive north to visit my grandparents by the lake.",
        "The new keyboard feels strange at first, but you get used to it quickly.",
        "Write down anything that seems wrong, and we can fix it together later.",
        "They walked along the river until the sun began to set behind the trees.",
        "Good morning! Did you sleep well? Breakfast is ready whenever you are.",
        "I need to buy a gift for my brother. Do you have any good ideas?",
        "Don't worry about the mess. We will clean everything up after the party.",
        "Traffic was terrible today, so the drive took almost twice as long.",
        "Is it possible to move our call to Thursday at three?",
        "The kitchen smells wonderful. What are you cooking tonight?",
        "Some days are just harder than others, and that is all right.",
        "Turn left at the next light, then keep going straight for a mile.",
        "I would rather walk than wait another hour for the bus.",
        "Before you leave, could you check that all the windows are closed?",
    )

    /** Letters and apostrophes, lowercased: the word as the keyboard would commit it. */
    fun labelOf(token: String): String = buildString {
        for (c in token) {
            when {
                c.isLetter() -> append(c.lowercaseChar())
                c == '\'' || c == '’' -> append('\'')
            }
        }
    }.trim('\'')

    /** The passage as words; tokens with no letters (a lone dash) are not words to type. */
    fun words(passage: String): List<Word> =
        passage.split(WHITESPACE).filter { it.isNotEmpty() }
            .mapNotNull { t -> labelOf(t).takeIf { it.isNotEmpty() }?.let { Word(t, it) } }

    /**
     * Matches [typed], the field's text, against [passage]. A typed word is finished once anything
     * but letters follows it (a space, or punctuation the keyboard put straight after it); the
     * finished words are aligned to the start of the passage ([align]) and the word being typed
     * is the passage word after the last one they cover. Text past the passage's end is ignored.
     */
    fun progress(passage: List<Word>, typed: String): Progress {
        val tokens = typed.split(WHITESPACE).map(::labelOf).filter { it.isNotEmpty() }
        // The last word is still open while it ends the text with a letter or an apostrophe.
        val open = tokens.isNotEmpty() && typed.last().let { it.isLetter() || it == '\'' || it == '\u2019' }
        val finished = align(passage.map { it.label }, if (open) tokens.dropLast(1) else tokens)
        return Progress(finished, if (finished.size < passage.size) finished.size else null)
    }

    /**
     * Aligns the finished typed words [typed] to a prefix of the passage words [words]: the
     * cheapest edit from one to the other, where a typed word stands for one passage word at the
     * cost of how different the two are (0 when equal, at most 1), for two passage words run
     * together ("between" for "be there") or two typed words for one ("to day"), and a typed word
     * with no passage word, or a passage word left out, costs a little over 1. Returns, for each
     * passage word the typed text has reached, whether one typed word spelled it exactly.
     *
     * Counting typed words alone put every label after a word the keyboard split or joined one
     * word off until the end of the passage (batch 2026-10-22: "be there" read as "between", and
     * every line after it labelled with the word before). Aligned, a wrong guess about such a word
     * labels at most the next one wrong, and the word after that puts it right.
     */
    fun align(words: List<String>, typed: List<String>): List<Boolean> {
        val n = typed.size
        val m = words.size
        val cost = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE } }
        // How each cell was reached: typed words and passage words taken, and whether exactly.
        val from = Array(n + 1) { IntArray(m + 1) }
        cost[0][0] = 0.0
        fun relax(i: Int, j: Int, di: Int, dj: Int, c: Double) {
            val ni = i + di
            val nj = j + dj
            if (ni > n || nj > m) return
            val v = cost[i][j] + c
            if (v < cost[ni][nj] - 1e-9) {
                cost[ni][nj] = v
                from[ni][nj] = di * 4 + dj
            }
        }
        for (i in 0..n) {
            for (j in 0..m) {
                if (cost[i][j] == Double.MAX_VALUE) continue
                if (i < n && j < m) relax(i, j, 1, 1, distance(typed[i], words[j]))
                if (i < n && j + 1 < m) {
                    joined(typed[i], words[j], words[j + 1])?.let { relax(i, j, 1, 2, it + JOIN) }
                }
                if (i + 1 < n && j < m) {
                    joined(words[j], typed[i], typed[i + 1])?.let { relax(i, j, 2, 1, it + JOIN) }
                }
                if (i < n) relax(i, j, 1, 0, GAP)
                if (j < m) relax(i, j, 0, 1, GAP)
            }
        }
        // Every typed word placed; the passage words after the last one placed are still to come.
        // Ties go to the further end: a typed word is more likely an attempt than a stray.
        var end = 0
        for (j in 1..m) if (cost[n][j] <= cost[n][end] + 1e-9) end = j
        val hit = BooleanArray(end)
        var i = n
        var j = end
        while (i > 0 || j > 0) {
            val di = from[i][j] / 4
            val dj = from[i][j] % 4
            if (di == 1 && dj == 1) hit[j - 1] = typed[i - 1] == words[j - 1]
            i -= di
            j -= dj
        }
        return hit.toList()
    }

    /**
     * How far [one] is from [a] and [b] run together, or null when the pair is no closer to it
     * than one of its halves alone: then the other half is a stray word, not a piece of this one
     * ("there gad" is "there" and a stray, not "there" broken in two).
     */
    private fun joined(one: String, a: String, b: String): Double? {
        val d = distance(one, a + b)
        return if (d < distance(one, a) && d < distance(one, b)) d else null
    }

    /** Levenshtein distance over the longer length: 0 for the same word, 1 for nothing alike. */
    internal fun distance(a: String, b: String): Double {
        if (a == b) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (x in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = x
            for (y in 1..b.length) {
                cur[y] = minOf(prev[y] + 1, cur[y - 1] + 1, prev[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /** Extra cost of one typed word for two passage words, or two for one, over their spelling. */
    private const val JOIN = 0.1

    /** A typed word with no passage word, or a passage word with none typed. */
    private const val GAP = 1.01

    /** A passage other than [after] (the index of the last one), so the same one never comes twice in a row. */
    fun pick(rnd: Random, after: Int = -1): Int {
        if (after !in PASSAGES.indices || PASSAGES.size < 2) return rnd.nextInt(PASSAGES.size)
        val i = rnd.nextInt(PASSAGES.size - 1)
        return if (i >= after) i + 1 else i
    }

    private val WHITESPACE = Regex("\\s+")
}
