package com.kinetica.keyboard.ime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A word written mid-paragraph, where the editor already has what follows it (`cats|.`,
 * `word| next`), holds its autospace; the space is owed to the next word instead. Before,
 * nothing paid it and every inserted word after the first glued on (`triedto`), which read
 * as autospace being broken whenever the cursor was not at the end of the text.
 */
class OwedSpaceTest {

    @Test
    fun theHoldStillHappensMidParagraph() {
        // The hold itself is right: a word inserted last before `.` or ` next` needs no space.
        assertFalse(autospaceWanted(tidy = true, after = ". "))
        assertFalse(autospaceWanted(tidy = true, after = ", "))
        assertFalse(autospaceWanted(tidy = true, after = " n"))
        assertTrue(autospaceWanted(tidy = true, after = ""))
    }

    @Test
    fun aNewWordAtTheOwedOffsetPaysIt() {
        assertTrue(paysOwedSpace(owedAt = 12, cursor = 12, before = "d"))
    }

    @Test
    fun nothingIsOwedByDefault() {
        assertFalse(paysOwedSpace(owedAt = -1, cursor = 12, before = "d"))
    }

    @Test
    fun aCursorThatMovedSettlesIt() {
        // A tap elsewhere, a backspace, or text written since all leave the cursor elsewhere.
        assertFalse(paysOwedSpace(owedAt = 12, cursor = 11, before = "d"))
        assertFalse(paysOwedSpace(owedAt = 12, cursor = 40, before = "d"))
    }

    @Test
    fun aSpaceAlreadyThereIsTheSpace() {
        assertFalse(paysOwedSpace(owedAt = 12, cursor = 12, before = " "))
        assertFalse(paysOwedSpace(owedAt = 12, cursor = 12, before = "\n"))
        assertFalse(paysOwedSpace(owedAt = 0, cursor = 0, before = ""))
        assertFalse(paysOwedSpace(owedAt = 0, cursor = 0, before = null))
    }

    private fun source(): String {
        val rel = "src/main/java/com/kinetica/keyboard/ime/KineticaIME.kt"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("KineticaIME.kt not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun theHoldOwesTheSpaceAndBothWordStartsPayIt() {
        // The service needs a live editor and dictionary, so the wiring is a source scan.
        val src = source()
        val held = src.indexOf("autospace held before=")
        assertTrue("hold log not found", held > 0)
        assertTrue(
            "the hold branch must record where the space is owed",
            src.substring(src.lastIndexOf("if (finalizePendingWord())", held), held).contains("spaceOwedAt ="),
        )
        // One call for a tap that starts a word, one for a swipe that does.
        assertEquals(2, Regex("""\n\s+(if \(.*\) )?payOwedSpace\(\)""").findAll(src).count())
    }
}
