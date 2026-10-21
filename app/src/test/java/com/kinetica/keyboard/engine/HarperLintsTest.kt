package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.HarperLints.Edit
import com.kinetica.keyboard.engine.HarperLints.EditKind
import org.junit.Assert.assertEquals
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
    fun marksOfferTheFixesInTextOrder() {
        val text = "He don't know a apple"
        val marks = HarperLints.marks(listOf(lint(14, 15, "Ran"), lint(3, 8, "Rdoesn't\u001FRdon't")), text, 5)
        assertEquals(
            listOf(HarperLints.Mark(3, 5, listOf("doesn't"), "msg"), HarperLints.Mark(14, 1, listOf("an"), "msg")),
            marks,
        )
    }

    @Test
    fun aDeletionIsUnderlinedWithoutASuggestion() {
        val text = "the the cat"
        assertEquals(listOf(HarperLints.Mark(3, 4, emptyList(), "msg")), HarperLints.marks(listOf(lint(3, 7, "D")), text, 5))
        assertEquals("however,", HarperLints.applied(Edit(EditKind.INSERT_AFTER, ","), "however"))
    }

    @Test
    fun marksOutsideTheTextOrOnTheSameSpanAreDropped() {
        val text = "short"
        val marks = HarperLints.marks(listOf(lint(0, 9, "Rx"), lint(0, 5, "Ra"), lint(0, 5, "Rb")), text, 5)
        assertEquals(listOf(HarperLints.Mark(0, 5, listOf("a"), "msg")), marks)
        assertEquals(1, HarperLints.marks(listOf(lint(0, 5, "Ra\u001FRb\u001FRc")), text, 1).single().suggestions.size)
    }

    @Test
    fun theSentenceJustClosedIsStillTheCurrentOne() {
        assertEquals(11, HarperLints.currentSentenceStart("It rained. He don't know"))
        assertEquals(11, HarperLints.currentSentenceStart("It rained. He don't know. "))
        assertEquals(0, HarperLints.currentSentenceStart("He don't know."))
        assertEquals(6, HarperLints.currentSentenceStart("Hello\nhe don't"))
        // A decimal point is not a sentence end.
        assertEquals(0, HarperLints.currentSentenceStart("It costs 3.50 now"))
    }

    @Test
    fun theProofShowsOnlyTheCurrentSentence() {
        val text = "Their is it. He don't know. "
        val marks = listOf(HarperLints.Mark(0, 5, listOf("There")), HarperLints.Mark(16, 5, listOf("doesn't")))
        val proof = HarperLints.proof(text, marks)
        assertEquals("He don't know.", proof.sentence)
        assertEquals(13, proof.start)
        assertEquals(listOf(HarperLints.Mark(3, 5, listOf("doesn't"))), proof.marks)
    }

    @Test
    fun aTruncatedTailStartsAtTheFirstWholeWord() {
        assertEquals(0, HarperLints.windowStart("ing words here", truncated = false))
        assertEquals(4, HarperLints.windowStart("ing words here", truncated = true))
    }

    private fun lint(start: Int, end: Int, edit: String) =
        HarperLints.parse("$start\t$end\tGrammar\t31\tmsg\t$edit").single()
}
