package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A traced decode ends with its time and where the time went by stage, so a slow decode on
 * the phone says which stage to look at. The corpus tools read the total before the stages.
 */
class DecodeTimeLineTest {

    private val g = TestData.qwertyGeometry()

    @Test
    fun aTracedDecodeNamesItsStages() {
        val p = listOf(Paths.get("src/main/assets/dictionaries/en_wordlist.txt"), Paths.get("app/src/main/assets/dictionaries/en_wordlist.txt"))
            .firstOrNull { Files.exists(it) }
        assumeTrue("en wordlist not found", p != null)
        val d = Files.newBufferedReader(p!!).use { DictionaryLoader.load(it) }
        val predictor = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = "en")
        // A captured two-thumb `trying`, both thumbs drawing at once.
        val line = "decode in[en]: swipe[LEFT,t=596343189..596343879,keys=t@0-74,r@74-115,e@115-157,s@157-273," +
            "d@273-320,e@320-383,r@383-541,t@541-616,g@616-690,arc=7.83,n=123/5ms] swipe[RIGHT,t=596343488..596343879," +
            "keys=i@0-134,k@134-217,n@217-391,arc=3.15,n=70/5ms] ctx=[]"
        val lines = ArrayList<String>()
        DecodeTrace.sink = { lines.add(it) }
        try {
            predictor.decode(TraceReplay.tokens(line, g), emptyList())
        } finally {
            DecodeTrace.sink = null
        }
        val time = lines.single { it.startsWith("decode time") }
        assertTrue(time, Regex("""decode time\[en]: [0-9.]+ms( [a-z]+ [0-9]+\.[0-9]{2})+""").matches(time))
        val stages = time.substringAfter("ms ").split(" ").chunked(2).map { it[0] }
        assertTrue(time, "il" in stages)
        assertEquals(stages, PhaseClock.NAMES.filter { it in stages })
    }
}
