package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ui.KeyboardTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ExactColourTest {

    @Test
    fun aBlankExactColourLeavesTheHueSliderInCharge() {
        assertEquals(KeyboardTheme.primaryForHue(233f), MapPrefs.config().themeColor)
        assertEquals(
            KeyboardTheme.primaryForHue(233f),
            MapPrefs.config(mapOf(Prefs.THEME_COLOR_HEX to "")).themeColor,
        )
        assertEquals(
            KeyboardTheme.primaryForHue(233f),
            MapPrefs.config(mapOf(Prefs.THEME_COLOR_HEX to "#12")).themeColor,
        )
    }

    @Test
    fun anExactThemeColourIsUsedAsTyped() {
        val c = MapPrefs.config(mapOf(Prefs.THEME_COLOR_HEX to "#202124")).themeColor
        assertEquals(0xFF202124.toInt(), c)
    }

    @Test
    fun theExactTrailKeepsItsHueSaturationAndBrightness() {
        val c = MapPrefs.config(
            mapOf(Prefs.TRAIL_COLOR to "exact", Prefs.TRAIL_COLOR_HEX to "#808000"),
        )
        assertEquals(60f, c.trailBaseHue, 0.5f)
        assertEquals(1f, c.trailSaturation, 0.01f)
        assertEquals(0.5f, c.trailValue, 0.01f)
    }

    @Test
    fun otherTrailsDrawAtTheUsualTone() {
        val c = MapPrefs.config(
            mapOf(Prefs.TRAIL_COLOR to "custom", Prefs.TRAIL_COLOR_HEX to "#808000"),
        )
        assertEquals(KeyboardConfig.DEFAULT_TRAIL_SAT, c.trailSaturation, 0f)
        assertEquals(1f, c.trailValue, 0f)
        // Exact without a colour yet falls back to the custom hue, never to red.
        val unset = MapPrefs.config(
            mapOf(Prefs.TRAIL_COLOR to "exact", Prefs.TRAIL_COLOR_CUSTOM_HUE to 150),
        )
        assertEquals(150f, unset.trailBaseHue, 0f)
    }
}
