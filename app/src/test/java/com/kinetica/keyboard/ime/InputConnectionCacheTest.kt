package com.kinetica.keyboard.ime

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** The read cache in [InputConnectionHelper] (audit #181): one call into the app per key press. */
class InputConnectionCacheTest {

    private var text = StringBuilder("hello world")
    private var reads = 0
    private var now = 1_000L

    private val ic: InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getTextBeforeCursor" -> {
                reads++
                val n = args[0] as Int
                text.substring(maxOf(0, text.length - n))
            }
            "commitText" -> {
                text.append(args[0] as CharSequence)
                true
            }
            "deleteSurroundingText" -> {
                val n = args[0] as Int
                text.setLength(maxOf(0, text.length - n))
                true
            }
            "beginBatchEdit", "endBatchEdit" -> true
            else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        }
    } as InputConnection

    private val ich = InputConnectionHelper(connection = { ic }, clock = { now })

    @Test
    fun repeatedReadsCostOneCall() {
        assertEquals("d", ich.textBeforeCursor(1).toString())
        assertEquals("world", ich.textBeforeCursor(5).toString())
        assertEquals("hello world", ich.textBeforeCursor(40).toString())
        assertEquals(1, reads)
    }

    @Test
    fun anEditDropsTheCache() {
        ich.textBeforeCursor(5)
        ich.commitText("!")
        assertEquals("orld!", ich.textBeforeCursor(5).toString())
        ich.replaceBeforeCursor(6, "there")
        assertEquals("hello there", ich.textBeforeCursor(11).toString())
        assertEquals(3, reads)
    }

    @Test
    fun anEditByTheAppIsSeenAfterInvalidateOrTheDeadline() {
        ich.textBeforeCursor(5)
        text.append("X")
        // Unreported: served from the cache until the deadline.
        assertEquals("world", ich.textBeforeCursor(5).toString())
        ich.invalidate()
        assertEquals("orldX", ich.textBeforeCursor(5).toString())
        text.append("Y")
        now += InputConnectionHelper.CACHE_MS + 1
        assertEquals("rldXY", ich.textBeforeCursor(5).toString())
    }

    @Test
    fun aLongerReadThanCachedGoesToTheAppUnlessTheFieldStartWasReached() {
        text = StringBuilder("x".repeat(200))
        ich.textBeforeCursor(10)
        ich.textBeforeCursor(150)
        assertEquals(2, reads)
        text = StringBuilder("short")
        ich.invalidate()
        ich.textBeforeCursor(1)
        assertEquals("short", ich.textBeforeCursor(150).toString())
        assertEquals(3, reads)
    }
}
