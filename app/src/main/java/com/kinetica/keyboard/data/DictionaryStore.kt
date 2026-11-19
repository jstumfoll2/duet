package com.kinetica.keyboard.data

import android.content.Context
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * App-internal storage for imported base dictionaries. An imported (merged)
 * wordlist lives beside a small info JSON describing where it came from; when
 * the wordlist file exists it overrides the bundled asset at load time, and
 * deleting it reverts to the bundled dictionary.
 */
object DictionaryStore {

    data class Info(
        val source: String,
        val words: Int,
        val added: Int,
        val updatedAt: Long,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "dictionaries").apply { mkdirs() }

    fun wordlistOverride(context: Context, lang: String): File =
        File(dir(context), "${lang}_wordlist.txt")

    /**
     * Replaces [lang]'s override with [text] atomically: written to a side file, synced, then
     * renamed over the old one. A kill mid-write leaves the old list (or none), never a
     * truncated one the keyboard would load for good. Throws IOException when it cannot.
     */
    fun writeOverride(context: Context, lang: String, text: String) {
        val target = wordlistOverride(context, lang)
        val side = File(target.parentFile, "${target.name}.part")
        try {
            java.io.FileOutputStream(side).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!side.renameTo(target)) throw java.io.IOException("could not replace ${target.name}")
        } finally {
            side.delete()
        }
    }

    private fun infoFile(context: Context, lang: String): File =
        File(dir(context), "${lang}_info.json")

    fun readInfo(context: Context, lang: String): Info? {
        val f = infoFile(context, lang)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            Info(
                source = o.getString("source"),
                words = o.getInt("words"),
                added = o.optInt("added", 0),
                updatedAt = o.getLong("updatedAt"),
            )
        } catch (e: JSONException) {
            null
        }
    }

    fun writeInfo(context: Context, lang: String, info: Info) {
        infoFile(context, lang).writeText(
            JSONObject()
                .put("source", info.source)
                .put("words", info.words)
                .put("added", info.added)
                .put("updatedAt", info.updatedAt)
                .toString(),
        )
    }

    fun removeOverride(context: Context, lang: String) {
        wordlistOverride(context, lang).delete()
        infoFile(context, lang).delete()
    }
}
