package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DecodeTrace
import java.util.concurrent.Executor

/**
 * One-time codes from SMS on the suggestion bar, for one field at a time (split out of
 * KineticaIME, #184). It owns the per-field generation, the codes picked this session and the
 * message-store watch, and decides every gate in one place: the setting, the field's kind, the
 * permission, the lock screen (review H1: checked when the field opens, when a read comes back
 * and on each store change), a word in progress, and the field moving on.
 *
 * The keyboard supplies what only it knows through [Host]; the code store is a [Source], so the
 * gates run in a JVM test without a phone.
 */
class CodeOffers(
    private val source: Source,
    private val host: Host,
    /** Where reads of the message store run, off the main thread. */
    private val io: Executor,
    /** The main thread, where every decision and the offer itself happen. */
    private val main: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The message store: [SmsCodes] on a phone. */
    interface Source {
        fun allowed(): Boolean
        fun read(nowMs: Long): List<OneTimeCodes.Code>
        fun watch(callback: () -> Unit)
        fun unwatch()
    }

    /** The keyboard's side. */
    interface Host {
        /** True while the lock screen shows: the keyboard there serves whoever holds the phone. */
        fun locked(): Boolean

        /** True while a word is being typed, which an offer must not displace. */
        fun busy(): Boolean

        /** Whether the field holds no text around the cursor. */
        fun fieldEmpty(): Boolean

        fun show(codes: List<String>)
    }

    // Bumped per field, so a code read for one field is not offered in the next.
    private var generation = 0

    // Codes picked from the bar this session, kept in memory only ([OneTimeCodes.offer]).
    private val used = HashSet<String>()

    /** A field opened: offers its codes now and as messages arrive, when every gate allows. Main thread. */
    fun fieldStarted(kind: OneTimeCodes.Field, enabled: Boolean) {
        val mine = ++generation
        source.unwatch()
        if (!enabled || kind == OneTimeCodes.Field.NEVER || !source.allowed()) return
        if (host.locked()) {
            DecodeTrace.log { "  sms codes refused=locked" }
            return
        }
        val read = {
            io.execute {
                val now = clock()
                val codes = source.read(now)
                main.execute { deliver(mine, kind, codes, now) }
            }
        }
        read()
        source.watch { if (mine == generation && !host.locked()) read() }
    }

    private fun deliver(mine: Int, kind: OneTimeCodes.Field, codes: List<OneTimeCodes.Code>, now: Long) {
        if (mine != generation || codes.isEmpty()) return
        if (host.locked() || host.busy()) return
        val offered = OneTimeCodes.offer(kind, codes, now, used, host.fieldEmpty())
        if (offered.isEmpty()) return
        DecodeTrace.log { "  sms codes n=${offered.size} kind=$kind" }
        host.show(offered.map { it.code })
    }

    /** The field closed: nothing read for it is offered any more. */
    fun fieldFinished() {
        generation++
        source.unwatch()
    }

    /** A code was picked from the bar: a plain field is not offered it again. */
    fun picked(code: String) {
        used.add(code)
    }
}
