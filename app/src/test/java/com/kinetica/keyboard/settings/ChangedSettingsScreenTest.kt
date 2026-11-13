package com.kinetica.keyboard.settings

import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The changed-settings screen opens from the hidden row, lists a moved setting and writes nothing on open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangedSettingsScreenTest {

    private fun titles(f: KeyboardPrefsFragment): List<String> {
        val screen = f.preferenceScreen
        return (0 until screen.preferenceCount).mapNotNull { screen.getPreference(it).title?.toString() }
    }

    @Test
    fun aMovedSettingIsListedAndOpeningTheScreenWritesNothing() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().putBoolean(Prefs.LOOK_SETS, true).commit()
        val before = prefs.all.toMap()
        activity.revealSetting(null, SettingsSynonyms.CHANGED_SCREEN)
        activity.supportFragmentManager.executePendingTransactions()
        val f = activity.supportFragmentManager.findFragmentById(android.R.id.content) as KeyboardPrefsFragment
        assertEquals(SettingsSynonyms.CHANGED_SCREEN, f.preferenceScreen.key)
        val shown = titles(f)
        assertTrue(shown.toString(), activity.getString(R.string.pref_look_sets_title) in shown)
        assertFalse(shown.toString(), activity.getString(R.string.pref_theme_mode_title) in shown)
        assertEquals("inflating the tree for its defaults must not write", before, prefs.all.toMap())
    }
}
