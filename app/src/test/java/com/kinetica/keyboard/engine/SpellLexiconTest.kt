package com.kinetica.keyboard.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpellLexiconTest {

    private fun dict(text: String) = DictionaryLoader.load(text.reader().buffered())

    private val en = dict("the\t1000\nthen\t500\nthey\t400\ninstalled\t300\ncaffeine\t200\nserver\t150\n")
    private val es = dict("olvidó\t900\nolvido\t10\ntemprano\t800\nlimonada\t300\nel\t1000\n")

    @Test
    fun aWordOfAnyEnabledLanguageIsCorrect() {
        // The report: correct Spanish flagged while the checker only knew English.
        val lex = SpellLexicon(listOf("en" to en, "es" to es))
        assertTrue(lex.isCorrect("temprano"))
        assertTrue(lex.isCorrect("server"))
        assertTrue(lex.isCorrect("Limonada"))
        assertTrue(lex.isCorrect("THE"))
        assertFalse(lex.isCorrect("instslled"))
        assertFalse(SpellLexicon(listOf("en" to en)).isCorrect("temprano"))
    }

    @Test
    fun accentsMustMatchWhenTheDictionaryKeepsThem() {
        val accentOnly = dict("olvidó\t900\n")
        val lex = SpellLexicon(listOf("es" to accentOnly))
        assertTrue(lex.isCorrect("olvidó"))
        assertFalse(lex.isCorrect("olvido"))
        assertEquals("olvidó", lex.suggestions("olvido", 3).first())
    }

    @Test
    fun suggestionsAreNearestThenCommonest() {
        val lex = SpellLexicon(listOf("en" to en, "es" to es))
        assertEquals("installed", lex.suggestions("instslled", 3).first())
        assertEquals("caffeine", lex.suggestions("caffine", 3).first())
        // A swap of neighbours is one edit, and case follows the typed word.
        assertEquals("The", lex.suggestions("Teh", 3).first())
        assertTrue(lex.suggestions("zzzzzzz", 3).isEmpty())
    }

    @Test
    fun bundledSpanishPracticeWordsAreCorrect() {
        val dir = File("src/main/assets/dictionaries")
        assumeTrue(File(dir, "es_wordlist.txt").exists())
        val esReal = File(dir, "es_wordlist.txt").bufferedReader().use { DictionaryLoader.load(it) }
        val enReal = File(dir, "en_wordlist.txt").bufferedReader().use { DictionaryLoader.load(it) }
        // Words from the owner's 2026-10-18 Spanish practice.
        val lex = SpellLexicon(listOf("en" to enReal, "es" to esReal))
        for (w in listOf("olvidó", "recibí", "almorcé", "desperté", "temprano", "calcetines", "limonada", "almuerzo")) {
            assertTrue(w, lex.isCorrect(w))
        }
        assertEquals("installed", lex.suggestions("instslled", 5).first())
    }
}
