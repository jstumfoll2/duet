package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The day and night themes: which one draws, and that only the theme rows have twins. */
class LookSetsTest {

    private val both = mapOf(
        Prefs.LOOK_SETS to true,
        Prefs.THEME_MODE to "preset_ocean",
        Prefs.nightTwin(Prefs.THEME_MODE) to "custom",
        Prefs.THEME_HUE to 40,
        Prefs.nightTwin(Prefs.THEME_HUE) to 220,
    )

    @Test
    fun theNightThemeDrawsOnlyInDarkModeWithTheSwitchOn() {
        assertEquals("custom", KeyboardConfig.from(MapPrefs(both), systemNight = true).themeMode)
        assertEquals("preset_ocean", KeyboardConfig.from(MapPrefs(both), systemNight = false).themeMode)
        val off = both + (Prefs.LOOK_SETS to false)
        assertEquals("preset_ocean", KeyboardConfig.from(MapPrefs(off), systemNight = true).themeMode)
    }

    @Test
    fun aNeverWrittenNightRowFallsBackToItsDefaultNotTheDayValue() {
        val p = MapPrefs(mapOf(Prefs.LOOK_SETS to true, Prefs.THEME_MODE to "custom"))
        assertEquals(Prefs.DEFAULT_THEME_MODE, KeyboardConfig.from(p, systemNight = true).themeMode)
    }

    @Test
    fun onlyTheThemeRowsHaveNightTwins() {
        val night = LookSets.view(MapPrefs(both + (Prefs.TRAIL_COLOR to "theme")), night = true)
        assertEquals(220, night.getInt(Prefs.THEME_HUE, -1))
        // The trail, image and keys are shared by day and night.
        assertEquals("theme", night.getString(Prefs.TRAIL_COLOR, null))
        assertFalse(Prefs.TRAIL_COLOR in Prefs.NIGHT_TWINS)
        assertFalse(Prefs.BACKDROP in Prefs.NIGHT_TWINS)
    }

    @Test
    fun theSeedCopiesEveryDayThemeRow() {
        val seeded = LookSets.seedValues(both).toMap()
        assertEquals(Prefs.NIGHT_TWINS.map(Prefs::nightTwin).toSet(), seeded.keys)
        assertEquals("preset_ocean", seeded[Prefs.nightTwin(Prefs.THEME_MODE)])
        assertEquals(40, seeded[Prefs.nightTwin(Prefs.THEME_HUE)])
        // Never written by day: the twin is cleared, so it shows the same default.
        assertEquals(null, seeded[Prefs.nightTwin(Prefs.THEME_BRIGHTNESS)])
    }

    @Test
    fun dayKeyUndoesNightTwinOnlyForTwins() {
        assertEquals(Prefs.THEME_MODE, Prefs.dayKey(Prefs.nightTwin(Prefs.THEME_MODE)))
        assertEquals("pref_something_night", Prefs.dayKey("pref_something_night"))
        assertTrue(Prefs.nightTwin(Prefs.THEME_MODE).endsWith(Prefs.NIGHT_SUFFIX))
    }
}
