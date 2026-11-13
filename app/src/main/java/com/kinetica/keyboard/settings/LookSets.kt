package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * The day and night themes, ported from upstream Kinetica 3.0 and narrowed to Duet's theme rows.
 *
 * The day theme is the settings as they always were; the night theme is the same keys with
 * [Prefs.NIGHT_SUFFIX] ([Prefs.NIGHT_TWINS]), drawn while the phone is in dark mode and
 * [Prefs.LOOK_SETS] is on. Everything that reads the theme reads it through [view] (via
 * [KeyboardConfig.from]), so no reader knows there are two.
 */
object LookSets {

    /** Whether the phone's own theme is dark, from a configuration's `uiMode`. */
    fun systemNight(uiMode: Int): Boolean =
        (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Whether the night theme is the one to draw. */
    fun nightDrawn(prefs: SharedPreferences, systemNight: Boolean): Boolean =
        systemNight && prefs.getBoolean(Prefs.LOOK_SETS, Prefs.DEFAULT_LOOK_SETS)

    /** [prefs] with each theme key read from its night twin when [night]. */
    fun view(prefs: SharedPreferences, night: Boolean): SharedPreferences =
        if (night) NightView(prefs) else prefs

    /**
     * Starts the night theme as a copy of the day theme, the first time the switch is turned on;
     * later it is left as it was edited. Opening the settings has stored the night rows' defaults
     * under the twins by then, so every twin is written over, not only the missing ones.
     * Returns false when it had already run.
     */
    fun seed(prefs: SharedPreferences): Boolean {
        if (prefs.getBoolean(Prefs.LOOK_NIGHT_SEEDED, false)) return false
        val e = prefs.edit()
        for ((twin, value) in seedValues(prefs.all)) {
            when (value) {
                is String -> e.putString(twin, value)
                is Int -> e.putInt(twin, value)
                is Boolean -> e.putBoolean(twin, value)
                // Never written: the twin shows the same default.
                else -> e.remove(twin)
            }
        }
        e.putBoolean(Prefs.LOOK_NIGHT_SEEDED, true)
        e.apply()
        return true
    }

    /** Each night twin and the day value it starts from; null where the day key was never written. */
    fun seedValues(stored: Map<String, *>): List<Pair<String, Any?>> =
        Prefs.NIGHT_TWINS.map { key -> Prefs.nightTwin(key) to stored[key] }

    private class NightView(private val base: SharedPreferences) : SharedPreferences by base {
        private fun k(key: String?): String? = key?.takeIf { it in Prefs.NIGHT_TWINS }?.let(Prefs::nightTwin) ?: key

        override fun getString(key: String?, defValue: String?): String? = base.getString(k(key), defValue)
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            base.getStringSet(k(key), defValues)
        override fun getInt(key: String?, defValue: Int): Int = base.getInt(k(key), defValue)
        override fun getLong(key: String?, defValue: Long): Long = base.getLong(k(key), defValue)
        override fun getFloat(key: String?, defValue: Float): Float = base.getFloat(k(key), defValue)
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = base.getBoolean(k(key), defValue)
        override fun contains(key: String?): Boolean = base.contains(k(key))
    }
}
