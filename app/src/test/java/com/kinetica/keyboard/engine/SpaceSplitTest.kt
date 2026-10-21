package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tapped letters with the space between two words left out (batch 11: `tofeel` three times),
 * against the real English list and pairs.
 */
class SpaceSplitTest {

    private val g = TestData.qwertyGeometry()

    private fun asset(name: String): Path {
        val p = listOf(Paths.get("src/main/assets/dictionaries/$name"), Paths.get("app/src/main/assets/dictionaries/$name"))
            .firstOrNull { Files.exists(it) }
        assumeTrue("$name not found", p != null)
        return p!!
    }

    private fun english(counts: Map<String, Int> = emptyMap()): WordPredictor {
        val d = Files.newBufferedReader(asset("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(asset("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        return WordPredictor(d.trie, b, g, d.forms, language = "en", personalCounts = counts)
    }

    @Test
    fun aCommonPairWithItsSpaceLeftOutSplits() {
        val p = english()
        assertEquals("to" to "feel", p.spaceSplit("tofeel"))
        assertEquals("how" to "to", p.spaceSplit("howto"))
        assertEquals("the case is the user's", "To" to "feel", p.spaceSplit("Tofeel"))
    }

    @Test
    fun wordsAndWeakPairsStay() {
        val p = english()
        assertNull("a word", p.spaceSplit("allow"))
        assertNull("`read me` is a weak pair: readme was meant", p.spaceSplit("readme"))
        assertNull("no bundled pair", p.spaceSplit("pasteor"))
        assertNull("too short", p.spaceSplit("ofa"))
    }

    @Test
    fun lettersTheUserKeepsCommittingAreTheirs() {
        assertNull(english(mapOf("tofeel" to KineticaConstants.PERSONAL_MERGE_MIN_COUNT)).spaceSplit("tofeel"))
    }

    @Test
    fun aReopenedWordIsCutOnlyWhereItEnded() {
        val p = english()
        assertEquals("to" to "feel", p.spaceSplit("tofeel", at = 2))
        assertNull(p.spaceSplit("tofeel", at = 3))
    }

    @Test
    fun aClearPauseSplitsAPairTheListLacks() {
        val p = english()
        // crookedbe, batch 3: 850 ms before the b against a median of about 100.
        val crooked = longArrayOf(0, 134, 68, 92, 108, 16, 126, 850, 67)
        assertNull("no pair without the pause", p.spaceSplit("crookedbe"))
        assertEquals("crooked" to "be", p.spaceSplit("crookedbe", pausesMs = crooked))
        assertEquals("or" to "swipe", p.spaceSplit("orswipe", pausesMs = longArrayOf(0, 8, 1070, 134, 33, 242, 9)))
    }

    @Test
    fun aPauseIsNotEnoughForRarePiecesOrEvenTyping() {
        val p = english()
        assertNull("ty and er are no common words", p.spaceSplit("tyer", pausesMs = longArrayOf(0, 141, 1155, 134)))
        assertNull("no pause stands out", p.spaceSplit("orswipe", pausesMs = longArrayOf(0, 200, 250, 210, 190, 240, 220)))
        assertNull("seeded taps of a reopened word say nothing", p.spaceSplit("orswipe", pausesMs = longArrayOf(0, 0, 900, 0, 0, 0, 0)))
    }
}
