package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A space on a symbol page goes back to the letters, under its switch (upstream Kinetica 3.0). */
class LettersAfterSpaceTest {

    @Test
    fun aSpaceOnEitherSymbolPageBringsTheLetters() {
        assertTrue(lettersAfterSpace(enabled = true, board = "symbols"))
        assertTrue(lettersAfterSpace(enabled = true, board = "symbols2"))
    }

    @Test
    fun theNumberPadAndTheLettersStay() {
        // A phone number goes on past a space.
        assertFalse(lettersAfterSpace(enabled = true, board = "numpad"))
        // On the letters already: nothing to rebuild.
        assertFalse(lettersAfterSpace(enabled = true, board = "qwerty"))
        assertFalse(lettersAfterSpace(enabled = true, board = null))
    }

    @Test
    fun theSwitchOffKeepsThePage() {
        assertFalse(lettersAfterSpace(enabled = false, board = "symbols"))
        assertFalse(lettersAfterSpace(enabled = false, board = "symbols2"))
    }

    @Test
    fun theSymbolPagesAreNamedAsTheirLayoutsAre() {
        for (page in SYMBOL_PAGES) {
            val json = File("src/main/assets/layouts/$page.json").readText()
            assertTrue(page, json.contains("\"name\": \"$page\""))
        }
    }

    @Test
    fun theSpaceKeyAsksAfterItsSpace() {
        // The service has no JVM reach: the space is written first, then the page changes.
        val src = File("src/main/java/com/kinetica/keyboard/ime/KineticaIME.kt").readText()
        val branch = src.substringAfter("KeyType.SPACE -> {").substringBefore("KeyType.BACKSPACE ->")
        assertTrue(branch.indexOf("onSpace()") in 0 until branch.indexOf("lettersAfterSpace("))
        assertTrue(branch.contains("setKeyboardLayout(alphaLayout())"))
    }
}
