package com.kinetica.keyboard.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.UserDictionary
import android.util.Log
import com.kinetica.keyboard.engine.SystemWords

/**
 * Words the phone already knows that no bundled list spells: the Android user dictionary
 * (Settings > Languages > Personal dictionary, shared by every keyboard) and, only with the
 * user's opt-in ([com.kinetica.keyboard.settings.Prefs.CONTACT_NAMES]) and the READ_CONTACTS
 * permission, the words of contact names. Both are read on this phone, at dictionary load, and
 * never stored, exported or traced: the trie is the only place they end up.
 *
 * Names and tech words were most of the out-of-vocabulary misses in the 2026-10-18 traces; a
 * word here is decodable from its first swipe instead of after three typed commits.
 */
object SystemVocabulary {

    private const val TAG = "SystemVocabulary"

    /** Enough for a large address book; a cap so a synced directory cannot flood the trie. */
    private const val MAX_WORDS = 5000

    fun contactsAllowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Blocking: call off the main thread. Empty, never a crash, when a provider is missing. */
    fun words(context: Context, lang: String, contacts: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        userDictionary(context, lang, out)
        if (contacts && contactsAllowed(context)) contactNames(context, lang, out)
        return out.toList()
    }

    /** The words of the Android user dictionary for [lang], or for every language. */
    private fun userDictionary(context: Context, lang: String, out: MutableSet<String>) {
        try {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_WORDS) {
                    val locale = c.getString(1)
                    if (!locale.isNullOrEmpty() && !locale.startsWith(lang)) continue
                    val word = c.getString(0) ?: continue
                    addWords(word, lang, out)
                }
            }
        } catch (e: RuntimeException) {
            // Android restricts the provider to the current keyboard and spell checker.
            Log.w(TAG, "user dictionary unavailable", e)
        }
    }

    private fun contactNames(context: Context, lang: String, out: MutableSet<String>) {
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_WORDS) {
                    val name = c.getString(0) ?: continue
                    addWords(name, lang, out)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read contacts", e)
        }
    }

    private fun addWords(text: String, lang: String, out: MutableSet<String>) {
        out.addAll(SystemWords.of(text, lang))
    }
}
