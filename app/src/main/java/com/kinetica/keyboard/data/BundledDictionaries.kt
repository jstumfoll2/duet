package com.kinetica.keyboard.data

import android.content.Context
import android.util.Log
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.LoadedDictionary
import java.io.IOException

/**
 * One parse per language and process of a wordlist with nothing merged in: the bundled list, or
 * the user's imported replacement for it (review D3, #185).
 *
 * The keyboard parses every other enabled language like this just to file words, and the spell
 * checker, in the same process, parsed the same lists again. Both now share this cache. A trie
 * is immutable once built, so readers on any thread can share it. An entry is keyed by the
 * imported file's size and time, so an import or a reset is picked up on the next call.
 */
object BundledDictionaries {

    private const val TAG = "BundledDictionaries"

    private class Entry(val stamp: String, val dict: LoadedDictionary)

    private val entries = HashMap<String, Entry>()
    private val locks = HashMap<String, Any>()

    /** The plain dictionary for [lang], parsed now or shared from an earlier call; null without a list. */
    fun get(context: Context, lang: String): LoadedDictionary? {
        val app = context.applicationContext ?: context
        val override = DictionaryStore.wordlistOverride(app, lang)
        val stamp = if (override.exists()) "file:${override.length()}:${override.lastModified()}" else "asset"
        // One parse per language at a time; different languages parse in parallel.
        val lock = synchronized(locks) { locks.getOrPut(lang) { Any() } }
        synchronized(lock) {
            synchronized(entries) { entries[lang] }?.let { if (it.stamp == stamp) return it.dict }
            val dict = try {
                val stream = if (override.exists()) override.inputStream() else app.assets.open("dictionaries/${lang}_wordlist.txt")
                stream.bufferedReader().use { DictionaryLoader.load(it, alphabet = Alphabet.forLanguage(lang)) }
            } catch (e: IOException) {
                Log.w(TAG, "no wordlist for $lang", e)
                return null
            }
            synchronized(entries) { entries[lang] = Entry(stamp, dict) }
            return dict
        }
    }

    /** Drops every entry; for tests. */
    fun clear() = synchronized(entries) { entries.clear() }
}
