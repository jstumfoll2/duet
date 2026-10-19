package com.kinetica.keyboard.ui

import android.content.res.AssetManager
import android.graphics.Paint
import org.json.JSONObject

/** Reads [EmojiSuggest]'s entries from `emoji_data.json`, as the picker reads its panels. */
object EmojiSuggestAsset {

    /**
     * Every entry this device can draw. Throws on an unreadable or malformed asset; the
     * caller treats that as no suggestions.
     */
    fun load(assets: AssetManager): EmojiSuggest.Index {
        val glyphs = Paint()
        val json = assets.open("emoji_data.json").bufferedReader().use { it.readText() }
        val cats = JSONObject(json).getJSONArray("categories")
        val out = ArrayList<EmojiSuggest.Entry>()
        for (i in 0 until cats.length()) {
            val arr = cats.getJSONObject(i).getJSONArray("emoji")
            for (j in 0 until arr.length()) {
                val e = arr.getJSONObject(j)
                val ch = e.getString("ch")
                if (!glyphs.hasGlyph(ch)) continue
                val kw = e.optJSONArray("kw")
                val keywords = if (kw == null) emptyList() else List(kw.length()) { kw.getString(it) }
                out.add(EmojiSuggest.Entry(ch, e.optString("name", ""), keywords))
            }
        }
        return EmojiSuggest.Index(out)
    }
}
