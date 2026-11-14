package com.example.ultrahdr

import com.example.ultrahdr.processing.GainMapPlan
import com.example.ultrahdr.processing.GainMapSynth
import com.example.ultrahdr.processing.HdrStrength
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The look of the HDR effect, checked on pictures whose right answer is obvious. */
class GainMapSynthTest {

    private val w = 400
    private val h = 300

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun flat(color: Int) = IntArray(w * h) { color }

    /** A dark frame with one bright square, [side] pixels wide, in the middle. */
    private fun spot(background: Int, highlight: Int, side: Int = 40): IntArray {
        val px = flat(background)
        for (y in (h - side) / 2 until (h + side) / 2) {
            for (x in (w - side) / 2 until (w + side) / 2) px[y * w + x] = highlight
        }
        return px
    }

    /** A made-up but photo-like picture: sky ramp on top, darker textured ground, a few lights. */
    private fun scene(seed: Int): IntArray {
        var state = seed * 7919 + 17
        fun noise(): Int {
            state = state * 1103515245 + 12345
            return (state ushr 16) and 0x1F
        }
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                y < h / 3 -> {
                    val v = (150 + 105 * x / w + noise() / 4).coerceAtMost(255)
                    rgb(v - 20 - seed, v - 8, v)
                }
                (x + seed * 31) % 97 < 3 && y % 53 < 3 -> rgb(255, 250, 235)
                else -> {
                    val v = 40 + seed * 9 + (y * 60 / h) + noise()
                    rgb(v + 10, v, v - 10)
                }
            }
        }
    }

    private fun plan(px: IntArray, strength: HdrStrength = HdrStrength.BALANCED): GainMapPlan =
        GainMapSynth.plan(px, w, h, strength)

    private fun gainAt(plan: GainMapPlan, x: Int, y: Int) = plan.gains[y * w + x].toInt() and 0xFF

    /** Version 1.9's gain map, copied from its UltraHdrProcessor so the two can be compared. */
    private class Reference(val gains: ByteArray, val headroom: Float)

    private fun version19(pixels: IntArray): Reference {
        val total = pixels.size
        val histogram = IntArray(256)
        val lumaValues = FloatArray(total)
        var maxLuma = 0f
        for (i in 0 until total) {
            val color = pixels[i]
            val r = (color shr 16 and 0xFF) / 255f
            val g = (color shr 8 and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lumaValues[i] = luma
            if (luma > maxLuma) maxLuma = luma
            histogram[(luma * 255f).toInt().coerceIn(0, 255)]++
        }
        val count95 = (total * 0.95f).toInt()
        val count85 = (total * 0.85f).toInt()
        var accumulated = 0
        var p85 = 0.70f
        var p95 = 0.85f
        for (bin in 0..255) {
            accumulated += histogram[bin]
            if (accumulated >= count85 && p85 == 0.70f) p85 = bin / 255f
            if (accumulated >= count95) {
                p95 = bin / 255f
                break
            }
        }
        val threshold = max(0.68f, min(p85, 0.88f))
        val range = (1.0f - threshold).coerceAtLeast(0.08f)
        val headroom = when {
            maxLuma >= 0.96f && p95 >= 0.88f -> 5.2f
            maxLuma >= 0.90f -> 4.2f
            else -> 2.8f
        }
        val gains = ByteArray(total) { i ->
            val luma = lumaValues[i]
            val factor = if (luma > threshold) ((luma - threshold) / range).pow(1.8f) else 0.0f
            (factor.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        return Reference(gains, headroom)
    }

    @Test
    fun balancedIsVersion19ByteForByte() {
        val pictures = listOf(
            scene(0), scene(1), scene(2), scene(3), scene(4),
            spot(rgb(30, 30, 40), rgb(255, 255, 255)),
            spot(rgb(90, 90, 90), rgb(224, 172, 150), side = 120),
            flat(rgb(128, 128, 128)),
            IntArray(w * h) { i -> ((i % w) * 255 / (w - 1)).let { rgb(it, it, it) } }
        )
        pictures.forEachIndexed { index, px ->
            val expected = version19(px)
            val actual = plan(px)
            assertArrayEquals("gain map of picture $index", expected.gains, actual.gains)
            assertEquals("headroom of picture $index", expected.headroom, actual.ratioMax, 0f)
        }
    }

    @Test
    fun blackFrameIsLeftAlone() {
        val p = plan(flat(rgb(0, 0, 0)))
        assertFalse(p.hasVisibleEffect)
        assertTrue(p.gains.all { it.toInt() == 0 })
    }

    @Test
    fun midGreyFrameIsLeftAlone() {
        val p = plan(flat(rgb(128, 128, 128)))
        assertFalse("A flat grey picture has no highlights", p.hasVisibleEffect)
        assertEquals(0f, p.boostedShare, 0f)
    }

    @Test
    fun smallHighlightGetsTheFullBoost() {
        val p = plan(spot(rgb(30, 30, 40), rgb(255, 255, 255)))
        assertTrue(p.hasVisibleEffect)
        assertEquals("A lit scene with a small highlight", 4.2f, p.ratioMax, 0f)
        assertEquals(255, gainAt(p, w / 2, h / 2))
        assertEquals("The dark surroundings must not move", 0, gainAt(p, 10, 10))
        assertTrue("About 1.3% of the frame is boosted", p.boostedShare in 0.008f..0.03f)
        assertEquals(4.2f, p.peakBoost, 0.01f)
    }

    @Test
    fun headroomFollowsTheScene() {
        // Bright sky over a darker ground: strong highlights.
        val sunny = IntArray(w * h) { i -> if (i / w < h / 5) rgb(255, 255, 255) else rgb(120, 120, 120) }
        assertEquals(5.2f, plan(sunny).ratioMax, 0f)
        // Nothing gets close to white: soft light.
        val dull = spot(rgb(60, 60, 60), rgb(215, 215, 215))
        assertEquals(2.8f, plan(dull).ratioMax, 0f)
    }

    @Test
    fun whitePageOnlyGetsAModestLift() {
        val p = plan(flat(rgb(250, 250, 250)))
        assertTrue("Documents and screenshots must not glare: ${p.ratioMax}", p.ratioMax < 2.2f)
        assertTrue("…and a page of white ends up under 2x: ${p.peakBoost}", p.peakBoost < 1.8f)
    }

    @Test
    fun aBrightSkyIsNotTreatedAsADocument() {
        // 40% of the frame is near white. That is a photo, and it keeps the full version 1.9 boost.
        val px = IntArray(w * h) { i -> if (i / w < h * 2 / 5) rgb(252, 252, 252) else rgb(90, 100, 80) }
        assertEquals(5.2f, plan(px).ratioMax, 0f)
    }

    @Test
    fun strengthsAreOrdered() {
        val px = spot(rgb(60, 60, 70), rgb(255, 250, 240))
        val subtle = plan(px, HdrStrength.SUBTLE)
        val balanced = plan(px, HdrStrength.BALANCED)
        val vivid = plan(px, HdrStrength.VIVID)
        assertTrue(subtle.ratioMax < balanced.ratioMax)
        assertTrue(balanced.ratioMax < vivid.ratioMax)
        assertTrue(subtle.peakBoost < balanced.peakBoost && balanced.peakBoost < vivid.peakBoost)
    }

    @Test
    fun strongerSettingsReachFurtherDownTheRange() {
        val px = scene(2)
        val subtle = plan(px, HdrStrength.SUBTLE)
        val balanced = plan(px, HdrStrength.BALANCED)
        val vivid = plan(px, HdrStrength.VIVID)
        for (i in px.indices) {
            val s = subtle.gains[i].toInt() and 0xFF
            val b = balanced.gains[i].toInt() and 0xFF
            val v = vivid.gains[i].toInt() and 0xFF
            assertTrue("pixel $i: $s <= $b <= $v", s <= b && b <= v)
        }
    }

    @Test
    fun boostRisesSmoothlyWithBrightness() {
        // Left to right ramp from black to white.
        val px = IntArray(w * h) { i ->
            val v = (i % w) * 255 / (w - 1)
            rgb(v, v, v)
        }
        val p = plan(px)
        var previous = 0
        var biggestStep = 0
        for (x in 0 until w) {
            val g = gainAt(p, x, h / 2)
            assertTrue("Gain must never fall as the picture gets brighter (x=$x)", g >= previous)
            biggestStep = maxOf(biggestStep, g - previous)
            previous = g
        }
        assertEquals(0, gainAt(p, w / 2, h / 2))
        assertTrue("The right edge is white and gets boosted", gainAt(p, w - 3, h / 2) > 200)
        assertTrue("No visible bands: largest jump was $biggestStep of 255", biggestStep <= 14)
    }

    @Test
    fun midtonesAndSkinStayPut() {
        // A bright, well exposed skin tone on a neutral background.
        val p = plan(spot(rgb(90, 90, 90), rgb(224, 172, 150), side = 120))
        assertTrue("Skin must not be pushed into HDR", gainAt(p, w / 2, h / 2) < 20)
    }

    @Test
    fun aFaceThatFillsTheFrameIsNotBoostedAtAll() {
        val p = plan(spot(rgb(70, 70, 70), rgb(236, 190, 168), side = 280))
        assertEquals(0, gainAt(p, w / 2, h / 2))
    }

    @Test
    fun strongColourIsNotAHighlight() {
        // A clear blue sky, a red shirt and a neon sign are colourful, not bright.
        val sky = plan(spot(rgb(20, 20, 20), rgb(80, 140, 255), side = 200))
        val shirt = plan(spot(rgb(20, 20, 20), rgb(215, 30, 30)))
        val neon = plan(spot(rgb(20, 20, 20), rgb(255, 40, 200)))
        assertEquals(0, gainAt(sky, w / 2, h / 2))
        assertEquals(0, gainAt(shirt, w / 2, h / 2))
        assertEquals(0, gainAt(neon, w / 2, h / 2))
    }

    @Test
    fun oneHotPixelIsNotAHighlight() {
        val px = flat(rgb(40, 40, 40))
        px[(h / 2) * w + w / 2] = rgb(255, 255, 255)
        assertFalse(plan(px).hasVisibleEffect)
    }

    @Test
    fun fitKeepsTheShape() {
        assertEquals(1280 to 960, GainMapSynth.fit(4000, 3000))
        assertEquals(960 to 1280, GainMapSynth.fit(3000, 4000))
        assertEquals(800 to 600, GainMapSynth.fit(800, 600))
        assertEquals(1280 to 1, GainMapSynth.fit(20000, 10))
    }
}
