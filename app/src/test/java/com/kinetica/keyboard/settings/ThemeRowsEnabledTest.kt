package com.kinetica.keyboard.settings

import androidx.preference.ListPreference
import androidx.preference.Preference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The colour rows on Look, sound and vibration are grayed out where the theme ignores them,
 * and the settings search still reaches rows that sit under a heading.
 *
 * The change listeners are called directly, as the list dialog would, because they run before
 * the new value persists and the rows have to follow that value, not the stored one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeRowsEnabledTest {

    private fun open(screen: String?): KeyboardPrefsFragment {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        if (screen != null) activity.revealSetting(screen, "pref_theme_mode")
        activity.supportFragmentManager.executePendingTransactions()
        return activity.supportFragmentManager.findFragmentById(android.R.id.content) as KeyboardPrefsFragment
    }

    private fun KeyboardPrefsFragment.row(key: String): Preference = findPreference(key)!!

    private fun choose(row: ListPreference, value: String) {
        assertTrue(row.onPreferenceChangeListener!!.onPreferenceChange(row, value))
        row.value = value
    }

    @Test
    fun theAccentHueOnlyWorksForTheCustomColourTheme() {
        val f = open("pref_group_look")
        val mode = f.row(Prefs.THEME_MODE) as ListPreference
        assertEquals("default", mode.value)
        assertFalse("Duet dark ignores the hue", f.row(Prefs.THEME_HUE).isEnabled)
        choose(mode, "custom")
        assertTrue(f.row(Prefs.THEME_HUE).isEnabled)
        choose(mode, "dynamic")
        assertFalse(f.row(Prefs.THEME_HUE).isEnabled)
    }

    @Test
    fun brightnessIsOffForTheFixedPresets() {
        val f = open("pref_group_look")
        val mode = f.row(Prefs.THEME_MODE) as ListPreference
        assertTrue(f.row(Prefs.THEME_BRIGHTNESS).isEnabled)
        choose(mode, "preset_ocean")
        assertFalse(f.row(Prefs.THEME_BRIGHTNESS).isEnabled)
        assertFalse(f.row(Prefs.THEME_HUE).isEnabled)
        choose(mode, "custom")
        assertTrue(f.row(Prefs.THEME_BRIGHTNESS).isEnabled)
    }

    @Test
    fun theTrailHueOnlyWorksForTheCustomHueTrail() {
        val f = open("pref_group_look")
        val trail = f.row(Prefs.TRAIL_COLOR) as ListPreference
        assertEquals("rainbow", trail.value)
        assertFalse(f.row(Prefs.TRAIL_COLOR_CUSTOM_HUE).isEnabled)
        choose(trail, "custom")
        assertTrue(f.row(Prefs.TRAIL_COLOR_CUSTOM_HUE).isEnabled)
        choose(trail, "theme")
        assertFalse(f.row(Prefs.TRAIL_COLOR_CUSTOM_HUE).isEnabled)
    }

    @Test
    fun searchReachesRowsUnderAHeading() {
        // Every screen is now split by PreferenceCategory headings, which have no key; a walk
        // that skipped keyless rows would lose everything under them.
        val entries = open(null).searchEntries().associateBy { it.key }
        assertEquals("pref_group_look", entries[Prefs.THEME_HUE]?.screenKey)
        assertEquals("pref_group_keys", entries["pref_enter_action"]?.screenKey)
        assertEquals(null, entries["pref_group_typing"]?.screenKey)
        assertEquals("pref_group_about", entries["pref_licenses_screen"]?.screenKey)
    }
}
