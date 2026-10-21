package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Trie.endShare]: how much of the frequency at and below a word is the word itself, the smart
 * autospace delay's question of whether tapped letters are finished.
 */
class EndShareTest {

    private val trie = Trie.build(
        listOf(
            "if" to 9_000, "ifs" to 10,
            "i" to 5_000, "it" to 4_000, "is" to 3_000, "in" to 3_000,
            "th" to 5, "the" to 20_000, "they" to 2_000,
            "to" to 8_000, "too" to 1_000, "top" to 500,
            "zebra" to 40,
        ),
    )

    private fun share(w: String) = trie.endShare(trie.nodeFor(w))

    @Test
    fun aWordFewWordsGrowFromIsNearlyAllItself() {
        assertTrue(share("if") > 0.95f)
    }

    @Test
    fun aWordThatStartsCommonerWordsIsMostlyNot() {
        // 5000 against the 19010 of it, is, in, if and ifs below, give or take the byte
        // quantization.
        assertEquals(0.21f, share("i"), 0.03f)
        assertTrue(share("th") < 0.01f)
    }

    @Test
    fun theMiddleCase() {
        assertEquals(0.84f, share("to"), 0.05f)
    }

    @Test
    fun aLeafIsWhole() {
        assertEquals(1f, share("zebra"), 0f)
        assertEquals(1f, share("they"), 0f)
    }

    @Test
    fun aPrefixThatIsNoWordHasNoShare() {
        assertEquals(0f, trie.endShare(trie.prefixNode("ze")), 0f)
        assertEquals(0f, trie.endShare(-1), 0f)
    }
}
