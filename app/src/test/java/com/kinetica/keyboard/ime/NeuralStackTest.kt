package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.BoundaryModel
import com.kinetica.keyboard.engine.CtcReranker
import com.kinetica.keyboard.engine.DuoReranker
import com.kinetica.keyboard.engine.LatticeDecoder
import com.kinetica.keyboard.engine.PreviousWordFix
import com.kinetica.keyboard.engine.ScoreModel
import com.kinetica.keyboard.engine.ShapeModel
import com.kinetica.keyboard.engine.TestData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The decoder v3.0 ships in both builds (#81, #167): its models are in the main assets and load. */
class NeuralStackTest {

    private fun asset(name: String): Path {
        val direct = Paths.get("src/main/assets/$name")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/$name")
    }

    @After fun classic() = NeuralStack.applyScore(false)

    @Test fun bothModelsShipInTheMainAssetsAndLoadAsTheirKind() {
        val split = NeuralStack.scorer(Files.readAllBytes(asset(NeuralStack.SPLIT_ASSET)), duo = false)
        val duo = NeuralStack.scorer(Files.readAllBytes(asset(NeuralStack.DUO_ASSET)), duo = true)
        assertNotNull(split)
        assertNotNull(duo)
        // The wrong kind is refused rather than run on the wrong input.
        assertNull(NeuralStack.scorer(Files.readAllBytes(asset(NeuralStack.SPLIT_ASSET)), duo = true))
    }

    @Test fun theRerankerStacksTheTwoThumbModelOnTheOneStrokeModel() {
        val g = TestData.qwertyGeometry()
        val split = NeuralStack.scorer(Files.readAllBytes(asset(NeuralStack.SPLIT_ASSET)), duo = false)
        val duo = NeuralStack.scorer(Files.readAllBytes(asset(NeuralStack.DUO_ASSET)), duo = true)
        assertTrue(NeuralStack.reranker(split, duo, { g }) is DuoReranker)
        assertTrue(NeuralStack.reranker(split, null, { g }) is CtcReranker)
        assertNull(NeuralStack.reranker(null, null, { g }))
    }

    @Test fun oneSwitchTurnsEveryEnginePartOnAndOff() {
        NeuralStack.applyScore(true)
        assertEquals(ScoreModel.Mode.LOGLINEAR, ScoreModel.mode)
        assertTrue(ScoreModel.taps && ShapeModel.speed && PreviousWordFix.enabled && LatticeDecoder.enabled && BoundaryModel.enabled)
        NeuralStack.applyScore(false)
        assertEquals(ScoreModel.Mode.LEGACY, ScoreModel.mode)
        assertTrue(!ScoreModel.taps && !ShapeModel.speed && !PreviousWordFix.enabled && !LatticeDecoder.enabled && !BoundaryModel.enabled)
    }
}
