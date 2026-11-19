package com.kinetica.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * The learned blobs the keyboard rewrites as you type (shape paths, per-field counts, retype
 * counts, usage counts), in a file of their own rather than the settings file. Every write to
 * the settings file runs the keyboard's settings listener, which rebuilds the whole config and
 * re-applies the view on the main thread: up to five times on each field switch while these
 * lived there (audit M7). The settings backup still carries them ([BackupRun]); the system
 * cloud backup treats this file as it treats the learned-word database.
 */
object LearningPrefs {

    const val FILE = "duet_learning"

    /** The keys kept here, not in the settings file. */
    val KEYS = setOf(Prefs.SHAPE_PATHS, Prefs.FIELD_COUNTS, Prefs.RETYPE_COUNTS, Prefs.USAGE_COUNTS)

    @Volatile private var migrated = false

    /** The learning file, with any of [KEYS] still in the settings file moved over first. */
    fun get(context: Context): SharedPreferences {
        val learning = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!migrated) {
            synchronized(this) {
                if (!migrated) {
                    migrate(PreferenceManager.getDefaultSharedPreferences(context), learning)
                    migrated = true
                }
            }
        }
        return learning
    }

    /** Moves [KEYS] from [settings] to [learning]; a value already in [learning] wins. */
    fun migrate(settings: SharedPreferences, learning: SharedPreferences) {
        val old = settings.all
        val found = KEYS.filter { old[it] is String }
        if (found.isEmpty()) return
        val e = learning.edit()
        for (k in found) if (!learning.contains(k)) e.putString(k, old[k] as String)
        e.commit()
        val s = settings.edit()
        for (k in found) s.remove(k)
        s.apply()
    }
}
