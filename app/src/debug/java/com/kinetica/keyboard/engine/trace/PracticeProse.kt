package com.kinetica.keyboard.engine.trace

import java.util.Random

/**
 * Passages for prose practice: whole sentences copied by typing, so the trace sees words in
 * running text (context, autospace, capitals, punctuation, short glue words) instead of one
 * isolated prompt at a time.
 *
 * Each word of the passage is the label of the line its attempt writes, the same `target` the
 * word prompts give. Which passage word is being typed is read from the field: the n-th word
 * of the text is the attempt at the n-th word of the passage, and a word counts as finished once
 * something follows it. That is decided from the text alone ([progress]), so backspacing,
 * retyping or the keyboard's own spacing all land on the right word without any bookkeeping.
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
     * Matches [typed], the field's text, against [passage]. The n-th word of the text is the
     * attempt at the n-th passage word, and it is finished once anything but letters follows it
     * (a space, or punctuation the keyboard put straight after it). Text past the passage's end
     * is ignored.
     */
    fun progress(passage: List<Word>, typed: String): Progress {
        val tokens = typed.split(WHITESPACE).filter { labelOf(it).isNotEmpty() }
        // The last word is still open while it ends the text with a letter or an apostrophe.
        val open = tokens.isNotEmpty() && typed.isNotEmpty() && typed.last().let { it.isLetter() || it == '\'' || it == '’' }
        val done = (if (open) tokens.size - 1 else tokens.size).coerceAtMost(passage.size)
        val finished = (0 until done).map { labelOf(tokens[it]) == passage[it].label }
        return Progress(finished, if (done < passage.size) done else null)
    }

    /** A passage other than [after] (the index of the last one), so the same one never comes twice in a row. */
    fun pick(rnd: Random, after: Int = -1): Int {
        if (after !in PASSAGES.indices || PASSAGES.size < 2) return rnd.nextInt(PASSAGES.size)
        val i = rnd.nextInt(PASSAGES.size - 1)
        return if (i >= after) i + 1 else i
    }

    private val WHITESPACE = Regex("\\s+")
}
