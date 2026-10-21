package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EmojiToneTest {

    private val medium = "🏽" // U+1F3FD

    @Test
    fun aHandTakesATone() {
        assertTrue(EmojiTone.supports("👍"))
        assertEquals("👍$medium", EmojiTone.apply("👍", 3))
        assertEquals("👍🏻", EmojiTone.apply("👍", 1))
        assertEquals("👍🏿", EmojiTone.apply("👍", 5))
    }

    @Test
    fun noToneAndEmojiWithoutTonesStayPlain() {
        assertEquals("👍", EmojiTone.apply("👍", EmojiTone.NONE))
        assertFalse(EmojiTone.supports("🍕"))
        assertEquals("🍕", EmojiTone.apply("🍕", 3))
        assertEquals("👍", EmojiTone.apply("👍", 9))
    }

    @Test
    fun theModifierReplacesTheVariationSelector() {
        // ✌️ is U+270C U+FE0F; toned it is U+270C U+1F3FD, with no selector between.
        assertEquals("✌$medium", EmojiTone.apply("✌️", 3))
        assertEquals("✌️", EmojiTone.strip("✌$medium"))
        // ✋ has emoji presentation of its own and never carried a selector.
        assertEquals("✋", EmojiTone.strip("✋$medium"))
    }

    @Test
    fun aToneIsReplacedNotStacked() {
        assertEquals("👍🏿", EmojiTone.apply("👍$medium", 5))
        assertEquals("👍", EmojiTone.apply("👍$medium", EmojiTone.NONE))
    }

    @Test
    fun everyAssetEmojiRoundTrips() {
        val chars = assetChars()
        val bases = chars.filter { EmojiTone.supports(it) }
        assertTrue("the asset should hold dozens of tone-taking emoji, found ${bases.size}", bases.size > 40)
        for (ch in chars) {
            for (tone in EmojiTone.ALL) {
                assertEquals("$ch in tone $tone", ch, EmojiTone.strip(EmojiTone.apply(ch, tone)))
            }
        }
    }

    @Test
    fun aPerEmojiToneWinsOverTheDefault() {
        val c = EmojiTone.Choices(default = 2).withEmoji("👍", 4)
        assertEquals("👍🏾", c.render("👍"))
        assertEquals("👋🏼", c.render("👋"))
        assertEquals("🍕", c.render("🍕"))
        // A toned emoji finds its own remembered tone.
        assertEquals(4, c.toneFor("👍$medium"))
    }

    @Test
    fun choosingTheDefaultsToneForgetsTheEmoji() {
        val c = EmojiTone.Choices(default = 2).withEmoji("👍", 4).withEmoji("👍", 2)
        assertTrue(c.perEmoji.isEmpty())
        assertEquals("👍🏿", c.withDefault(5).render("👍"))
    }

    @Test
    fun choicesSurviveTheirStoredForm() {
        val c = EmojiTone.Choices(default = 1).withEmoji("👍", 4).withEmoji("✌️", 0)
        val back = EmojiTone.decode(c.default, c.encodePerEmoji())
        assertEquals(c.default, back.default)
        assertEquals(c.perEmoji, back.perEmoji)
    }

    @Test
    fun aMalformedStoredFormNeverThrows() {
        val c = EmojiTone.decode(42, "garbage,=3,👍=x,🍕=2,👋=9,👍=4,")
        assertEquals(EmojiTone.NONE, c.default)
        assertEquals(mapOf("👍" to 4), c.perEmoji)
        assertTrue(EmojiTone.decode(0, null).perEmoji.isEmpty())
    }

    @Test
    fun rememberedEmojiAreCapped() {
        var c = EmojiTone.Choices()
        repeat(EmojiTone.MAX_REMEMBERED + 20) { i ->
            // Distinct keys: one base each from a pool, toned differently; the cap is what counts.
            c = c.withEmoji(String(Character.toChars(0x1F466 + i % 19)) + "x$i", 3)
        }
        assertTrue(c.perEmoji.size <= EmojiTone.MAX_REMEMBERED)
    }

    private fun assetChars(): List<String> {
        val direct = Paths.get("src/main/assets/emoji_data.json")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/emoji_data.json")
        assumeTrue("emoji_data.json not found", Files.exists(p))
        val json = Files.newBufferedReader(p).use { it.readText() }
        return Regex(""""ch"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(json).map { it.groupValues[1] }.toList()
    }
}
