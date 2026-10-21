package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSearchTest {

    private fun entry(ch: String, name: String, vararg kw: String) = EmojiSuggest.Entry(ch, name, kw.toList())

    private val search = EmojiSearch(
        listOf(
            entry("😀", "grinning face", "happy", "smile"),
            entry("😃", "grinning face big eyes", "happy"),
            entry("🐶", "dog face", "puppy"),
            entry("🍕", "pizza", "food"),
            entry("🥧", "pie", "dessert"),
            entry("❤️", "red heart", "love"),
            entry("💔", "broken heart", "sad"),
            entry("👍", "thumbs up", "yes", "ok"),
            entry("🦮", "guide dog", "blind"),
        ),
    )

    @Test
    fun aPrefixFindsAsItIsTyped() {
        assertEquals(listOf("🍕", "🥧"), search.find("pi"))
        assertEquals(listOf("🍕"), search.find("piz"))
        assertEquals(listOf("👍"), search.find("thu"))
    }

    @Test
    fun aWholeWordBeatsAPrefix() {
        // "pie" is the pie's name and only the start of nothing else.
        assertEquals("🥧", search.find("pie").first())
        assertEquals(listOf("🐶", "🦮"), search.find("dog"))
    }

    @Test
    fun everyWordMustMatch() {
        assertEquals(listOf("❤️", "💔"), search.find("heart"))
        assertEquals(listOf("❤️"), search.find("red heart"))
        assertEquals(listOf("❤️"), search.find("heart red"))
        assertEquals(emptyList<String>(), search.find("red dog"))
    }

    @Test
    fun keywordsCountAndCaseDoesNot() {
        assertEquals(listOf("😀", "😃"), search.find("Happy"))
        assertEquals(listOf("🐶"), search.find("puppy"))
        assertEquals(listOf("🐶", "🦮"), search.find("dogs"))
    }

    @Test
    fun theUsersFavouriteBreaksATie() {
        assertEquals(listOf("😃", "😀"), search.find("happy", mapOf("😃" to 5)))
        // A better match still wins over a favourite.
        assertEquals("🥧", search.find("pie", mapOf("🍕" to 99)).first())
    }

    @Test
    fun aBlankQueryFindsNothing() {
        assertTrue(search.find("").isEmpty())
        assertTrue(search.find("   ").isEmpty())
        assertTrue(search.find("zzz").isEmpty())
    }

    @Test
    fun resultsAreCapped() {
        val many = EmojiSearch(List(100) { entry("e$it", "sun $it", "x") })
        assertEquals(3, many.find("sun", limit = 3).size)
        assertEquals(EmojiSearch.MAX, many.find("sun").size)
    }
}
