package com.example.ultrahdr

import android.graphics.Bitmap
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.UltraHdrEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer

/**
 * BALANCED must be version 1.9, gain byte for gain byte. The reference is version 1.9's own code
 * ([UltraHdrProcessorV19]), run on the painted scenes at three sizes: one under the 1280 limit, one
 * landscape and one portrait above it, so the scaling step is compared too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V19ParityTest {

    private val sizes = listOf(480 to 360, 1920 to 1440, 1000 to 2000)

    @Test
    fun balancedGainMapIsVersion19OnEveryPaintedScene() {
        for ((width, height) in sizes) {
            for (kind in 0 until TestScenes.COUNT) {
                val scene = TestScenes.bitmap(kind, width, height)
                val plan = UltraHdrEngine.plan(scene, HdrStrength.BALANCED)

                val reference = UltraHdrProcessorV19.process(scene.copy(Bitmap.Config.ARGB_8888, true))
                val gainmap = reference.gainmap!!
                val contents = gainmap.gainmapContents
                val label = "scene $kind at ${width}x$height"
                assertEquals("$label width", contents.width, plan.width)
                assertEquals("$label height", contents.height, plan.height)

                val stride = contents.rowBytes
                val raw = ByteArray(contents.byteCount).also { contents.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
                val expected = ByteArray(plan.width * plan.height) { i ->
                    raw[(i / plan.width) * stride + i % plan.width]
                }
                assertArrayEquals("$label gain bytes", expected, plan.gains)
                assertEquals("$label ratioMax", gainmap.ratioMax[0], plan.ratioMax, 0f)
            }
        }
    }

    @Test
    fun attachSetsTheSameNumbersAsVersion19() {
        val scene = TestScenes.bitmap(TestScenes.LAMP, 800, 600)
        val reference = UltraHdrProcessorV19.process(scene.copy(Bitmap.Config.ARGB_8888, true)).gainmap!!

        val ours = scene.copy(Bitmap.Config.ARGB_8888, true)
        UltraHdrEngine.attach(ours, UltraHdrEngine.plan(scene, HdrStrength.BALANCED))
        val mine = ours.gainmap!!

        assertArrayEquals(reference.ratioMin, mine.ratioMin, 0f)
        assertArrayEquals(reference.ratioMax, mine.ratioMax, 0f)
        assertArrayEquals(reference.gamma, mine.gamma, 0f)
        assertArrayEquals(reference.epsilonSdr, mine.epsilonSdr, 0f)
        assertArrayEquals(reference.epsilonHdr, mine.epsilonHdr, 0f)
        assertEquals(reference.displayRatioForFullHdr, mine.displayRatioForFullHdr, 0f)
        assertEquals(reference.minDisplayRatioForHdrTransition, mine.minDisplayRatioForHdrTransition, 0f)
    }
}
