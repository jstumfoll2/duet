package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A setting stored with the wrong type reads as its default and is removed (review H4). */
class TypeSafePrefsTest {

    /** Casts the way Android's SharedPreferencesImpl does: a wrong type throws. */
    private class StrictPrefs(val values: MutableMap<String, Any>) : SharedPreferences {
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key]?.let { it as String } ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            values[key]?.let { it as Set<String> } ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key]?.let { it as Int } ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key]?.let { it as Long } ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key]?.let { it as Float } ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key]?.let { it as Boolean } ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        inner class Editor : SharedPreferences.Editor {
            private val removed = ArrayList<String>()
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { key?.let(removed::add) }
            override fun clear() = this
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { removed.forEach { values.remove(it) } }
        }
    }

    @Test
    fun aMistypedKeyReadsAsItsDefaultAndIsRemoved() {
        val values = mutableMapOf<String, Any>(
            Prefs.KEYBOARD_HEIGHT_PCT to "x",
            Prefs.THEME_HUE to 233,
        )
        val prefs = StrictPrefs(values)
        val config = KeyboardConfig.from(prefs)
        assertEquals(KeyboardConfig.from(MapPrefs(MapPrefs.HUE_SET)).heightPct, config.heightPct)
        assertFalse(values.containsKey(Prefs.KEYBOARD_HEIGHT_PCT))
        assertTrue(values.containsKey(Prefs.THEME_HUE))
    }

    @Test
    fun wellTypedKeysAreLeftAlone() {
        val values = mutableMapOf<String, Any>(Prefs.THEME_HUE to 233)
        val safe = TypeSafePrefs(StrictPrefs(values))
        assertEquals(233, safe.getInt(Prefs.THEME_HUE, 0))
        assertEquals(7, safe.getInt("missing", 7))
        assertFalse(safe.removeMistyped())
    }

    @Test
    fun aBackedUpValueOnlyReplacesOneOfTheSameType() {
        assertTrue(Backup.sameType(Backup.PrefType.INT, null))
        assertTrue(Backup.sameType(Backup.PrefType.INT, 3))
        assertFalse(Backup.sameType(Backup.PrefType.STRING, 3))
        assertFalse(Backup.sameType(Backup.PrefType.BOOL, "true"))
        assertTrue(Backup.sameType(Backup.PrefType.SET, setOf("en")))
    }
}
