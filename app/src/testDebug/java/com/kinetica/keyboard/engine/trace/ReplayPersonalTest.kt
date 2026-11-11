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
}
