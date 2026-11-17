package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.ScoreTerms
import com.kinetica.keyboard.engine.models.SwipeToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTemplatesTest {

    private val g = TestData.qwertyGeometry()
    private val layout = ShapeTemplates.layoutOf(g)
    private val dtw = DtwMatcher()

    @After fun off() {
        ShapeTemplates.enabled = false
    }

    private fun path(word: String, sloppy: Float = 0f): FloatArray =
        (if (sloppy == 0f) TestData.swipe(word, g, 0, 400) else TestData.sloppySwipe(word, g, 0, 400, sloppy)).resampled

    @Test fun aWordIsMatchedOnceItHasTwoPaths() {
        var s = ShapeTemplates.empty(layout).with("hello", path("hello"))
        assertEquals(1, s.count("hello"))
        assertEquals(0, s.ready)
        assertTrue(s.nearest(path("hello"), dtw, 0.5f).isEmpty())
        s = s.with("Hello", path("hello"))
        assertEquals(1, s.ready)
        val m = s.nearest(path("hello"), dtw, 0.5f)
        assertEquals(listOf("hello"), m.map { it.word })
        assertTrue(m[0].distance < 0.05f)
    }

    @Test fun aFarPathIsNotAMatch() {
        var s = ShapeTemplates.empty(layout)
        repeat(2) { s = s.with("hello", path("hello")) }
        assertTrue(s.nearest(path("quiz"), dtw, 0.5f).isEmpty())
    }

    @Test fun keepsTheNewestPathsAndTheBusiestWords() {
        var s = ShapeTemplates.empty(layout)
        repeat(ShapeTemplates.PER_WORD + 2) { s = s.with("hello", path("hello")) }
        assertEquals(ShapeTemplates.PER_WORD, s.count("hello"))
        for (i in 0 until ShapeTemplates.MAX_WORDS + 5) s = s.with("w$i", path("was"))
        assertEquals(ShapeTemplates.MAX_WORDS, s.size)
        assertEquals(ShapeTemplates.PER_WORD, s.count("hello"))
    }

    @Test fun takesBackTheNewestPathOrTheWord() {
        var s = ShapeTemplates.empty(layout)
        repeat(2) { s = s.with("hello", path("hello")) }
        assertEquals(1, s.withoutLast("hello").count("hello"))
        assertEquals(0, s.withoutLast("hello").withoutLast("hello").size)
        assertEquals(0, s.without("HELLO").size)
        assertSame(s, s.without("other"))
        assertSame(s, s.withoutLast("other"))
        assertSame(s, s.with("a,b", path("hello")))
        assertSame(s, s.with("hello", FloatArray(4)))
    }

    @Test fun survivesStorageOnItsOwnLayoutOnly() {
        var s = ShapeTemplates.empty(layout)
        repeat(2) { s = s.with("hello", path("hello", 0.2f)) }
        s = s.with("world", path("world"))
        val stored = s.serialize()
        val back = ShapeTemplates.parse(stored, layout)
        assertEquals(stored, back.serialize())
        assertEquals(2, back.count("hello"))
        val probe = path("hello")
        assertEquals(s.nearest(probe, dtw, 1f)[0].distance, back.nearest(probe, dtw, 1f)[0].distance, 0f)
        assertEquals(0, ShapeTemplates.parse(stored, "other").size)
        assertEquals(0, ShapeTemplates.parse(null, layout).size)
        assertEquals(0, ShapeTemplates.parse("$layout,hello|!!!", layout).size)
    }

    @Test fun fitsTheLayoutItWasDrawnOn() {
        val s = ShapeTemplates.empty(layout)
        assertTrue(s.fits(g))
        // A learned touch offset moves keys a little; the layout is still the same one.
        val n = g.letterCount
        assertTrue(s.fits(g.shifted(FloatArray(n) { 0.1f }, FloatArray(n) { -0.1f })))
        assertTrue(!s.fits(g.shifted(FloatArray(n) { 0.5f }, FloatArray(n))))
        assertTrue(!ShapeTemplates.empty("junk").fits(g))
    }

    @Test fun theDecoderScoresAWordByItsOwnPathOnlyWhenSwitchedOn() {
        val p = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)
        val tokens = listOf(TestData.sloppySwipe("something", g, 0, 600, 0.3f))
        val plain = p.decode(tokens, emptyList())
        val before = plain.first { it.word == "something" }
        assertTrue(before.dtwDistance > 0f)
        var s = ShapeTemplates.empty(layout)
        repeat(2) { s = s.with("something", (tokens[0] as SwipeToken).resampled) }
        p.shapeTemplates = s
        assertEquals("off: no change", plain.map { it.score }, p.decode(tokens, emptyList()).map { it.score })
        ShapeTemplates.enabled = true
        val out = p.decode(tokens, emptyList())
        val after = out.first { it.word == "something" }
        assertTrue(after.score > before.score)
        assertTrue(after.terms[ScoreTerms.Term.TEMPLATE] > 0f)
        assertEquals(out.map { it.score }.sortedDescending(), out.map { it.score })
        // Another layout's paths say nothing here.
        p.shapeTemplates = ShapeTemplates.parse(s.serialize().replaceBefore(',', "other"), "other")
        assertEquals("other layout: no change", plain.map { it.score }, p.decode(tokens, emptyList()).map { it.score })
    }
}
