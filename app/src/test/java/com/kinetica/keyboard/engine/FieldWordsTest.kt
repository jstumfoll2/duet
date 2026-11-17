package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.ScoreTerms
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FieldWordsTest {

    @After fun off() {
        FieldWords.enabled = false
    }

    private fun filled(): FieldWords {
        val f = FieldWords()
        // Half the typing is chat, half is code; "grep" lives in code, "hello" in chat,
        // "the" in both alike.
        repeat(30) { f.add("grep", FieldClass.CODE) }
        repeat(30) { f.add("hello", FieldClass.CHAT) }
        repeat(20) { f.add("the", FieldClass.CODE) }
        repeat(20) { f.add("the", FieldClass.CHAT) }
        return f
    }

    @Test fun aWordKeepsItsBoostWhereItLives() {
        val f = filled()
        assertEquals(1f, f.share("grep", FieldClass.CODE), 0f)
        assertEquals(1f, f.factor("grep", FieldClass.CODE, 30), 0f)
        assertEquals(1f, f.share("hello", FieldClass.CHAT), 0f)
    }

    @Test fun aWordLosesMostOfItsBoostElsewhere() {
        val f = filled()
        // 0 commits in chat, 15 expected: (0 + 2) / (15 + 2).
        assertEquals(2f / 17f, f.share("grep", FieldClass.CHAT), 1e-5f)
        val factor = f.factor("grep", FieldClass.CHAT, 30)
        assertTrue(factor < 1f)
        // Never below the whole personal boost: the word falls back to its dictionary frequency.
        val whole = 1f + KineticaConstants.PERSONAL_BOOST * kotlin.math.ln(31f)
        assertTrue(factor > 1f / whole)
    }

    @Test fun aWordUsedEverywhereIsLeftAlone() {
        val f = filled()
        assertEquals(1f, f.share("the", FieldClass.CHAT), 0f)
        assertEquals(1f, f.share("the", FieldClass.CODE), 0f)
    }

    @Test fun anUnknownWordOrAnEmptyClassIsLeftAlone() {
        val f = filled()
        assertEquals(1f, f.share("never", FieldClass.CHAT), 0f)
        // Nothing was ever typed in a search field, so nothing is expected there.
        assertEquals(1f, f.share("grep", FieldClass.SEARCH), 0f)
        assertEquals(1f, f.factor("grep", FieldClass.CHAT, 0), 0f)
        assertEquals(1f, FieldWords().share("grep", FieldClass.CHAT), 0f)
    }

    @Test fun aFewCommitsSayLittle() {
        val f = FieldWords()
        repeat(2) { f.add("grep", FieldClass.CODE) }
        repeat(2) { f.add("hello", FieldClass.CHAT) }
        // (0 + 2) / (1 + 2)
        assertEquals(2f / 3f, f.share("grep", FieldClass.CHAT), 1e-5f)
    }

    @Test fun survivesStorage() {
        val f = filled()
        val back = FieldWords.parse(f.serialize())
        assertEquals(f.serialize(), back.serialize())
        assertEquals(30, back.count("grep", FieldClass.CODE))
        assertEquals(50, back.total(FieldClass.CHAT))
        assertEquals(f.share("grep", FieldClass.CHAT), back.share("grep", FieldClass.CHAT), 0f)
        assertEquals(0, FieldWords.parse(null).size)
        assertEquals(0, FieldWords.parse("junk,|1|2,a|b|c|d|e|f").size)
    }

    @Test fun forgetsAWord() {
        val f = filled()
        assertTrue(f.remove("Grep"))
        assertEquals(0, f.count("grep", FieldClass.CODE))
        assertEquals(20, f.total(FieldClass.CODE))
        assertTrue(!f.remove("grep"))
    }

    @Test fun staysSmall() {
        val f = FieldWords()
        repeat(3) { f.add("keep", FieldClass.CHAT) }
        for (i in 0 until FieldWords.MAX_WORDS + 10) f.add("w$i", FieldClass.CHAT)
        assertTrue(f.size <= FieldWords.MAX_WORDS)
        assertEquals(3, f.count("keep", FieldClass.CHAT))
        f.add("a,b", FieldClass.CHAT)
        f.add("a|b", FieldClass.CHAT)
        assertEquals(0, f.count("a,b", FieldClass.CHAT))
    }

    @Test fun classesFollowTheEditor() {
        val text = 0x1
        assertEquals(FieldClass.CHAT, FieldClass.of(text or 0x40, 0))
        assertEquals(FieldClass.CHAT, FieldClass.of(text, 4))
        assertEquals(FieldClass.EMAIL, FieldClass.of(text or 0x30, 4))
        assertEquals(FieldClass.EMAIL, FieldClass.of(text or 0x50, 0))
        assertEquals(FieldClass.SEARCH, FieldClass.of(text, 3))
        assertEquals(FieldClass.SEARCH, FieldClass.of(text, 2))
        assertEquals(FieldClass.CODE, FieldClass.of(text or 0x10, 2))
        assertEquals(FieldClass.CODE, FieldClass.of(text or 0x80000, 0))
        assertEquals(FieldClass.OTHER, FieldClass.of(text, 0))
        assertEquals(FieldClass.OTHER, FieldClass.of(0x2, 3))
        assertEquals(FieldClass.CODE, FieldClass.ofKey("code"))
    }

    @Test fun theDecoderCutsTheBoostOnlyWhenSwitchedOn() {
        val dir = Paths.get("src/main/assets/dictionaries")
        assumeTrue(Files.exists(dir.resolve("en_wordlist.txt")))
        val d = Files.newBufferedReader(dir.resolve("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(dir.resolve("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        val g = TestData.qwertyGeometry()
        val tokens = listOf(TestData.sloppySwipe("tones", g, 1000, 500, 0.3f))
        val first = WordPredictor(d.trie, b, g, d.forms).decode(tokens, emptyList())
        assumeTrue(first.size >= 2)
        val word = first[0].word.lowercase()
        val p = WordPredictor(d.trie, b, g, d.forms, mapOf(word to 30))
        val plain = p.decode(tokens, emptyList())
        val f = FieldWords()
        repeat(30) { f.add(word, FieldClass.CODE) }
        repeat(30) { f.add("hello", FieldClass.CHAT) }
        p.fieldWords = f
        p.fieldClass = FieldClass.CHAT
        assertEquals("off: no change", plain.map { it.score }, p.decode(tokens, emptyList()).map { it.score })
        FieldWords.enabled = true
        val out = p.decode(tokens, emptyList())
        val before = plain.first { it.word.lowercase() == word }
        val after = out.first { it.word.lowercase() == word }
        assertEquals(before.score * f.factor(word, FieldClass.CHAT, 30), after.score, before.score * 1e-4f)
        assertTrue(after.terms[ScoreTerms.Term.FIELD] < 0f)
        assertEquals(out.map { it.score }.sortedDescending(), out.map { it.score })
        p.fieldClass = FieldClass.CODE
        assertEquals("at home: no change", plain.map { it.score }, p.decode(tokens, emptyList()).map { it.score })
    }
}
