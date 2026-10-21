package com.kinetica.keyboard.spell

import android.content.Context
import android.os.Build
import android.service.textservice.SpellCheckerService
import android.util.Log
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.HarperLints
import com.kinetica.keyboard.engine.LoadedDictionary
import com.kinetica.keyboard.engine.SpellLexicon
import com.kinetica.keyboard.grammar.HarperGrammar
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

        /**
         * Sentence checks: the words as the default splitter reports them, then, with Harper
         * grammar on and English active (Android 12+), Harper's problems as grammar results, which
         * the text field underlines and offers fixes for on a tap. The grammar results go last:
         * a field drops error underlines under a word reported as correct, and the words are
         * reported first.
         */
        override fun onGetSentenceSuggestionsMultiple(
            textInfos: Array<TextInfo>,
            suggestionsLimit: Int,
        ): Array<SentenceSuggestionsInfo?> {
            val words: Array<SentenceSuggestionsInfo?> = super.onGetSentenceSuggestionsMultiple(textInfos, suggestionsLimit)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return words
            val config = KeyboardConfig.from(PreferenceManager.getDefaultSharedPreferences(context))
            if (!config.harperGrammar || config.language != "en") return words
            val dialect = if (config.britishSpelling) 1 else 0
            return Array(textInfos.size) { i ->
                withGrammar(words.getOrNull(i), textInfos[i], dialect, suggestionsLimit.coerceIn(0, MAX_SUGGESTIONS))
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun withGrammar(
            words: SentenceSuggestionsInfo?,
            textInfo: TextInfo,
            dialect: Int,
            limit: Int,
        ): SentenceSuggestionsInfo? {
            val text = textInfo.text
            if (text.isBlank()) return words
            val encoded = HarperGrammar.lint(text, dialect) ?: return words
            val marks = HarperLints.marks(HarperLints.parse(encoded), text, limit)
            if (marks.isEmpty()) return words
            val infos = ArrayList<SuggestionsInfo>()
            val offsets = ArrayList<Int>()
            val lengths = ArrayList<Int>()
            if (words != null) {
                for (k in 0 until words.suggestionsCount) {
                    infos.add(words.getSuggestionsInfoAt(k))
                    offsets.add(words.getOffsetAt(k))
                    lengths.add(words.getLengthAt(k))
                }
            }
            for (m in marks) {
                val attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR or
                    if (m.suggestions.isNotEmpty()) SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0
                infos.add(SuggestionsInfo(attrs, m.suggestions.toTypedArray(), textInfo.cookie, textInfo.sequence))
                offsets.add(m.start)
                lengths.add(m.length)
            }
            return SentenceSuggestionsInfo(infos.toTypedArray(), offsets.toIntArray(), lengths.toIntArray())
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
