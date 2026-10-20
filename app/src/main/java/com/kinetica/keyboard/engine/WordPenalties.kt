package com.kinetica.keyboard.engine

/**
 * Words the user asked to see less of, per language: each "less" is one step, and each step
 * halves the word's score ([KineticaConstants.DOWNRANK_FACTOR]). The personal count only ever
 * adds to a word, so sliding it down cannot push a common word below its corpus rank; a step
 * here can.
 *
 * Immutable: every change returns a new value, so a decode thread holding the old map never
 * sees a half-written one. Stored as one string preference, `lang|word|steps` rows joined by
 * commas, which keeps it inside the settings backup with no new record type.
 */
class WordPenalties private constructor(private val byLang: Map<String, Map<String, Int>>) {

    /** Steps on [word] in [lang], 0 when none. */
    fun steps(word: String, lang: String): Int = byLang[lang]?.get(word.lowercase()) ?: 0

    /** The steps for one language, keyed lowercase, for a predictor. */
    fun forLanguage(lang: String): Map<String, Int> = byLang[lang] ?: emptyMap()

    /** [word] with its steps set to [steps], clamped to 0..[KineticaConstants.DOWNRANK_MAX_STEPS]; 0 removes it. */
    fun with(word: String, lang: String, steps: Int): WordPenalties {
        val w = word.lowercase()
        val s = steps.coerceIn(0, KineticaConstants.DOWNRANK_MAX_STEPS)
        val langMap = HashMap(byLang[lang] ?: emptyMap())
        if (s == 0) langMap.remove(w) else langMap[w] = s
        val out = HashMap(byLang)
        if (langMap.isEmpty()) out.remove(lang) else out[lang] = langMap
        return WordPenalties(out)
    }

    fun serialize(): String =
        byLang.entries.sortedBy { it.key }.flatMap { (lang, words) ->
            words.entries.sortedBy { it.key }.map { (w, s) -> "$lang$FIELD$w$FIELD$s" }
        }.joinToString(ROW)

    companion object {
        private const val ROW = ","
        private const val FIELD = "|"

        val EMPTY = WordPenalties(emptyMap())

        /** The score multiplier for [steps] of downrank. */
        fun factor(steps: Int): Float =
            if (steps <= 0) 1f else Math.pow(KineticaConstants.DOWNRANK_FACTOR.toDouble(), steps.toDouble()).toFloat()

        /** Reads [serialize]'s form; malformed rows are dropped, not fatal. */
        fun parse(s: String?): WordPenalties {
            if (s.isNullOrBlank()) return EMPTY
            val out = HashMap<String, HashMap<String, Int>>()
            for (row in s.split(ROW)) {
                val f = row.split(FIELD)
                if (f.size != 3 || f[0].isEmpty() || f[1].isEmpty()) continue
                val steps = f[2].toIntOrNull()?.coerceIn(0, KineticaConstants.DOWNRANK_MAX_STEPS) ?: continue
                if (steps == 0) continue
                out.getOrPut(f[0]) { HashMap() }[f[1].lowercase()] = steps
            }
            return WordPenalties(out)
        }

        /** Whether [word] can be stored: the separators would split its row. */
        fun storable(word: String): Boolean = word.isNotEmpty() && !word.contains(ROW) && !word.contains(FIELD)
    }
}
