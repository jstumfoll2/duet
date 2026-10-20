package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DEV build is one app: the trace screen is reached from Settings and the start screen, not
 * from a launcher icon of its own. Read off the manifests and the settings XML as text, like
 * [PreferenceTreeTest].
 */
class TraceScreenTest {

    private fun read(rel: String): String {
        val direct = Paths.get(rel)
        val path = if (Files.exists(direct)) direct else Paths.get("app", rel)
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }

    @Test
    fun theDebugBuildAddsNoSecondLauncherIcon() {
        val debug = read("src/debug/AndroidManifest.xml")
        assertFalse("the trace screen must not have its own launcher icon", "category.LAUNCHER" in debug)
        assertTrue("the trace screen is opened only by this app", "android:exported=\"false\"" in debug)
    }

    @Test
    fun theSettingsRowNamesTheDebugScreen() {
        val debug = read("src/debug/AndroidManifest.xml")
        assertTrue(TraceScreen.CLASS_NAME.removePrefix("com.kinetica.keyboard") + "\"" in debug)
        val xml = PrefsXml.read()
        assertEquals(1, Regex("""android:key="${TraceScreen.PREF_KEY}"""").findAll(xml).count())
        // A top-level row, where the fragment that removes it in a release build looks for it.
        assertEquals(null, PrefsXml.walk(xml).first[TraceScreen.PREF_KEY])
    }
}
