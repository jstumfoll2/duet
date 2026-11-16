package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class CtcProposerTest {

    private val trie = Trie.build(listOf("cat" to 500, "cot" to 400, "act" to 300, "all" to 300, "ale" to 200, "cattle" to 50))

    /** Frames that each give [sure] to one class (a letter, or '-' for the blank) and share the rest. */
    private fun frames(s: String, sure: Double = 0.9): FloatArray {
        val out = FloatArray(s.length * CtcScorer.CLASSES)
        val rest = ln((1 - sure) / (CtcScorer.CLASSES - 1)).toFloat()
        for ((t, ch) in s.withIndex()) {
            val k = if (ch == '-') 0 else CtcScorer.classOf(Alphabet.codeOf(ch))
            for (c in 0 until CtcScorer.CLASSES) out[t * CtcScorer.CLASSES + c] = if (c == k) ln(sure).toFloat() else rest
        }
        return out
    }

    private fun words(s: String, top: Int = 5): List<String> {
        val lp = frames(s)
        return CtcProposer.propose(trie, lp, s.length, top).map { trie.wordOf(it.node) }
    }

    @Test fun readsTheWordTheFramesSpell() {
        assertEquals("cat", words("cc-aa-tt").first())
        assertEquals("act", words("a-cct").first())
    }

    @Test fun proposesOnlyWordsOfTheLexicon() {
        // "cta" is no word: whatever comes back is one, and each is in the trie.
        val got = words("c-t-a", top = 10)
        assertTrue(got.none { it == "cta" })
        assertTrue(got.all { trie.contains(it) })
    }

    @Test fun aDoubledLetterNeedsABlankBetween() {
        assertEquals("all", words("a-l-l").first())
        // Without the blank the two l frames are one l, and "ale" or "all" must pay for a letter the frames lack.
        val with = CtcProposer.propose(trie, frames("al-l"), 4, 5).first { trie.wordOf(it.node) == "all" }.logP
        val without = CtcProposer.propose(trie, frames("a-ll"), 4, 5).first { trie.wordOf(it.node) == "all" }.logP
        assertTrue(with > without + 1f)
    }

    @Test fun theLanguageModelBreaksANearTie() {
        // The middle frame is as much a as o: the commoner word leads.
        val lp = frames("c-a-t")
        val a = CtcScorer.classOf(Alphabet.codeOf('a')); val o = CtcScorer.classOf(Alphabet.codeOf('o'))
        lp[2 * CtcScorer.CLASSES + a] = ln(0.45).toFloat()
        lp[2 * CtcScorer.CLASSES + o] = ln(0.45).toFloat()
        assertEquals(listOf("cat", "cot"), CtcProposer.propose(trie, lp, 5, 2).map { trie.wordOf(it.node) })
    }

    @Test fun severalStrokesAreReadEndToEnd() {
        val lp = CtcProposer.frames(listOf(frames("c-a-t"), frames("t-l-e")))
        assertEquals("cattle", CtcProposer.propose(trie, lp, 10, 3).first().let { trie.wordOf(it.node) })
    }
}
