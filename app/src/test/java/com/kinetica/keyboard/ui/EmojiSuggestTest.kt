package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSuggestTest {

    private fun entry(ch: String, name: String, vararg kw: String) = EmojiSuggest.Entry(ch, name, kw.toList())

    private val index = EmojiSuggest.Index(
        listOf(
            entry("😀", "grinning face", "happy", "smile"),
            entry("😃", "grinning face big eyes", "happy"),
            entry("🐶", "dog face", "puppy"),
            entry("🍕", "pizza", "food"),
            entry("😻", "smiling cat heart eyes", "love", "cat"),
            entry("❤️", "red heart", "love"),
        ),
    )

    @Test
    fun aNameOrAKeywordOffersItsEmoji() {
        assertEquals(listOf("🍕"), index.lookup("pizza"))
        assertEquals(listOf("🍕"), index.lookup("Pizza"))
        assertEquals(listOf("😀", "😃"), index.lookup("happy"))
        // "dog face" is the dog.
        assertEquals(listOf("🐶"), index.lookup("dog"))
        assertEquals(emptyList<String>(), index.lookup("face"))
        assertEquals(emptyList<String>(), index.lookup("the"))
    }

    @Test
    fun aPluralFallsBackToItsSingular() {
        assertEquals(listOf("🐶"), index.lookup("dogs"))
        assertEquals(listOf("🍕"), index.lookup("pizzas"))
        assertEquals(emptyList<String>(), index.lookup("s"))
    }

    @Test
    fun theUsersOwnFavouriteComesFirstAndTwoAtMost() {
        assertEquals(listOf("😻", "❤️"), index.lookup("love"))
        assertEquals(listOf("❤️", "😻"), index.lookup("love", mapOf("❤️" to 3)))
        assertEquals(1, index.lookup("love", limit = 1).size)
    }

    @Test
    fun theWordBeforeTheCursorIsTheOneJustTypedOrCommitted() {
        assertEquals("pizza", EmojiSuggest.wordBeforeCursor("I want pizza"))
        assertEquals("pizza", EmojiSuggest.wordBeforeCursor("I want pizza "))
        assertEquals("don't", EmojiSuggest.wordBeforeCursor("I don't"))
        assertEquals("love", EmojiSuggest.wordBeforeCursor("(love "))
        // An emoji already added, two spaces, digits and an empty field offer nothing.
        assertNull(EmojiSuggest.wordBeforeCursor("pizza 🍕 "))
        assertNull(EmojiSuggest.wordBeforeCursor("pizza  "))
        assertNull(EmojiSuggest.wordBeforeCursor("abc123pizza"))
        assertNull(EmojiSuggest.wordBeforeCursor(""))
        // A tail that may have started inside a longer word is no word.
        assertNull(EmojiSuggest.wordBeforeCursor("a".repeat(EmojiSuggest.MAX_WORD + 2)))
    }

    @Test
    fun theShippedAssetOffersTheEverydayWords() {
        val direct = Paths.get("src/main/assets/emoji_data.json")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/emoji_data.json")
        val json = Files.newBufferedReader(p).use { it.readText() }
        // Parsed as text, as EmojiDataTest does: the JVM test runtime stubs org.json.
        val entries = Regex(""""ch"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"([^"]*)"\s*,\s*"kw"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .findAll(json)
            .map { m ->
                val kw = Regex(""""([^"]*)"""").findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
                EmojiSuggest.Entry(m.groupValues[1], m.groupValues[2], kw)
            }.toList()
        assertTrue(entries.size > 400)
        val shipped = EmojiSuggest.Index(entries)
        for (w in listOf("pizza", "love", "happy", "coffee", "fire", "thanks", "dog", "cats")) {
            assertTrue("$w offers nothing", shipped.lookup(w).isNotEmpty())
        }
    }
}
