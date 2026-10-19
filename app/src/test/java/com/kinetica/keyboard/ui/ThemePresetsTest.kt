package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The named presets are hand-picked literals, so nothing derives their contrast: each one is
 * held to the same readability bars as the custom palette, and the settings list is held to
 * the shipped ids.
 */
class ThemePresetsTest {

    private fun contrast(a: Int, b: Int) = Hsv.contrast(a, b)

    @Test
    fun everyPresetIsReadable() {
        for (id in ThemePresets.IDS) {
            val t = ThemePresets.find(id)!!
            assertTrue("$id key text ${contrast(t.keyText, t.key)}", contrast(t.keyText, t.key) >= 4.5)
            assertTrue(
                "$id special key text ${contrast(t.keyText, t.keySpecial)}",
                contrast(t.keyText, t.keySpecial) >= 4.5,
            )
            assertTrue("$id hint ${contrast(t.keyHint, t.key)}", contrast(t.keyHint, t.key) >= 2.0)
            assertTrue(
                "$id suggestion ${contrast(t.suggestionText, t.suggestionBg)}",
                contrast(t.suggestionText, t.suggestionBg) >= 4.5,
            )
            assertTrue(
                "$id primary ${contrast(t.suggestionPrimary, t.suggestionBg)}",
                contrast(t.suggestionPrimary, t.suggestionBg) >= 4.5,
            )
            assertTrue(
                "$id accent ${contrast(t.accent, t.suggestionBg)}",
                contrast(t.accent, t.suggestionBg) >= 2.0,
            )
        }
    }

    @Test
    fun keysStandOutFromTheBackground() {
        for (id in ThemePresets.IDS) {
            val t = ThemePresets.find(id)!!
            for ((name, role) in listOf(
                "key" to t.key, "keySpecial" to t.keySpecial, "keyPressed" to t.keyPressed,
                "chip" to t.chip, "popupBg" to t.popupBg, "accent" to t.accent,
            )) {
                assertNotEquals("$id $name == background", t.background, role)
            }
            assertNotEquals("$id pressed == key", t.key, t.keyPressed)
        }
    }

    @Test
    fun presetsAreRecognisedAndOtherModesAreNot() {
        assertTrue(ThemePresets.IDS.isNotEmpty())
        for (id in ThemePresets.IDS) {
            assertTrue(id, id.startsWith(ThemePresets.PREFIX))
            assertTrue(id, ThemePresets.isPreset(id))
            // The hue slider does nothing under a preset; the preview caption relies on this.
            assertFalse(id, KeyboardTheme.hueAffects(id))
        }
        for (mode in listOf(KeyboardTheme.MODE_DEFAULT, KeyboardTheme.MODE_DYNAMIC, KeyboardTheme.MODE_CUSTOM, "", "preset_gone")) {
            assertFalse(mode, ThemePresets.isPreset(mode))
            assertNull(mode, ThemePresets.find(mode))
        }
    }

    private fun res(rel: String): String {
        val direct = Paths.get("src/main/res/$rel")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/$rel")
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun items(xml: String, name: String): List<String> {
        val block = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item>([^<]*)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun theSettingsListOffersEveryPresetAndEveryTranslationLinesUp() {
        val values = items(res("values/arrays.xml"), "theme_mode_values")
        assertEquals(
            listOf(KeyboardTheme.MODE_DEFAULT, KeyboardTheme.MODE_DYNAMIC, KeyboardTheme.MODE_CUSTOM) +
                ThemePresets.IDS,
            values,
        )
        for (dir in listOf("values", "values-it")) {
            assertEquals("$dir theme entries", values.size, items(res("$dir/arrays.xml"), "theme_mode_entries").size)
        }
    }
}
