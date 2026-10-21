package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.HarperLints.Edit
import com.kinetica.keyboard.engine.HarperLints.EditKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Harper's results and choosing what the bar offers. */
class HarperLintsTest {

    @Test
    fun readsEveryEditKindAndDropsDuplicates() {
        val encoded = "0\t5\tGrammar\t31\tDid you mean `there`?\tRThere\u001FRThere's\u001FRThere\n" +
            "9\t13\tRepetition\t63\tDoubled word.\tD\n" +
            "20\t24\tPunctuation\t40\tAdd a comma.\tI,"
        val lints = HarperLints.parse(encoded)
        assertEquals(3, lints.size)
        assertEquals(listOf(Edit(EditKind.REPLACE, "There"), Edit(EditKind.REPLACE, "There's")), lints[0].edits)
        assertEquals(EditKind.DELETE, lints[1].edits.single().kind)
        assertEquals(Edit(EditKind.INSERT_AFTER, ","), lints[2].edits.single())
        assertEquals(31, lints[0].priority)
    }

    @Test
    fun malformedRecordsAreSkipped() {
        assertTrue(HarperLints.parse("").isEmpty())
        assertTrue(HarperLints.parse("x\t1\tGrammar\t1\tmsg\tRa").isEmpty())
        assertTrue(HarperLints.parse("5\t2\tGrammar\t1\tmsg\tRa").isEmpty())
        assertTrue(HarperLints.parse("0\t2\tGrammar\t1\tmsg\t").isEmpty())
        assertEquals(1, HarperLints.parse("0\t2\tGrammar\t1\tmsg\tRa\ngarbage").size)
    }

    @Test
    fun aTruncatedTailStartsAtTheFirstWholeWord() {
        assertEquals(0, HarperLints.windowStart("ing words here", truncated = false))
        assertEquals(4, HarperLints.windowStart("ing words here", truncated = true))
    }

    @Test
    fun theSentenceJustClosedIsStillTheCurrentOne() {
        assertEquals(10, HarperLints.currentSentenceStart("It rained. He don't know"))
        assertEquals(10, HarperLints.currentSentenceStart("It rained. He don't know. "))
        assertEquals(0, HarperLints.currentSentenceStart("He don't know."))
        assertEquals(6, HarperLints.currentSentenceStart("Hello\nhe don't"))
        // A decimal point is not a sentence end.
        assertEquals(0, HarperLints.currentSentenceStart("It costs 3.50 now"))
    }

    @Test
    fun offersTheLastUnofferedProblemInTheCurrentSentence() {
        val text = "Their is it. He don't know a apple"
        val earlier = lint(0, 5, "RThere")
        val dont = lint(16, 21, "Rdoesn't")
        val article = lint(27, 28, "Ran")
        val lints = listOf(earlier, dont, article)
        assertEquals(article, HarperLints.pick(lints, text, emptySet(), 0))
        val seen = setOf(HarperLints.key(article, text, 0))
        assertEquals(dont, HarperLints.pick(lints, text, seen, 0))
        assertNull(HarperLints.pick(lints, text, seen + HarperLints.key(dont, text, 0), 0))
    }

    @Test
    fun labelsAndRewrites() {
        assertEquals("✎ doesn't", HarperLints.label(Edit(EditKind.REPLACE, "doesn't"), "don't"))
        assertEquals("✎ −the", HarperLints.label(Edit(EditKind.DELETE, ""), " the"))
        assertEquals("✎ however,", HarperLints.label(Edit(EditKind.INSERT_AFTER, ","), "however"))
        assertEquals("however,", HarperLints.applied(Edit(EditKind.INSERT_AFTER, ","), "however"))
        assertEquals("", HarperLints.applied(Edit(EditKind.DELETE, ""), " the"))
    }

    private fun lint(start: Int, end: Int, edit: String) =
        HarperLints.parse("$start\t$end\tGrammar\t31\tmsg\t$edit").single()
}
