package com.kinetica.keyboard.engine

import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A contraction typed without its apostrophe corrects to the contraction. The English list held
 * `whats`, `theres` and `cant` beside `what's`, `there's` and `can't`, so the plain letters were
 * a word and were never corrected; `generate_assets.py --apostrophe-cleanup` removes them, and
 * the loader keeps learned copies out.
 */
class ApostropheSpellingTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun predictor(lang: String, learned: List<Pair<String, Int>> = emptyList()): WordPredictor {
        val p = assetPath("${lang}_wordlist.txt")
        assumeTrue("$lang wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it, learned) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), dict.forms)
    }

    private fun tapsCorrectTo(p: WordPredictor, typed: String): String? {
        val g = TestData.qwertyGeometry()
        val tokens = typed.mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = p.decode(tokens, emptyList())
        return p.autocorrectTarget(typed, result, KineticaConstants.AUTOCORRECT_CONF_NORMAL)?.word
    }

    @Test
    fun plainContractionsCorrectToTheContraction() {
        val p = predictor("en")
        for ((typed, word) in listOf(
            "whats" to "what's", "theres" to "there's", "cant" to "can't", "wont" to "won't",
            "dont" to "don't", "didnt" to "didn't", "doesnt" to "doesn't", "heres" to "here's",
        )) {
            assertFalse("$typed is still a word", p.isWord(typed))
            assertEquals(typed, word, tapsCorrectTo(p, typed))
        }
    }

    @Test
    fun commonWordsWithAContractionTwinStay() {
        val p = predictor("en")
        for (w in listOf("its", "lets", "well", "ill", "were", "hell", "shell")) {
            assertTrue("$w was dropped", p.isWord(w))
        }
    }

    @Test
    fun aLearnedPlainContractionIsNotMerged() {
        val p = predictor("en", listOf("cant" to 5000, "dont" to 5000, "proxmox" to 5000))
        assertFalse(p.isWord("cant"))
        assertFalse(p.isWord("dont"))
        assertTrue("an ordinary learned word still merges", p.isWord("proxmox"))
    }

    @Test
    fun aLearnedPlainSpellingTheListHoldsStillMerges() {
        val list = "it's\t100\nits\t10\n"
        val dict = BufferedReader(StringReader(list)).use { DictionaryLoader.load(it, listOf("its" to 50)) }
        assertTrue(dict.trie.nodeFor("its") != -1)
    }

    @Test
    fun englishCliticStemsLeftOtherLanguages() {
        for (lang in listOf("es", "fr", "de", "nl", "pl", "cs")) {
            val p = assetPath("${lang}_wordlist.txt")
            assumeTrue(Files.exists(p))
            val words = Files.readAllLines(p).map { it.substringBefore('\t') }.toSet()
            for (stem in listOf("didn", "doesn", "isn", "couldn", "wouldn")) {
                assertFalse("$lang still holds $stem", stem in words)
            }
        }
    }
}
