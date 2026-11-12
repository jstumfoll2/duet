package com.kinetica.keyboard.ui

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
    fun accentsFoldAndBroadKeywordsAreDropped() {
        val idx = EmojiSuggest.Index(
            listOf(EmojiSuggest.Entry("❤️", "corazón rojo", emptyList(), listOf("corazón", "amor"))) +
                List(EmojiSuggest.MAX_NAMES_PER_OTHER_WORD) { EmojiSuggest.Entry("f$it", "big face", emptyList(), listOf("face")) },
        )
        assertEquals(listOf("❤️"), idx.lookup("corazon"))
        assertEquals(listOf("❤️"), idx.lookup("Corazón"))
        assertEquals(emptyList<String>(), idx.lookup("face"))
    }

    private fun shipped(vararg langs: String) =
        EmojiSuggest.fromCatalog(EmojiAssetFiles.catalog.entries, langs.map { EmojiAssetFiles.words(it) })

    @Test
    fun theShippedSetOffersTheEverydayWords() {
        val en = shipped("en")
        for (w in listOf("pizza", "love", "happy", "coffee", "fire", "thanks", "dog", "cats")) {
            assertTrue("$w offers nothing", en.lookup(w).isNotEmpty())
        }
        assertEquals("🍕", en.lookup("pizza").first())
        assertEquals("🔥", en.lookup("fire").first())
        // Too generic to offer anything.
        for (w in listOf("the", "face", "hand", "with")) {
            assertEquals("$w should offer nothing", emptyList<String>(), en.lookup(w))
        }
    }

    @Test
    fun spanishWordsOfferTheirEmojiWhenSpanishIsOn() {
        val both = shipped("es", "en")
        for (w in listOf("pizza", "fuego", "amor", "perro", "gato", "café")) {
            assertTrue("$w offers nothing", both.lookup(w).isNotEmpty())
        }
        assertEquals("🔥", both.lookup("fuego").first())
        assertTrue("fuego is not English", shipped("en").lookup("fuego").isEmpty())
    }
}
