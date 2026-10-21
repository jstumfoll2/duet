package com.kinetica.keyboard.grammar

import com.kinetica.keyboard.engine.HarperLints
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The real Harper library through its JNI bridge, on a host build of native/harper-jni.
 * CI builds one and names it in DUET_HARPER_LIB; without it these tests skip.
 */
class HarperNativeTest {

    companion object {
        private var handle = 0L

        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getenv("DUET_HARPER_LIB") ?: return
            if (!File(path).isFile) return
            System.load(path)
            handle = HarperNative.create(0)
        }
    }

    private fun lints(text: String): List<HarperLints.Lint> {
        assumeTrue("no host build of libduet_harper", handle != 0L)
        return HarperLints.parse(HarperNative.lint(handle, text))
    }

    @Test
    fun agreementIsFixedWithACorrectSpan() {
        val text = "He don't know."
        val l = lints(text).single()
        assertEquals("don't", text.substring(l.start, l.end))
        assertEquals(HarperLints.Edit(HarperLints.EditKind.REPLACE, "doesn't"), l.edits.first())
    }

    @Test
    fun cleanTextAndSpellingRaiseNothing() {
        assertTrue(lints("I went to the store yesterday.").isEmpty())
        assertTrue(lints("Jasonn typed it.").isEmpty())
    }

    @Test
    fun offsetsAreJavaStringIndices() {
        val text = "😀 I could of done it."
        val l = lints(text).first()
        assertTrue(text.substring(l.start, l.end).startsWith("could"))
    }

    @Test
    fun reportsTheBundledVersion() {
        assumeTrue(handle != 0L)
        assertEquals("2.13.0", HarperNative.version())
    }
}
