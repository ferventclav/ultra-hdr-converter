package com.example.ultrahdr

// Version 1.9's UltraHdrProcessor, copied unchanged from the 13 Nov backup except for this package
// and the object's name. It is the reference the parity test compares GainMapSynth with.

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object UltraHdrProcessorV19 {

    /**
     * Fully automatic, memory-safe SDR to Ultra HDR Gain Map synthesizer.
     * Analyzes image luminance histogram to determine optimal highlight threshold
     * and HDR headroom without requiring manual adjustments.
     *
     * Conforms to ISO 21496-1 standard for gain map metadata.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun process(sourceBitmap: Bitmap): Bitmap {
        val srcWidth = sourceBitmap.width
        val srcHeight = sourceBitmap.height

        // Gain maps in ISO 21496-1 / Google Ultra HDR are low-frequency textures.
        // Cap max dimension to 1280px to guarantee fast execution and prevent OOM on large camera photos.
        val maxDimension = 1280
        val scale = min(1.0f, maxDimension.toFloat() / max(srcWidth, srcHeight).toFloat())
        val gmWidth = max(1, (srcWidth * scale).toInt())
        val gmHeight = max(1, (srcHeight * scale).toInt())

        val scaledSource = if (gmWidth != srcWidth || gmHeight != srcHeight) {
            Bitmap.createScaledBitmap(sourceBitmap, gmWidth, gmHeight, true)
        } else {
            sourceBitmap
        }

        val totalPixels = gmWidth * gmHeight
        val pixels = IntArray(totalPixels)
        scaledSource.getPixels(pixels, 0, gmWidth, 0, 0, gmWidth, gmHeight)

        if (scaledSource != sourceBitmap) {
            scaledSource.recycle()
        }

        // 1. Analyze Luminance Distribution & Build Histogram
        val histogram = IntArray(256)
        val lumaValues = FloatArray(totalPixels)
        var maxLuma = 0f

        for (i in 0 until totalPixels) {
            val color = pixels[i]
            val r = (color shr 16 and 0xFF) / 255f
            val g = (color shr 8 and 0xFF) / 255f
            val b = (color and 0xFF) / 255f

            // ITU-R BT.709 relative luminance
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lumaValues[i] = luma
            if (luma > maxLuma) maxLuma = luma

            val bin = (luma * 255f).toInt().coerceIn(0, 255)
            histogram[bin]++
        }

        // 2. Find Percentile Luminance Cutoffs
        val count95 = (totalPixels * 0.95f).toInt()
        val count85 = (totalPixels * 0.85f).toInt()
        var accumulated = 0
        var p85 = 0.70f
        var p95 = 0.85f

        for (bin in 0..255) {
            accumulated += histogram[bin]
            if (accumulated >= count85 && p85 == 0.70f) {
                p85 = bin / 255f
            }
            if (accumulated >= count95) {
                p95 = bin / 255f
                break
            }
        }

        // 3. Dynamic Calculation of Threshold and Headroom
        // Protect skin tones and midtones: threshold starts at high highlight range
        val threshold = max(0.68f, min(p85, 0.88f))
        val range = (1.0f - threshold).coerceAtLeast(0.08f)

        // Calculate dynamic headroom multiplier:
        // Shots with specular peaks get up to 5.2x boost; flatter shots get a subtle 2.8x boost
        val dynamicHeadroom = when {
            maxLuma >= 0.96f && p95 >= 0.88f -> 5.2f // Strong specular highlights (sun, lights, reflections)
            maxLuma >= 0.90f -> 4.2f                 // Standard outdoor/indoor lit scenes
            else -> 2.8f                             // Low contrast/diffuse lighting
        }

        // 4. Generate Smooth Highlight Gain Map (single-channel ALPHA_8 matches camera Ultra HDR gainmaps)
        val gamma = 1.8f
        val gainmapBitmap = Bitmap.createBitmap(gmWidth, gmHeight, Bitmap.Config.ALPHA_8)
        val rowStride = gainmapBitmap.rowBytes
        val alphaBuffer = ByteArray(gainmapBitmap.byteCount)

        for (y in 0 until gmHeight) {
            val rowSrcOffset = y * gmWidth
            val rowDstOffset = y * rowStride
            for (x in 0 until gmWidth) {
                val luma = lumaValues[rowSrcOffset + x]
                val gainFactor = if (luma > threshold) {
                    val t = (luma - threshold) / range
                    t.pow(gamma)
                } else {
                    0.0f
                }
                alphaBuffer[rowDstOffset + x] = (gainFactor.coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
        }

        gainmapBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(alphaBuffer))

        // 5. Construct Android Gainmap with consistent ISO 21496-1 / Ultra HDR metadata
        // displayRatioForFullHdr must be >= max(ratioMax) per Gainmap / ISO 21496-1 constraints
        val gainmap = Gainmap(gainmapBitmap).apply {
            setRatioMax(dynamicHeadroom, dynamicHeadroom, dynamicHeadroom)
            setRatioMin(1.0f, 1.0f, 1.0f)
            setGamma(1.0f, 1.0f, 1.0f)
            setEpsilonSdr(1f / 64f, 1f / 64f, 1f / 64f)
            setEpsilonHdr(1f / 64f, 1f / 64f, 1f / 64f)
            setDisplayRatioForFullHdr(dynamicHeadroom)
            setMinDisplayRatioForHdrTransition(1.0f)
        }

        // 6. Attach to mutable SDR Bitmap while preserving color space
        val outputBitmap = if (sourceBitmap.isMutable) {
            sourceBitmap
        } else {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        outputBitmap.gainmap = gainmap

        return outputBitmap
    }
}
