package com.kinetica.keyboard.ui

/**
 * Skin tones for emoji that take them (👍 to 👍🏽): which emoji do, how a tone is written into
 * one, and the user's choices.
 *
 * Pure, because the JVM test runtime has no glyph table and runs on JDK 17, whose Character
 * lacks isEmojiModifierBase. Whether the device can draw a toned emoji is the picker's check.
 *
 * The asset carries untoned emoji only (EmojiDataTest), so a tone is added at display time and
 * the frequently-used counts stay keyed by the plain emoji whatever tone it went out in.
 */
object EmojiTone {

    /** No tone: the emoji's own yellow. Tones 1 to 5 are light to dark. */
    const val NONE = 0

    /** The five Fitzpatrick modifiers, light to dark; tone `t` is `MODIFIERS[t - 1]`. */
    val MODIFIERS = intArrayOf(0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF)

    /** Tones in the order a chooser offers them: none, then light to dark. */
    val ALL = 0..MODIFIERS.size

    /** The emoji a chooser shows for the default tone: a raised hand, plain or toned. */
    const val SAMPLE = "✋"

    private const val VS16 = 0xFE0F

    /**
     * Unicode's Emoji_Modifier_Base set (emoji-data.txt, Unicode 15), as ranges. Read once from
     * JDK 21's Character.isEmojiModifierBase; a code point outside it never takes a tone.
     */
    private val BASES: List<IntRange> = listOf(
        0x261D..0x261D, 0x26F9..0x26F9, 0x270A..0x270D, 0x1F385..0x1F385, 0x1F3C2..0x1F3C4,
        0x1F3C7..0x1F3C7, 0x1F3CA..0x1F3CC, 0x1F442..0x1F443, 0x1F446..0x1F450, 0x1F466..0x1F478,
        0x1F47C..0x1F47C, 0x1F481..0x1F483, 0x1F485..0x1F487, 0x1F48F..0x1F48F, 0x1F491..0x1F491,
        0x1F4AA..0x1F4AA, 0x1F574..0x1F575, 0x1F57A..0x1F57A, 0x1F590..0x1F590, 0x1F595..0x1F596,
        0x1F645..0x1F647, 0x1F64B..0x1F64F, 0x1F6A3..0x1F6A3, 0x1F6B4..0x1F6B6, 0x1F6C0..0x1F6C0,
        0x1F6CC..0x1F6CC, 0x1F90C..0x1F90C, 0x1F90F..0x1F90F, 0x1F918..0x1F91F, 0x1F926..0x1F926,
        0x1F930..0x1F939, 0x1F93C..0x1F93E, 0x1F977..0x1F977, 0x1F9B5..0x1F9B6, 0x1F9B8..0x1F9B9,
        0x1F9BB..0x1F9BB, 0x1F9CD..0x1F9CF, 0x1F9D1..0x1F9DD, 0x1FAC3..0x1FAC5, 0x1FAF0..0x1FAF8,
    )

    fun isBase(cp: Int): Boolean = BASES.any { cp in it }

    private fun isModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    /**
     * The bases without Emoji_Presentation: plain, they draw as emoji only with U+FE0F after
     * them, so [strip] puts it back where the modifier was. Toned, the modifier does that job.
     */
    private val TEXT_DEFAULT = intArrayOf(0x261D, 0x26F9, 0x270C, 0x270D, 0x1F3CB, 0x1F3CC, 0x1F574, 0x1F575, 0x1F590)

    /** Whether [emoji] takes a skin tone. */
    fun supports(emoji: String): Boolean = emoji.codePoints().anyMatch { isBase(it) }

    /**
     * [emoji] in [tone]. The modifier follows each base code point and replaces a variation
     * selector after it (✌️ is U+270C U+FE0F, toned it is U+270C U+1F3FD). An emoji that takes
     * no tone, or [NONE], comes back plain.
     */
    fun apply(emoji: String, tone: Int): String {
        val plain = strip(emoji)
        if (tone !in 1..MODIFIERS.size || !supports(plain)) return plain
        val out = StringBuilder(plain.length + 2)
        val cps = plain.codePoints().toArray()
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            out.appendCodePoint(cp)
            if (isBase(cp)) {
                out.appendCodePoint(MODIFIERS[tone - 1])
                if (i + 1 < cps.size && cps[i + 1] == VS16) i++
            }
            i++
        }
        return out.toString()
    }

    /**
     * [emoji] without its tone, as the asset writes it: a base that draws as text unless a
     * variation selector follows it (☝️, ✌️, 🖐️) gets that selector back.
     */
    fun strip(emoji: String): String {
        if (emoji.codePoints().noneMatch { isModifier(it) }) return emoji
        val out = StringBuilder(emoji.length)
        val cps = emoji.codePoints().toArray()
        for ((i, cp) in cps.withIndex()) {
            if (isModifier(cp)) {
                val before = cps.getOrNull(i - 1)
                if (before != null && before in TEXT_DEFAULT && cps.getOrNull(i + 1) != VS16) {
                    out.appendCodePoint(VS16)
                }
                continue
            }
            out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /**
     * The user's tones: one default for every emoji, and a remembered tone for each emoji
     * picked from its own long-press row, which wins over the default.
     */
    class Choices(val default: Int = NONE, val perEmoji: Map<String, Int> = emptyMap()) {

        fun toneFor(emoji: String): Int = perEmoji[strip(emoji)] ?: default

        /** [emoji] as the picker, the bar and the frequently-used tab show it. */
        fun render(emoji: String): String = apply(emoji, toneFor(emoji))

        fun withDefault(tone: Int): Choices = Choices(tone.coerceIn(ALL), perEmoji)

        /**
         * [emoji] remembered in [tone]. Choosing the default's own tone forgets the emoji, so
         * a later change of default reaches it again.
         */
        fun withEmoji(emoji: String, tone: Int): Choices {
            val key = strip(emoji)
            val next = LinkedHashMap(perEmoji)
            if (tone == default) next.remove(key) else next[key] = tone.coerceIn(ALL)
            // Oldest out first, so the stored string stays small.
            while (next.size > MAX_REMEMBERED) next.remove(next.keys.first())
            return Choices(default, next)
        }

        /** The per-emoji tones as one preference string: `emoji=tone` pairs joined by commas. */
        fun encodePerEmoji(): String = perEmoji.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /** More remembered emoji than the asset has bases for is never needed. */
    const val MAX_REMEMBERED = 200

    /**
     * Choices from their stored form. Never throws: a malformed pair is skipped, and a
     * tone out of range reads as no tone, because a bad pref must not stop the picker opening.
     */
    fun decode(default: Int, perEmoji: String?): Choices {
        val map = LinkedHashMap<String, Int>()
        if (!perEmoji.isNullOrEmpty()) {
            for (pair in perEmoji.split(',')) {
                val eq = pair.lastIndexOf('=')
                if (eq <= 0) continue
                val tone = pair.substring(eq + 1).toIntOrNull() ?: continue
                val emoji = pair.substring(0, eq)
                if (tone in ALL && supports(emoji)) map[strip(emoji)] = tone
            }
        }
        return Choices(if (default in ALL) default else NONE, map)
    }
}
