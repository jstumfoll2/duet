package com.kinetica.keyboard.engine

/**
 * How typing went, counted per day and kept on the phone (issue #135): words committed, how
 * many were picked from the bar, how many replaced a word just deleted, how many a correction
 * rewrote, and the time and characters behind a words-per-minute figure. Counts only: no word,
 * no text and no app name is ever held here, so the numbers say whether a decoder switch helped
 * in ordinary use without a trace being recorded.
 *
 * A day is a count of days since the epoch in the phone's own time zone; the caller supplies it.
 * Immutable: each note returns the store to keep.
 */
class UsageStats private constructor(private val days: Map<Int, Day>) {

    /** One day's counts. [timedChars] and [activeMs] cover only words typed within [PAUSE_MS] of the one before. */
    data class Day(
        val words: Int = 0,
        val picked: Int = 0,
        val retyped: Int = 0,
        val corrected: Int = 0,
        val timedChars: Int = 0,
        val activeMs: Long = 0,
    ) {
        operator fun plus(o: Day) = Day(
            words + o.words, picked + o.picked, retyped + o.retyped, corrected + o.corrected,
            timedChars + o.timedChars, activeMs + o.activeMs,
        )

        /** Share of words picked from the bar, 0..1. */
        val pickShare: Float get() = if (words == 0) 0f else picked.toFloat() / words

        /** Share of words that replaced a word just deleted or were rewritten by a correction, 0..1. */
        val fixShare: Float get() = if (words == 0) 0f else (retyped + corrected).toFloat() / words

        /** Words per minute at five characters a word, over the timed stretches; 0 with under [MIN_TIMED_MS] of them. */
        val wpm: Float get() = if (activeMs < MIN_TIMED_MS) 0f else timedChars / CHARS_PER_WORD / (activeMs / 60_000f)
    }

    /**
     * A word of [letters] letters was committed on [day]. [sinceLastMs] is the time since the
     * commit before it, or negative when there was none in this field; [picked] when it came
     * from the bar; [retyped] when it replaced a different word the user had just deleted.
     */
    fun withCommit(day: Int, letters: Int, sinceLastMs: Long, picked: Boolean, retyped: Boolean): UsageStats {
        if (letters <= 0) return this
        val timed = sinceLastMs in 1..PAUSE_MS
        return with(day, Day(
            words = 1,
            picked = if (picked) 1 else 0,
            retyped = if (retyped) 1 else 0,
            timedChars = if (timed) letters + 1 else 0,
            activeMs = if (timed) sinceLastMs else 0,
        ))
    }

    /** A correction rewrote a committed word on [day]. */
    fun withCorrection(day: Int): UsageStats = with(day, Day(corrected = 1))

    private fun with(day: Int, add: Day): UsageStats {
        val next = HashMap(days)
        next[day] = (days[day] ?: Day()) + add
        if (next.size > MAX_DAYS) next.keys.sorted().take(next.size - MAX_DAYS).forEach { next.remove(it) }
        return UsageStats(next)
    }

    /** The counts of [day] alone. */
    fun day(day: Int): Day = days[day] ?: Day()

    /** The counts of the [count] days ending with [today], added up. */
    fun last(count: Int, today: Int): Day =
        days.entries.filter { it.key in (today - count + 1)..today }.fold(Day()) { a, e -> a + e.value }

    /** The days held, oldest first. */
    fun heldDays(): List<Int> = days.keys.sorted()

    val isEmpty: Boolean get() = days.isEmpty()

    /** One row per day, `day|words|picked|retyped|corrected|timedChars|activeMs`, joined by commas. */
    fun serialize(): String = days.entries.sortedBy { it.key }.joinToString(",") { (d, c) ->
        "$d|${c.words}|${c.picked}|${c.retyped}|${c.corrected}|${c.timedChars}|${c.activeMs}"
    }

    companion object {
        /** A gap longer than this is a pause, not typing: the word after it is not timed. */
        const val PAUSE_MS = 5_000L

        /** Days kept; the oldest go first. */
        const val MAX_DAYS = 90

        /** Timed typing a words-per-minute figure needs before it is shown. */
        const val MIN_TIMED_MS = 30_000L

        private const val CHARS_PER_WORD = 5f

        val EMPTY = UsageStats(emptyMap())

        /** Reads [serialize]'s text; rows that do not parse are dropped. */
        fun parse(text: String?): UsageStats {
            if (text.isNullOrEmpty()) return EMPTY
            val out = HashMap<Int, Day>()
            for (row in text.split(',')) {
                val f = row.split('|')
                if (f.size != 7) continue
                val n = f.map { it.toLongOrNull() ?: -1L }
                if (n.any { it < 0 }) continue
                out[n[0].toInt()] = Day(n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toInt(), n[5].toInt(), n[6])
            }
            return if (out.isEmpty()) EMPTY else UsageStats(out).let { s ->
                if (out.size <= MAX_DAYS) s else UsageStats(out.entries.sortedBy { it.key }.takeLast(MAX_DAYS).associate { it.key to it.value })
            }
        }
    }
}
