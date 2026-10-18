package com.kinetica.keyboard.spell

import android.content.Context
import android.service.textservice.SpellCheckerService
import android.util.Log
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.LoadedDictionary
import com.kinetica.keyboard.engine.SpellLexicon
import com.kinetica.keyboard.settings.KeyboardConfig

/**
 * Android spell checker backed by the keyboard's own dictionaries, chosen in the system's
 * spell checker settings. It answers in the keyboard's languages, not the system locale: a
 * word is right when any enabled language spells it that way ([SpellLexicon]), so Spanish
 * typed on an English phone is not underlined. Without it the system checker (usually English
 * only) flagged correct Spanish while the owner practised (2026-10-18).
 *
 * Reads the same bundled or imported wordlists and the same learned words the keyboard merges,
 * on the binder thread that asks; nothing leaves the device.
 */
class KineticaSpellChecker : SpellCheckerService() {

    override fun createSession(): Session = KineticaSession(applicationContext)

    private class KineticaSession(private val context: Context) : Session() {
        override fun onCreate() = Unit

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo {
            val word = textInfo.text.trim()
            // Numbers, URLs, mixed tokens: not words this checker has an opinion on.
            if (word.isEmpty() || !word.all { it.isLetter() || it == '\'' || it == '’' }) {
                return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, null)
            }
            val normalised = word.replace('’', '\'')
            val lexicon = lexicon(context)
            if (lexicon.isCorrect(normalised)) {
                return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, null)
            }
            val suggestions = lexicon.suggestions(normalised, suggestionsLimit.coerceIn(0, MAX_SUGGESTIONS))
            val attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                if (suggestions.isNotEmpty()) SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0
            return SuggestionsInfo(attrs, suggestions.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "KineticaSpell"
        private const val MAX_SUGGESTIONS = 5
        private const val USER_WORD_LIMIT = 5000

        private var cachedKey: String? = null
        private var cached: SpellLexicon = SpellLexicon(emptyList())

        /**
         * The lexicon for the languages enabled now, active first, rebuilt when that list, the
         * active language or the dictionary generation (an import, a learned-word reload)
         * changes. Learned words arrive with the next rebuild, as they do on the keyboard.
         */
        @Synchronized
        fun lexicon(context: Context): SpellLexicon {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val config = KeyboardConfig.from(prefs)
            val ordered = listOf(config.language) + config.enabledLanguages.filter { it != config.language }
            val key = ordered.joinToString(",") + "#" + config.dictionaryGeneration
            if (key == cachedKey) return cached
            val dicts = ordered.mapNotNull { lang -> load(context, lang)?.let { lang to it } }
            cached = SpellLexicon(dicts)
            cachedKey = key
            return cached
        }

        private fun load(context: Context, lang: String): LoadedDictionary? = try {
            val rows = try {
                KineticaDb.get(context).userWords().topN(lang, USER_WORD_LIMIT)
            } catch (e: RuntimeException) {
                Log.w(TAG, "user dictionary unavailable for $lang", e)
                emptyList()
            }
            val learned = DictionaryLoader.userWordsForMerge(rows.map { it.word to it.frequency })
            val override = DictionaryStore.wordlistOverride(context, lang)
            val stream = if (override.exists()) override.inputStream() else context.assets.open("dictionaries/${lang}_wordlist.txt")
            stream.bufferedReader().use {
                DictionaryLoader.load(
                    it,
                    learned,
                    alphabet = Alphabet.forLanguage(lang),
                    newWordMinWeight = DictionaryLoader.NEW_WORD_MIN_WEIGHT,
                )
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "no wordlist for $lang", e)
            null
        }
    }
}
