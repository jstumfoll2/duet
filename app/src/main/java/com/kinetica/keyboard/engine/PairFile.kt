package com.kinetica.keyboard.engine

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The binary word-pair table (`<lang>_pairs.bin`, written by tools/build_en_pairs.py): a
 * table too large to ship as text. The English one holds a million pairs from Tatoeba and
 * C4 web text where `en_bigrams.txt` holds 100,000.
 *
 * Layout, big-endian:
 * ```
 * "DPR1"
 * int   words                      at most 65,536
 * words x (UTF, as DataOutput.writeUTF)
 * int   contexts
 * contexts x { u16 previous word, u16 followers - 1, followers x { u16 next word, u8 boost } }
 * ```
 * or, since decoder rework step 3 (#126), "DPR2", the same with log-probabilities for the
 * log-linear score ([ScoreModel]):
 * ```
 * contexts x { u16 previous word, u16 followers - 1, f32 ln C(previous word),
 *              followers x { u16 next word, u8 boost, u8 -ln P(next | previous) in 1/16 nat } }
 * ```
 * where C is the blended count of everything counted after the previous word, before the
 * table's cut, so P is a real conditional probability.
 * Contexts come in rising order of the previous word's number and a context's followers in
 * rising order of theirs. The boost byte is the one [BigramTable.build] computes from counts,
 * `255 * ln(1 + count) / ln(1 + the context's largest count)`, at least 1.
 *
 * The file names its words by spelling, so it does not depend on the order or the content of
 * the word list: a word the trie lacks (blocked by the user, or a replaced list) simply has no
 * pairs.
 */
object PairFile {
    private const val MAGIC = 0x44505231 // "DPR1"
    private const val MAGIC_2 = 0x44505232 // "DPR2"
    private const val MAX_WORDS = 65_536

    @Throws(IOException::class)
    fun read(stream: InputStream, trie: Trie): BigramTable {
        val input = DataInputStream(stream.buffered(1 shl 16))
        val magic = input.readInt()
        if (magic != MAGIC && magic != MAGIC_2) throw IOException("not a pair file")
        val v2 = magic == MAGIC_2
        val width = if (v2) 4 else 3
        val words = input.readInt()
        if (words < 0 || words > MAX_WORDS) throw IOException("pair file: $words words")
        val nodeOf = IntArray(words)
        val indexOfNode = IntArray(trie.nodeCount) { -1 }
        for (i in 0 until words) {
            val node = trie.nodeFor(AccentFolder.fold(input.readUTF()))
            nodeOf[i] = node
            // Two spellings that fold to one word: the first, the more frequent, keeps it.
            if (node >= 0 && node < indexOfNode.size && indexOfNode[node] == -1) indexOfNode[node] = i
        }
        val contexts = input.readInt()
        if (contexts < 0 || contexts > words) throw IOException("pair file: $contexts contexts")
        val start = IntArray(words + 1)
        var next = ShortArray(1 shl 16)
        var boost = ByteArray(1 shl 16)
        var logp = if (v2) ByteArray(1 shl 16) else null
        val lnTotal = if (v2) FloatArray(words) { Float.NaN } else null
        var n = 0
        var last = -1
        val block = ByteArray(MAX_WORDS * 4)
        repeat(contexts) {
            val prev = input.readUnsignedShort()
            val followers = input.readUnsignedShort() + 1
            if (prev <= last || prev >= words) throw IOException("pair file: contexts out of order")
            for (i in last + 1..prev) start[i] = n
            last = prev
            if (lnTotal != null) lnTotal[prev] = input.readFloat()
            if (n + followers > next.size) {
                val size = maxOf(next.size * 2, n + followers)
                next = next.copyOf(size)
                boost = boost.copyOf(size)
                logp = logp?.copyOf(size)
            }
            input.readFully(block, 0, followers * width)
            for (i in 0 until followers) {
                val o = i * width
                next[n] = (((block[o].toInt() and 0xFF) shl 8) or (block[o + 1].toInt() and 0xFF)).toShort()
                boost[n] = block[o + 2]
                logp?.set(n, block[o + 3])
                n++
            }
        }
        for (i in last + 1..words) start[i] = n
        return BigramTable.compact(
            BigramTable.Compact(indexOfNode, nodeOf, start, next.copyOf(n), boost.copyOf(n), logp?.copyOf(n), lnTotal),
        )
    }
}
