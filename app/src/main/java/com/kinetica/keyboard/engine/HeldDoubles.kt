package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken

/**
 * A key the thumb held on mid-swipe, read as "this letter is doubled".
 *
 * A doubled letter adds nothing to a swipe's path ("helo" and "hello" draw the same line), so
 * without a signal of its own the choice falls to frequency. Some typists pause on the key to
 * say "twice". The evidence is the hysteresis contact: how long the swipe stayed on a key.
 *
 * Measured on the developer's 2026-10-08 traces (sticky contacts, interior letters, a label's
 * doubled letter against its single letters): a contact of 250 ms or more held on 33% of
 * doubled letters and 1.8% of single ones. Over all four batches it is 17% against 2.3%: the
 * habit is recent and not every double gets it, so a missing hold is no evidence against a
 * double and nothing here penalises one.
 *
 * A swipe's last contact runs to the lift, and the thumb slows into it anyway (median 62 ms on
 * single final letters, 114 ms on doubled ones), so it has its own, shorter threshold. The
 * first contact is the landing and never counts. A key the swipe turns back on needs twice the
 * time: a slow 600 ms "ana" stopped 320 ms on its turn (NonLatinDictionaryTest), and only 4 of
 * the 26 held doubles on 2026-10-08 were turns back. A tap held as long as an interior contact
 * counts too (doubled taps on 2026-10-08: 5% held 250 ms or more, single taps none). A swipe
 * that never leaves its key does not: single letters were held that way as often as doubles.
 * A contact or tap while another token is down may be a thumb parked for the other one, so
 * only the time no other token was down counts ([KineticaConstants.HELD_DOUBLE_FREE_TIME]).
 */
internal object HeldDoubles {

    /** Letter codes held on, in no order; empty for most buffers. */
    fun heldKeys(tokens: List<InputToken>, midMs: Long, endMs: Long, freeTime: Boolean = KineticaConstants.HELD_DOUBLE_FREE_TIME): IntArray {
        var out = IntArray(0)
        for (t in tokens) {
            if (t is TapToken) {
                if (heldTap(tokens, t, midMs, freeTime) && t.code !in out) out += t.code
                continue
            }
            if (t !is SwipeToken) continue
            val cs = t.keyContacts
            val n = cs.size
            for (i in 1 until n) {
                val c = cs[i]
                val last = i == n - 1
                val dur = c.tExit - c.tEnter
                // A turn back the way the swipe came ("ana", "good") slows to a stop on its key
                // whatever the typist meant, so it has to be held twice as long.
                val turnBack = !last && cs[i - 1].code == cs[i + 1].code
                if (!held(tokens, t, c.tEnter, c.tExit, if (last) endMs else if (turnBack) 2 * midMs else midMs, freeTime)) continue
                if (c.code !in out) out += c.code
            }
        }
        return out
    }

    /**
     * Whether tap [t] of [tokens] was held long enough to say "twice", by the rule [heldKeys]
     * applies to taps. A pecked word's letters are its taps as typed, not a decode, so this is
     * what doubles a held tap's letter there ([WordComposer.literal]).
     */
    fun heldTap(tokens: List<InputToken>, t: TapToken, midMs: Long = KineticaConstants.HELD_DOUBLE_MS, freeTime: Boolean = KineticaConstants.HELD_DOUBLE_FREE_TIME): Boolean =
        held(tokens, t, t.tStart, t.tEnd, midMs, freeTime)

    /**
     * Whether [from]..[to] on [t] is a hold of [need] ms. Without [freeTime] any overlap with
     * another token disqualifies it; with it, only the time no other token was down counts.
     */
    private fun held(tokens: List<InputToken>, t: InputToken, from: Long, to: Long, need: Long, freeTime: Boolean): Boolean {
        if (to - from < need) return false
        if (!overlapsOther(tokens, t, from, to)) return true
        if (!freeTime) return false
        return freeMs(tokens, t, from, to) >= need
    }

    /** The part of [from]..[to] during which no token other than [t] was down. */
    private fun freeMs(tokens: List<InputToken>, t: InputToken, from: Long, to: Long): Long {
        val busy = tokens.filter { it !== t && it.tStart < to && it.tEnd > from }
            .map { maxOf(it.tStart, from) to minOf(it.tEnd, to) }.sortedBy { it.first }
        var covered = 0L
        var end = from
        for ((a, b) in busy) {
            val s = maxOf(a, end)
            if (b > s) { covered += b - s; end = b }
        }
        return (to - from) - covered
    }

    private fun overlapsOther(tokens: List<InputToken>, t: InputToken, from: Long, to: Long): Boolean =
        tokens.any { o -> o !== t && o.tStart < to && o.tEnd > from }

    /** True when [codes] has [code] twice in a row somewhere. */
    fun doubles(codes: IntArray, code: Int): Boolean {
        for (i in 1 until codes.size) if (codes[i] == code && codes[i - 1] == code) return true
        return false
    }
}
