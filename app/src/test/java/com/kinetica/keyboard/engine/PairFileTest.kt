package com.kinetica.keyboard.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The binary pair table answers exactly as the text table of the same counts does. */
class PairFileTest {

    private val words = listOf("the", "of", "kind", "cat", "sat", "café")
    private val counts = listOf(
        Triple("kind", "of", 900L), Triple("kind", "the", 3L), Triple("the", "cat", 40L),
        Triple("the", "kind", 12L), Triple("cat", "sat", 7L), Triple("the", "café", 5L),
    )

    private fun trie(list: List<String> = words) =
        DictionaryLoader.loadWordlist(StringReader(list.mapIndexed { i, w -> "$w\t${1000 - i}" }.joinToString("\n")).buffered())

    /** The file tools/build_en_pairs.py writes for [counts] over [vocabulary]. */
    private fun file(vocabulary: List<String> = words): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(0x44505231)
        out.writeInt(vocabulary.size)
        vocabulary.forEach { out.writeUTF(it) }
        val contexts = counts.groupBy { vocabulary.indexOf(it.first) }.toSortedMap()
        out.writeInt(contexts.size)
        for ((prev, rows) in contexts) {
            val max = rows.maxOf { it.third }
            out.writeShort(prev)
            out.writeShort(rows.size - 1)
            for (r in rows.sortedBy { vocabulary.indexOf(it.second) }) {
                out.writeShort(vocabulary.indexOf(r.second))
                out.writeByte((255.0 * ln(1.0 + r.third) / ln(1.0 + max)).toInt().coerceIn(1, 255))
            }
        }
        return bytes.toByteArray()
    }

    private fun text(t: Trie) =
        DictionaryLoader.loadBigrams(StringReader(counts.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }).buffered(), t)

    @Test
    fun everyPairReadsAsTheTextTableDoes() {
        val t = trie()
        val binary = PairFile.read(ByteArrayInputStream(file()), t)
        val plain = text(t)
        assertEquals(counts.size, binary.size)
        for (a in words) for (b in words) {
            val p = t.nodeFor(AccentFolder.fold(a))
            val n = t.nodeFor(AccentFolder.fold(b))
            assertEquals("$a $b", plain.boostByte(p, n), binary.boostByte(p, n))
            assertEquals("$a $b", plain.multiplier(p, n), binary.multiplier(p, n), 0f)
        }
        for (a in words) {
            val p = t.nodeFor(AccentFolder.fold(a))
            assertEquals(a, plain.successors(p, 10), binary.successors(p, 10))
        }
        assertEquals(1f, binary.multiplier(-1, t.nodeFor("of")), 0f)
        assertEquals(0, binary.boostByte(t.nodeFor("of"), -1))
    }

    @Test
    fun aWordTheTrieLacksHasNoPairsAndBreaksNothing() {
        // The user blocked `cat`: its pairs are gone, the rest read as before.
        val t = trie(words - "cat")
        val binary = PairFile.read(ByteArrayInputStream(file()), t)
        assertEquals(255, binary.boostByte(t.nodeFor("kind"), t.nodeFor("of")))
        assertTrue(binary.successors(t.nodeFor("the"), 10).none { it.first == -1 })
        assertEquals(2, binary.successors(t.nodeFor("the"), 10).size)
    }

    @Test
    fun behindATextTableOnlyTheBoostReadsIt() {
        val t = trie()
        val narrow = DictionaryLoader.loadBigrams(StringReader("the\tcat\t40\nthe\tkind\t40").buffered(), t)
        val both = narrow.withContext(PairFile.read(ByteArrayInputStream(file()), t))
        val kind = t.nodeFor("kind")
        val of = t.nodeFor("of")
        // The boost sees the wide table's pair; the byte the rules read does not.
        assertEquals(2f, both.multiplier(kind, of), 0f)
        assertEquals(0, both.boostByte(kind, of))
        assertEquals(narrow.successors(t.nodeFor("the"), 10), both.successors(t.nodeFor("the"), 10))
        assertEquals(255, both.boostByte(t.nodeFor("the"), kind))
        assertEquals(2, both.size)
        assertEquals(counts.size, both.contextSize)
    }

    @Test(expected = IOException::class)
    fun anotherFileIsRefused() {
        PairFile.read(ByteArrayInputStream("the\tof\t12\n".toByteArray()), trie())
    }

    @Test
    fun theShippedTableAgreesWithTheShippedListOnItsCommonPairs() {
        val dir = listOf("src/main/assets/dictionaries", "app/src/main/assets/dictionaries").map { Paths.get(it) }.firstOrNull { Files.exists(it) }
        assumeTrue("assets not found", dir != null && Files.exists(dir.resolve("en_pairs.bin")))
        val t = Files.newBufferedReader(dir!!.resolve("en_wordlist.txt")).use { DictionaryLoader.loadWordlist(it) }
        val pairs = Files.newInputStream(dir.resolve("en_pairs.bin")).use { PairFile.read(it, t) }
        assertTrue("pairs ${pairs.size}", pairs.size >= 900_000)
        fun byte(a: String, b: String) = pairs.boostByte(t.nodeFor(a), t.nodeFor(b))
        assertTrue(byte("kind", "of") > 200)
        assertTrue(byte("i", "agree") > 100)
        assertTrue(byte("going", "to") > 200)
    }
}
