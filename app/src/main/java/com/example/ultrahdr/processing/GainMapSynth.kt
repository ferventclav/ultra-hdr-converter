package com.example.ultrahdr.processing

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How strong the HDR effect is.
 *
 * [BALANCED] is the look of version 1.9, number for number. The other two bend it: [headroomPower]
 * raises or lowers how bright the highlights may get, and [kneeShift] moves the brightness at which
 * the boost starts (up = fewer pixels are touched).
 */
enum class HdrStrength(val headroomPower: Float, val kneeShift: Float) {
    SUBTLE(headroomPower = 0.62f, kneeShift = 0.04f),
    BALANCED(headroomPower = 1f, kneeShift = 0f),
    VIVID(headroomPower = 1.15f, kneeShift = -0.05f)
}

/**
 * A finished gain map: one byte per pixel (0 = leave as is, 255 = boost by [ratioMax]),
 * plus the numbers the screens show about it.
 */
class GainMapPlan(
    val width: Int,
    val height: Int,
    val gains: ByteArray,
    /** Linear brightness multiplier applied where the map is 255. */
    val ratioMax: Float,
    /** Multiplier the brightest real highlights in this photo actually reach. */
    val peakBoost: Float,
    /** Share of the photo (0..1) that gets a visible boost. */
    val boostedShare: Float
) {
    /** False when the photo has no highlights worth boosting, so converting it would change nothing. */
    val hasVisibleEffect: Boolean get() = peakBoost >= GainMapSynth.MIN_VISIBLE_BOOST
}

/**
 * Works out which parts of a normal photo should glow on an HDR screen.
 *
 * This is the method version 1.9 used, kept exactly, because it is the one that was judged on a
 * real phone: plain brightness decides everything, the boost starts at the photo's own 85th
 * percentile, and it rises steeply so only the top of the range moves. Colour, however saturated,
 * never counts as a highlight. Nothing is blurred.
 *
 * The one addition is [mostlyWhiteGuard], which only acts on pictures that are nearly all white,
 * and only for SUBTLE and VIVID. BALANCED is never changed by it.
 */
object GainMapSynth {

    /** Gain maps are soft, low-detail images. This is plenty and keeps the work fast. */
    const val MAX_DIMENSION = 1280

    /** Below this the eye can't tell the HDR version from the original. */
    const val MIN_VISIBLE_BOOST = 1.15f

    /** The boost never starts below this brightness, which keeps skin and midtones out of it. */
    private const val KNEE_FLOOR = 0.68f

    /** Nor above this, so even a very bright photo keeps a usable top range. */
    private const val KNEE_CEILING = 0.88f

    private const val CURVE = 1.8f

    fun plan(pixels: IntArray, width: Int, height: Int, strength: HdrStrength): GainMapPlan {
        val count = width * height
        require(count > 0 && pixels.size >= count) { "Empty image" }

        // 1. Brightness of every pixel (BT.709, on the stored values) and how it is spread.
        val luma = FloatArray(count)
        val histogram = IntArray(256)
        var maxLuma = 0f
        for (i in 0 until count) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            luma[i] = y
            if (y > maxLuma) maxLuma = y
            histogram[(y * 255f).toInt().coerceIn(0, 255)]++
        }

        // 2. The brightness 85% and 95% of the photo stays under.
        val count85 = (count * 0.85f).toInt()
        val count95 = (count * 0.95f).toInt()
        var seen = 0
        var p85 = 0.70f
        var p95 = 0.85f
        var found85 = false
        for (bin in 0..255) {
            seen += histogram[bin]
            if (!found85 && seen >= count85) {
                p85 = bin / 255f
                found85 = true
            }
            if (seen >= count95) {
                p95 = bin / 255f
                break
            }
        }

        // 3. Where the boost starts, and how far it may go.
        val knee = (max(KNEE_FLOOR, min(p85, KNEE_CEILING)) + strength.kneeShift).coerceIn(0.60f, 0.92f)
        val range = (1f - knee).coerceAtLeast(0.08f)
        val headroom = when {
            maxLuma >= 0.96f && p95 >= 0.88f -> 5.2f // sun, lamps, reflections
            maxLuma >= 0.90f -> 4.2f                 // ordinary well-lit scenes
            else -> 2.8f                             // flat, soft light
        }

        // 4. The map itself.
        val gains = ByteArray(count)
        val gainHistogram = IntArray(256)
        for (i in 0 until count) {
            val y = luma[i]
            val gain = if (y > knee) ((y - knee) / range).pow(CURVE) else 0f
            val level = (gain.coerceIn(0f, 1f) * 255f).toInt()
            gains[i] = level.toByte()
            gainHistogram[level]++
        }

        var strongCount = 0
        for (level in 128..255) strongCount += gainHistogram[level]
        // BALANCED stays exactly version 1.9, documents and screenshots included.
        val ratioMax = if (strength == HdrStrength.BALANCED) {
            headroom
        } else {
            mostlyWhiteGuard(headroom.pow(strength.headroomPower), strongCount.toFloat() / count)
        }

        // 5. The numbers shown to the person.
        val topLevel = percentile(gainHistogram, count, 0.999f)
        val peakBoost = ratioMax.pow(topLevel / 255f)
        // A pixel counts as boosted once it ends up at least 5% brighter.
        val visibleLevel = (255f * ln(1.05f) / ln(ratioMax.coerceAtLeast(1.0001f))).toInt().coerceIn(1, 255)
        var boostedCount = 0
        for (level in visibleLevel..255) boostedCount += gainHistogram[level]

        return GainMapPlan(
            width = width,
            height = height,
            gains = gains,
            ratioMax = ratioMax,
            peakBoost = peakBoost,
            boostedShare = boostedCount.toFloat() / count
        )
    }

    /**
     * Documents, screenshots and whiteboards are white almost everywhere. Boosting all of that makes
     * the whole screen glare, so the boost is eased off once more than 45% of the picture sits near
     * full gain. Ordinary photos, bright skies and snow included, stay well under that and are not
     * touched by this.
     */
    internal fun mostlyWhiteGuard(ratio: Float, strongShare: Float): Float {
        val t = ((strongShare - 0.45f) / 0.30f).coerceIn(0f, 1f)
        if (t <= 0f) return ratio
        val eased = t * t * (3f - 2f * t)
        return 1f + (ratio - 1f) * (1f - 0.75f * eased)
    }

    /**
     * Largest size with the same shape whose long side is at most [maxDimension]. Rounds down,
     * exactly as version 1.9 did, so the gain map has the same size as it had there.
     */
    fun fit(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Pair<Int, Int> {
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(width, height).toFloat())
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /** Value (0..255) below which [fraction] of the samples fall. */
    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val wanted = (total * fraction).toLong().coerceAtLeast(1)
        var seen = 0L
        for (bin in histogram.indices) {
            seen += histogram[bin]
            if (seen >= wanted) return bin
        }
        return histogram.lastIndex
    }
}
