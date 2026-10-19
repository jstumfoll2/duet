package com.kinetica.keyboard.ime

import android.content.Context
import android.util.Log
import com.kinetica.keyboard.data.KineticaDb
import java.util.concurrent.Executor

/**
 * The Room side of learning: every write to `user_words` and `user_bigrams`, queued on
 * [dbExecutor] in call order. The service keeps the guards and the live maps; this only
 * persists what it already decided, so a failed write is logged and costs that one row.
 */
internal class LearnedStore(
    private val context: Context,
    private val dbExecutor: Executor,
) {

    /** Adds [amount] (may be negative) to [word]'s count, then runs [then] behind the write. */
    fun addWord(word: String, lang: String, amount: Int, now: Long, failure: String, then: (() -> Unit)? = null) {
        dbExecutor.execute {
            try {
                KineticaDb.get(context).userWords().upsertAdd(word, lang, amount, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, failure, e)
            }
            // Queued behind the write above, failed or not: a reload reads userWords itself,
            // and out of order it would miss the count this call is adding.
            then?.invoke()
        }
    }

    /** Writes [word]'s capital and votes whole; [then] runs only when the write succeeded. */
    fun setCase(word: String, lang: String, display: String?, votes: Int, then: (() -> Unit)? = null) {
        dbExecutor.execute {
            try {
                KineticaDb.get(context).userWords().setCase(word, lang, display, votes)
            } catch (e: RuntimeException) {
                Log.w(TAG, "case vote failed", e)
                return@execute
            }
            then?.invoke()
        }
    }

    /** Adds [delta] (may be negative) to the pair [prev] -> [next] as spelled. */
    fun addPair(prev: String, next: String, lang: String, delta: Int, now: Long, failure: String) {
        dbExecutor.execute {
            try {
                KineticaDb.get(context).userBigrams().upsertAdd(prev, next, lang, delta, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, failure, e)
            }
        }
    }

    /**
     * Takes [amount] off the pair under folded [key]. The live count is every spelling of the
     * pair added up, so the decrement goes to the rows that hold it ([pickPairRows]), not to
     * one spelling, which may not exist and would come back at the next load.
     */
    fun takePair(key: String, lang: String, amount: Int, now: Long) {
        dbExecutor.execute {
            try {
                val dao = KineticaDb.get(context).userBigrams()
                val stored = dao.topN(lang, Int.MAX_VALUE).map { StoredPair(it.prev, it.next, it.count) }
                for ((row, take) in pickPairRows(stored, key, amount)) {
                    dao.upsertAdd(row.prev, row.next, lang, -take, now)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "phrase adjust failed", e)
            }
        }
    }

    private companion object {
        // The service's own tag: these lines were its own before they moved here.
        const val TAG = "KineticaIME"
    }
}
