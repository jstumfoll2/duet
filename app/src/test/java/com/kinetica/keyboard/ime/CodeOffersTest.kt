package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Every gate on SMS codes, without a phone (#184; the lock-screen gate is review H1). */
class CodeOffersTest {

    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.poll()!!.run() }
    }

    private class Store(var codes: List<OneTimeCodes.Code>) : CodeOffers.Source {
        var allowed = true
        var watcher: (() -> Unit)? = null
        override fun allowed() = allowed
        override fun read(nowMs: Long) = codes
        override fun watch(callback: () -> Unit) { watcher = callback }
        override fun unwatch() { watcher = null }
    }

    private class Keyboard : CodeOffers.Host {
        var locked = false
        var busy = false
        var empty = true
        val shown = ArrayList<List<String>>()
        override fun locked() = locked
        override fun busy() = busy
        override fun fieldEmpty() = empty
        override fun show(codes: List<String>) { shown.add(codes) }
    }

    private val now = 1_000_000L
    private val io = Queue()
    private val main = Queue()
    private val store = Store(listOf(OneTimeCodes.Code("123456", now - 1_000)))
    private val keyboard = Keyboard()
    private val offers = CodeOffers(store, keyboard, io, main) { now }

    private fun settle() { repeat(3) { io.drain(); main.drain() } }

    @Test
    fun aCodeFieldGetsTheCode() {
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        settle()
        assertEquals(listOf(listOf("123456")), keyboard.shown)
    }

    @Test
    fun nothingOnTheLockScreen() {
        keyboard.locked = true
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        settle()
        assertTrue(keyboard.shown.isEmpty())
        assertEquals(null, store.watcher)
    }

    @Test
    fun aReadThatComesBackAfterTheLockIsDropped() {
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        io.drain()
        keyboard.locked = true
        main.drain()
        assertTrue(keyboard.shown.isEmpty())
    }

    @Test
    fun aMessageWhileLockedReadsNothing() {
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        settle()
        keyboard.locked = true
        store.watcher!!.invoke()
        assertTrue(io.tasks.isEmpty())
        assertEquals(1, keyboard.shown.size)
    }

    @Test
    fun aReadForAClosedFieldIsDropped() {
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        io.drain()
        offers.fieldFinished()
        main.drain()
        assertTrue(keyboard.shown.isEmpty())
    }

    @Test
    fun offTheSettingNeverFieldOrPermissionNothingIsRead() {
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = false)
        offers.fieldStarted(OneTimeCodes.Field.NEVER, enabled = true)
        store.allowed = false
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        assertTrue(io.tasks.isEmpty())
    }

    @Test
    fun aWordInProgressIsNotDisplaced() {
        keyboard.busy = true
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        settle()
        assertTrue(keyboard.shown.isEmpty())
    }

    @Test
    fun aPlainFieldIsNotOfferedACodeAlreadyPicked() {
        offers.picked("123456")
        offers.fieldStarted(OneTimeCodes.Field.MAYBE, enabled = true)
        settle()
        assertTrue(keyboard.shown.isEmpty())
        offers.fieldStarted(OneTimeCodes.Field.CODE, enabled = true)
        settle()
        assertEquals(listOf(listOf("123456")), keyboard.shown)
    }
}
