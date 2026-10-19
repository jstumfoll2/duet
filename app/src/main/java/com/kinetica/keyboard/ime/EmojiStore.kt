package com.kinetica.keyboard.ime

import android.content.Context
import android.os.Handler
import android.util.Log
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.ui.EmojiRecents
import com.kinetica.keyboard.ui.EmojiSuggest
import com.kinetica.keyboard.ui.EmojiSuggestAsset
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * The service's emoji state: how often each emoji was picked (for the picker's first tab and
 * the bar's ranking) and the bar's keyword index. Room and the asset are read on [dbExecutor],
 * never on the main thread.
 */
internal class EmojiStore(
    private val context: Context,
    private val dbExecutor: Executor,
    private val mainHandler: Handler,
) {

    /** Resident emoji pick counts, so the picker fills without touching Room. */
    private val uses = ConcurrentHashMap<String, EmojiRecents.Use>()

    /** The bar's emoji keywords, read off the main thread the first time the setting is on. */
    @Volatile private var index: EmojiSuggest.Index? = null
    private var indexRequested = false

    /** The picker's frequently used tab, best first. */
    fun recents(): List<String> = EmojiRecents.ordered(uses.values)

    /**
     * One more pick recorded for [emoji]. Same shape as the service's learnWord: the resident
     * map is updated synchronously so the panel is right the next time it opens, and Room is
     * written on [dbExecutor].
     */
    fun record(emoji: String) {
        val now = System.currentTimeMillis()
        uses.compute(emoji) { _, v -> EmojiRecents.Use(emoji, (v?.count ?: 0) + 1, now) }
        dbExecutor.execute {
            try {
                KineticaDb.get(context).emojiUses().upsertAdd(emoji, 1, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "emoji use not recorded", e)
            }
        }
    }

    /** Seeds the counts once, so opening the picker never reads Room on the main thread. */
    fun reload() {
        dbExecutor.execute {
            val rows = try {
                KineticaDb.get(context).emojiUses().topN(EmojiRecents.MAX)
            } catch (e: RuntimeException) {
                Log.w(TAG, "emoji use table unavailable", e)
                emptyList()
            }
            for (row in rows) {
                uses[row.emoji] = EmojiRecents.Use(row.emoji, row.count, row.updatedAt)
            }
        }
    }

    /** The keyword index, or null until it has loaded; asking starts the load. */
    fun index(onLoaded: () -> Unit): EmojiSuggest.Index? {
        index?.let { return it }
        load(onLoaded)
        return null
    }

    /** Emoji for [word], the user's most picked first. */
    fun lookup(index: EmojiSuggest.Index, word: String): List<String> =
        index.lookup(word, uses.mapValues { it.value.count })

    private fun load(onLoaded: () -> Unit) {
        if (indexRequested) return
        indexRequested = true
        dbExecutor.execute {
            index = try {
                EmojiSuggestAsset.load(context.assets)
            } catch (e: RuntimeException) {
                Log.w(TAG, "emoji keywords unusable", e)
                null
            } catch (e: IOException) {
                Log.w(TAG, "emoji keywords unreadable", e)
                null
            }
            if (index != null) mainHandler.post { onLoaded() }
        }
    }

    private companion object {
        // The service's own tag: these lines were its own before they moved here.
        const val TAG = "KineticaIME"
    }
}
