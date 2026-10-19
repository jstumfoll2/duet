package com.kinetica.keyboard.engine

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Words from the phone's user dictionary and contact names ([DictionaryLoader.load]'s
 * vocabulary): decodable from the start, never shadowing a word the lists already spell.
 */
class SystemVocabularyTest {

    private val list = "will\t500000\nmark\t30000\ndon't\t90000\ncafé\t4000\n"

    private fun load(vocabulary: List<String>, blocked: Set<String> = emptySet()) =
        DictionaryLoader.load(BufferedReader(StringReader(list)), blocked = blocked, vocabulary = vocabulary)

    private fun spellings(d: LoadedDictionary, key: String): List<String> {
        val node = d.trie.nodeFor(key)
        if (node == -1 || !d.trie.isWord(node)) return emptyList()
        return d.forms[node]?.map { it.display } ?: listOf(key)
    }

    @Test
    fun aNewNameBecomesAWordWithItsCapital() {
        val d = load(listOf("Siobhan", "proxmox"))
        assertEquals(listOf("Siobhan"), spellings(d, "siobhan"))
        assertEquals(listOf("proxmox"), spellings(d, "proxmox"))
    }

    @Test
    fun aWordTheListHoldsIsLeftAlone() {
        // "Will" in an address book must not put a capital form next to "will".
        val d = load(listOf("Will", "Mark"))
        assertEquals(listOf("will"), spellings(d, "will"))
        assertEquals(listOf("mark"), spellings(d, "mark"))
    }

    @Test
    fun theLearnedWordRulesApply() {
        val d = load(listOf("dont", "cafe", "badword"), blocked = setOf("badword"))
        assertEquals(emptyList<String>(), spellings(d, "dont").filter { it == "dont" })
        assertEquals(listOf("café"), spellings(d, "cafe"))
        assertEquals(emptyList<String>(), spellings(d, "badword"))
    }

    @Test
    fun itRanksLikeAWordTypedAFewTimes() {
        val d = load(listOf("Siobhan"))
        val name = d.trie.frequency(d.trie.nodeFor("siobhan"))
        assertTrue(name > 0)
        assertTrue(name < d.trie.frequency(d.trie.nodeFor("mark")))
    }

    @Test
    fun namesSplitIntoTheirWords() {
        assertEquals(listOf("Mary", "Jane", "O'Brien"), SystemWords.of("Mary-Jane O'Brien", "en"))
        assertEquals(listOf("Bob"), SystemWords.of("Bob 2 (work) 😀 J.", "en").filter { it != "work" })
        assertEquals(listOf("José"), SystemWords.of("José", "es"))
    }
}
