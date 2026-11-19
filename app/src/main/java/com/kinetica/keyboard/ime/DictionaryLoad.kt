package com.kinetica.keyboard.ime

import android.content.Context
import android.util.Log
import com.kinetica.keyboard.data.BundledDictionaries
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.data.UserBigram
import com.kinetica.keyboard.data.UserWord
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.BigramTable
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.LoadedDictionary
import com.kinetica.keyboard.engine.PairFile
import com.kinetica.keyboard.engine.ShortSlips
import com.kinetica.keyboard.engine.Trie
import com.kinetica.keyboard.engine.TrigramTable
import com.kinetica.keyboard.keys.StandaloneLetters
import java.io.IOException
import java.io.InputStream

/**
 * The parse half of a dictionary install, on the load thread (split out of KineticaIME, #184).
 *
 * It reads the learned words, pairs and block list, parses each resident language's list with
 * them merged in, and returns everything as one [Result]. It touches no keyboard state: the
 * keyboard installs the result on the main thread, or drops it when a newer load superseded it.
 */
class DictionaryLoad(private val context: Context) {

    /** What to load, fixed when the load starts. */
    data class Plan(
        /** The active language. */
        val lang: String,
        /** The resident second language (auto-detect, or no primary), or null. */
        val detect: String?,
        /** The third resident language (no primary), or null. */
        val extra: String?,
        /** Other enabled languages, parsed plain only to file words. */
        val others: List<String>,
        val learnPhrases: Boolean,
        val britishSpelling: Boolean,
        val contactNames: Boolean,
    )

    /** One resident language: its list with the learned words in, and the rows they came from. */
    class Language(
        val lang: String,
        val dict: LoadedDictionary,
        val bigrams: BigramTable,
        val rows: List<UserWord>,
        val pairRows: List<UserBigram>,
    ) {
        val cases: Map<String, DictionaryLoader.LearnedCase> = casesOf(rows)
    }

    class Result(
        val primary: Language,
        /** The active language's block list, lower case. */
        val blocked: Set<String>,
        /** Every language's blocked spellings, lower case. */
        val allBlocked: Set<String>,
        val trigrams: TrigramTable,
        val detect: Language?,
        val extra: Language?,
        val others: List<Pair<String, LoadedDictionary>>,
    )

    /** Loads [plan]. Throws [IOException] when the active language's list cannot be read. */
    fun run(plan: Plan): Result {
        val lang = plan.lang
        // Learned words merge into the trie at load, gated and scaled by
        // PERSONAL_MERGE_MIN_COUNT and USER_FREQ_SCALE (rationale in KineticaConstants).
        val rows = userRows(lang)
        val pairRows = if (plan.learnPhrases) userBigramRows(lang) else emptyList()
        // Words the user has blocked never reach the trie, so they
        // cannot be decoded, completed or suggested.
        val blocked = blockedWords(lang)
        val vocabulary = SystemVocabulary.words(context, lang, plan.contactNames)
        val dict = openWordlist(lang).bufferedReader().use {
            DictionaryLoader.load(
                it, merge(rows), blocked, spellingSwaps(lang, plan.britishSpelling), Alphabet.forLanguage(lang),
                DictionaryLoader.NEW_WORD_MIN_WEIGHT, casesOf(rows), vocabulary,
            )
        }
        val primary = Language(lang, dict, bigrams(lang, dict.trie), rows, pairRows)
        // Only some languages ship a two-word table; a missing asset means none.
        val trigrams = try {
            context.assets.open("dictionaries/${lang}_trigrams.txt").bufferedReader().use {
                DictionaryLoader.loadTrigrams(it, dict.trie)
            }
        } catch (e: IOException) {
            TrigramTable.EMPTY
        }
        // The other resident languages decode beside the active one with their own personal
        // counts: both lists rank together, and one-sided weighting favoured the active
        // language by up to 1.83x on device.
        val detect = plan.detect?.let { resident(it, plan, "secondary") }
        val extra = plan.extra?.let { resident(it, plan, "third") }
        // Plain parses, shared with the spell checker in this process (#185).
        val others = plan.others.mapNotNull { other ->
            BundledDictionaries.get(context, other)?.let { other to it }
        }
        return Result(primary, blocked, allBlockedWords(), trigrams, detect, extra, others)
    }

    private fun resident(lang: String, plan: Plan, role: String): Language? = try {
        val rows = userRows(lang)
        val pairRows = if (plan.learnPhrases) userBigramRows(lang) else emptyList()
        val dict = openWordlist(lang).bufferedReader().use {
            DictionaryLoader.load(
                it, merge(rows), blockedWords(lang), spellingSwaps(lang, plan.britishSpelling),
                Alphabet.forLanguage(lang), DictionaryLoader.NEW_WORD_MIN_WEIGHT, casesOf(rows),
            )
        }
        Language(lang, dict, bigrams(lang, dict.trie), rows, pairRows)
    } catch (e: IOException) {
        Log.w(TAG, "$role dictionary load failed for $lang", e)
        null
    }

    private fun merge(rows: List<UserWord>) = DictionaryLoader.userWordsForMerge(rows.map { it.word to it.frequency })

    private fun userRows(lang: String): List<UserWord> = try {
        withoutStrayLetters(KineticaDb.get(context).userWords().topN(lang, USER_DICT_LIMIT), lang)
    } catch (e: RuntimeException) {
        Log.w(TAG, "user dictionary unavailable for $lang", e)
        emptyList()
    }

    private fun userBigramRows(lang: String): List<UserBigram> = try {
        KineticaDb.get(context).userBigrams().topN(lang, USER_PAIR_LIMIT)
    } catch (e: RuntimeException) {
        Log.w(TAG, "phrase store unavailable for $lang", e)
        emptyList()
    }

    /**
     * Spelling pairs to exchange for [lang], empty unless the user asked for British spelling
     * and this is English. Both spellings are already in the trie, so only the two counts
     * trade places.
     */
    private fun spellingSwaps(lang: String, british: Boolean): Map<String, String> {
        if (lang != "en" || !british) return emptyMap()
        return try {
            context.assets.open("dictionaries/en_gb_variants.txt").bufferedReader().use {
                DictionaryLoader.loadSpellingSwaps(it)
            }
        } catch (e: IOException) {
            Log.w(TAG, "British spelling variants asset unavailable", e)
            emptyMap()
        }
    }

    private fun allBlockedWords(): Set<String> = try {
        KineticaDb.get(context).blockedWords().allWords().mapTo(HashSet()) { it.lowercase() }
    } catch (e: RuntimeException) {
        Log.w(TAG, "blocked words unavailable", e)
        emptySet()
    }

    /**
     * Blocked spellings for [lang], lower-cased to match the loader's test. An unavailable
     * table is an empty block list, not a failed load: the keyboard has to come up either way.
     */
    private fun blockedWords(lang: String): Set<String> = try {
        KineticaDb.get(context).blockedWords().wordsForLanguage(lang)
            .mapTo(HashSet()) { it.lowercase() }
    } catch (e: RuntimeException) {
        Log.w(TAG, "blocked words unavailable for $lang", e)
        emptySet()
    }

    /** Imported (AOSP-merged) wordlist overrides the bundled asset. */
    private fun openWordlist(lang: String): InputStream {
        val override = DictionaryStore.wordlistOverride(context, lang)
        return if (override.exists()) override.inputStream() else context.assets.open("dictionaries/${lang}_wordlist.txt")
    }

    private fun bigrams(lang: String, trie: Trie): BigramTable =
        context.assets.open("dictionaries/${lang}_bigrams.txt").bufferedReader().use {
            DictionaryLoader.loadBigrams(it, trie)
        }.let { withContextPairs(lang, it, trie) }

    /**
     * [table] with the language's wide pair file behind the decoder's boost, where one ships
     * (English: a million pairs to the text table's 100,000). A language without the file,
     * or a file that cannot be read, keeps the text table alone.
     */
    private fun withContextPairs(lang: String, table: BigramTable, trie: Trie): BigramTable =
        try {
            context.assets.open("dictionaries/${lang}_pairs.bin").use { table.withContext(PairFile.read(it, trie)) }
        } catch (e: IOException) {
            table
        }

    companion object {
        private const val TAG = "DictionaryLoad"
        const val USER_DICT_LIMIT = 5000
        const val USER_PAIR_LIMIT = 5000

        fun casesOf(rows: List<UserWord>): Map<String, DictionaryLoader.LearnedCase> =
            DictionaryLoader.learnedCases(rows.map { Triple(it.word, it.display, it.caseVotes) })

        /**
         * [rows] without lone letters that can only be slips in [lang] ([ShortSlips.isStrayLetter]):
         * a count earned by uncorrected `s`-for-`a` slips must not rank `s` above `a` in the decode.
         */
        fun withoutStrayLetters(rows: List<UserWord>, lang: String): List<UserWord> {
            val standalone = ('a'..'z').filterTo(HashSet()) { StandaloneLetters.isWord(it, lang) }
            return rows.filterNot { it.word.length == 1 && ShortSlips.isStrayLetter(it.word[0], standalone) }
        }
    }
}
