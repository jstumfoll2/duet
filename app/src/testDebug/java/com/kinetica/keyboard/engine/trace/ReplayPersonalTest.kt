package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.WordPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `replay --personal BACKUP`: a settings backup's learned words and pairs as the replay's store. */
class ReplayPersonalTest {
    @Test
    fun backupRowsBecomeTheStore() {
        val f = File.createTempFile("backup", ".txt")
        try {
            f.writeText(
                listOf(
                    "kinetica-backup\t1",
                    "pref\tbool\tpref_number_row\ttrue",
                    "word\ten\tHomelab\t7",
                    "word\tes\tvale\t3",
                    "word\ten\tbroken",
                    "phrase\ten\tgood\tmorning\t4",
                    "wcase\ten\thomelab\tHomelab\t2",
                ).joinToString("\n"),
            )
            val l = ReplayCli.personalOf(f)
            assertTrue(l.frozen)
            assertTrue(l.phrases)
            assertEquals(7, l.countsOf("en")["homelab"])
            assertEquals(3, l.countsOf("es")["vale"])
            assertNull(l.countsOf("en")["broken"])
            assertEquals(1, l.countsOf("en").size)
            assertEquals(4, l.pairsOf("en")[WordPredictor.pairKey("good", "morning")])
        } finally {
            f.delete()
        }
    }

    @Test
    fun learnsOnTopOnlyWhenAsked() {
        val f = File.createTempFile("backup", ".txt")
        try {
            f.writeText("word\ten\tgrok\t2\n")
            assertTrue(ReplayCli.personalOf(f).frozen)
            assertTrue(!ReplayCli.personalOf(f, learns = true).frozen)
        } finally {
            f.delete()
        }
    }

    @Test
    fun backupPrefsGiveDownranksAndRetypes() {
        val f = File.createTempFile("backup", ".txt")
        try {
            f.writeText(
                listOf(
                    "pref\tstring\tpref_word_penalties\ten|zorp|2",
                    "pref\tstring\tpref_retype_counts\ten|blip||9,en|blip|blop|3",
                    "pref\tbool\tpref_retype_penalties\ttrue",
                ).joinToString("\n"),
            )
            val l = ReplayCli.personalOf(f)
            assertEquals(2, l.penalties.steps("zorp", "en"))
            assertEquals(3, l.retype!!.count("en", "blip", "blop"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun retypeCountsNeedTheirSwitch() {
        val f = File.createTempFile("backup", ".txt")
        try {
            f.writeText("pref\tstring\tpref_retype_counts\ten|blip|blop|3\npref\tbool\tpref_retype_penalties\tfalse\n")
            assertNull(ReplayCli.personalOf(f).retype)
        } finally {
            f.delete()
        }
    }

    @Test
    fun lastStateLineWins() {
        val a = File.createTempFile("words", ".jsonl")
        val b = File.createTempFile("words", ".jsonl")
        try {
            a.writeText(SwipeTrace.encodeDiscard() + "\n" + SwipeTrace.encodeState("v1;1,0,0,0", null) + "\n")
            b.writeText(SwipeTrace.encodeState("v1;2,0,0,0", "a1;5") + "\n")
            val s = ReplayCli.lastState(listOf(a, b))!!
            assertEquals("v1;2,0,0,0", s.touch)
            assertEquals("a1;5", s.aim)
            assertNull(ReplayCli.lastState(listOf(File.createTempFile("empty", ".jsonl").also { it.deleteOnExit() })))
            // A word export with a state line still reads: the state line is skipped.
            assertEquals(0, ReplayCli.read(a, ReplayReport(50)).size)
        } finally {
            a.delete()
            b.delete()
        }
    }

    @Test
    fun stateLineRoundTrips() {
        val l = SwipeTrace.decodeLine(SwipeTrace.encodeState(null, "a1;3;0,1.0,0.1,0.2"))
        assertEquals(SwipeTrace.Line.State(null, "a1;3;0,1.0,0.1,0.2"), l)
        assertEquals(0, SwipeTrace.wordDelta(SwipeTrace.encodeState("x", "y")))
    }
}
