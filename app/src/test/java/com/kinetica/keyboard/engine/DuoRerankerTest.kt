package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DuoRerankerTest {

    private val g = TestData.qwertyGeometry()
    private var mode = ScoreModel.mode

    @Before fun logLinear() { mode = ScoreModel.mode; ScoreModel.mode = ScoreModel.Mode.LOGLINEAR }
    @After fun restore() { ScoreModel.mode = mode }

    /**
     * A version 2 file whose encoder ignores its input: every weight is zero but the output bias,
     * which makes the blank and [letter] equally likely in every frame and every other class not.
     */
    private fun constantModel(letter: Char): CtcScorer {
        val width = 4; val hidden = 4
        val shapes = listOf(
            intArrayOf(width, DuoFeatures.IN_DIM, 5), intArrayOf(width), intArrayOf(width, width, 5), intArrayOf(width),
            intArrayOf(3 * hidden, width), intArrayOf(3 * hidden, hidden), intArrayOf(3 * hidden), intArrayOf(3 * hidden),
            intArrayOf(3 * hidden, width), intArrayOf(3 * hidden, hidden), intArrayOf(3 * hidden), intArrayOf(3 * hidden),
            intArrayOf(CtcScorer.CLASSES, 2 * hidden), intArrayOf(CtcScorer.CLASSES),
        )
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun float(v: Float) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
        out.write("KCTC".toByteArray(Charsets.US_ASCII))
        int(2); int(shapes.size); float(DuoFeatures.SIGMA_KW); int(DuoFeatures.FRAMES)
        for ((i, s) in shapes.withIndex()) {
            int(s.size); s.forEach { int(it) }
            val n = s.fold(1) { a, b -> a * b }
            for (k in 0 until n) {
                // The output bias: the blank and the one letter likely, every other class not.
                val v = if (i == shapes.size - 1) (if (k == 0 || k == CtcScorer.classOf(Alphabet.codeOf(letter))) 0f else -12f) else 0f
                float(v)
            }
        }
        return CtcScorer.load(ByteArrayInputStream(out.toByteArray()))
    }

    private fun cand(word: String, score: Float) = WordCandidate(word, score, 0.1f, 0.5f, 1f, 0, WordCandidate.Source.SWIPE)

    private fun twoTaps(): List<InputToken> =
        listOf(TestData.tap('a', g, 0L, StreamId.LEFT), TestData.tap('l', g, 200L, StreamId.RIGHT))

    @Test fun theEncodersWordMovesUp() {
        val r = DuoReranker(constantModel('a'), { g }, 0.5f).also { it.taps = true }
        val out = r.rerank(twoTaps(), listOf(cand("l", 0.5f), cand("a", 0.4f)))
        assertEquals(listOf("a", "l"), out.map { it.word })
    }

    @Test fun buffersOfTapsAloneAreLeftAloneByDefault() {
        val list = listOf(cand("l", 0.5f), cand("a", 0.4f))
        assertSame(list, DuoReranker(constantModel('a'), { g }, 0.5f).rerank(twoTaps(), list))
    }

    @Test fun aClearLeaderIsLeftAlone() {
        val r = DuoReranker(constantModel('a'), { g }, 0.5f).also { it.taps = true }
        val list = listOf(cand("l", 0.5f), cand("a", 0.05f))
        assertSame(list, r.rerank(twoTaps(), list))
    }

    @Test fun betaZeroOneStrokeAndTheLegacyScoreChangeNothing() {
        val list = listOf(cand("l", 0.5f), cand("a", 0.4f))
        assertSame(list, DuoReranker(constantModel('a'), { g }, 0f).rerank(twoTaps(), list))
        assertSame(list, DuoReranker(constantModel('a'), { g }, 0.5f).also { it.taps = true }.rerank(twoTaps().take(1), list))
        ScoreModel.mode = ScoreModel.Mode.LEGACY
        assertSame(list, DuoReranker(constantModel('a'), { g }, 0.5f).also { it.taps = true }.rerank(twoTaps(), list))
    }

    @Test fun startedAheadOrNotTheListIsTheSame() {
        val list = listOf(cand("l", 0.5f), cand("a", 0.4f))
        val inline = DuoReranker(constantModel('a'), { g }, 0.5f).also { it.taps = true }.also { it.ahead = false }
        val want = inline.rerank(twoTaps(), list).map { it.word to it.score }
        val ahead = DuoReranker(constantModel('a'), { g }, 0.5f).also { it.taps = true }
        val tokens = twoTaps()
        ahead.prepare(tokens)
        assertEquals(want, ahead.rerank(tokens, list).map { it.word to it.score })
        // The same tokens again reuse the frames; other tokens than the ones prepared get their own.
        assertEquals(want, ahead.rerank(tokens, list).map { it.word to it.score })
        ahead.prepare(twoTaps())
        assertEquals(want, ahead.rerank(tokens, list).map { it.word to it.score })
    }
}
