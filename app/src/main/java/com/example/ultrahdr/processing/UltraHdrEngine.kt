package com.example.ultrahdr.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * The Android half of the conversion: decoding photos, turning a [GainMapPlan] into pictures the
 * platform understands, and writing the two kinds of output file.
 */
internal object UltraHdrEngine {

    /**
     * Saving a picture again means holding all of it in memory. Up to this size that is safe, so a
     * 24 MP photo stays whole. A larger photo is fine when its picture is kept as it is
     * ([writeLossless]). Otherwise it is halved for a copy and left alone in place.
     */
    const val MAX_REENCODE_PIXELS = 26_000_000L

    /**
     * A small copy of the photo, exactly as its pixels are stored (no rotation applied), which is
     * the orientation a gain map inside the same file has to use.
     */
    fun decodeSmall(bytes: ByteArray, maxDimension: Int = GainMapSynth.MAX_DIMENSION): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Works out the gain map for [bitmap]. The bitmap is only read. */
    fun plan(bitmap: Bitmap, strength: HdrStrength): GainMapPlan {
        val (w, h) = GainMapSynth.fit(bitmap.width, bitmap.height)
        val scaled = if (w != bitmap.width || h != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return GainMapSynth.plan(pixels, w, h, strength)
    }

    /** The gain map as a grey JPEG, ready to be stored behind the photo. */
    fun gainMapJpeg(plan: GainMapPlan, quality: Int = 90): ByteArray {
        val pixels = IntArray(plan.width * plan.height)
        for (i in pixels.indices) {
            val g = plan.gains[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val bitmap = Bitmap.createBitmap(pixels, plan.width, plan.height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream(64 * 1024)
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Gain map encode failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** The gain map as a grey picture people can look at (bright = boosted). */
    fun gainMapPicture(plan: GainMapPlan): Bitmap {
        val pixels = IntArray(plan.width * plan.height)
        for (i in pixels.indices) {
            val g = plan.gains[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(pixels, plan.width, plan.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * A thumbnail-sized copy of [bitmap] that holds only its broad colours. Stretched over the
     * screen and blurred, it becomes the soft wash behind the preview. Halving step by step keeps
     * the colours true, where one big jump would pick stray pixels.
     */
    fun ambientWash(bitmap: Bitmap, longSide: Int = 48): Bitmap {
        var current = bitmap
        while (maxOf(current.width, current.height) > longSide * 2) {
            val next = Bitmap.createScaledBitmap(
                current, maxOf(1, current.width / 2), maxOf(1, current.height / 2), true
            )
            if (current !== bitmap) current.recycle()
            current = next
        }
        val (w, h) = GainMapSynth.fit(current.width, current.height, longSide)
        val wash = Bitmap.createScaledBitmap(current, w, h, true)
        if (current !== bitmap && current !== wash) current.recycle()
        // The wash is background. It must never glow, even when the photo is HDR already.
        if (wash !== bitmap) runCatching { wash.gainmap = null }
        return wash
    }

    /** Attaches the plan to [bitmap] so the platform draws and encodes it as HDR. */
    fun attach(bitmap: Bitmap, plan: GainMapPlan) {
        val contents = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ALPHA_8)
        val stride = contents.rowBytes
        val buffer = ByteArray(contents.byteCount)
        for (y in 0 until plan.height) {
            System.arraycopy(plan.gains, y * plan.width, buffer, y * stride, plan.width)
        }
        contents.copyPixelsFromBuffer(ByteBuffer.wrap(buffer))

        val ratio = plan.ratioMax
        val offset = UltraHdrContainer.OFFSET
        bitmap.gainmap = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(ratio, ratio, ratio)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(offset, offset, offset)
            setEpsilonHdr(offset, offset, offset)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(ratio)
        }
    }

    /**
     * Writes the original JPEG bytes with the gain map attached. Nothing in the photo is re-encoded.
     * [plan] must come from the picture as its pixels are stored ([decodeSmall]), not turned upright:
     * a gain map inside a file follows the file's own orientation, as camera photos do.
     * Returns false when the file's layout can't take it.
     */
    fun writeLossless(
        jpeg: ByteArray,
        layout: UltraHdrContainer.Layout,
        plan: GainMapPlan,
        out: File
    ): Boolean {
        // The quality version 1.9's gain maps were saved at: the platform encoder uses the photo's own.
        val gainMap = gainMapJpeg(plan, quality = 96)
        val written = BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
            UltraHdrContainer.write(jpeg, layout, gainMap, plan.ratioMax, stream)
        }
        if (!written) out.delete()
        return written
    }

    class Reencoded(val plan: GainMapPlan, val downsized: Boolean)

    /**
     * Decodes [staged] and saves it again as a new Ultra HDR JPEG through the platform encoder.
     * This is version 1.9's conversion, used when the photo's layout can't take [writeLossless].
     */
    fun writeReencoded(staged: File, strength: HdrStrength, out: File, quality: Int = 96): Reencoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(staged.absolutePath, bounds)
        val upright = ImageExifHelper.decodeUprightBitmap(staged, MAX_REENCODE_PIXELS) ?: return null
        var target = upright
        try {
            val plan = plan(upright, strength)
            if (!upright.isMutable) {
                target = upright.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            }
            attach(target, plan)
            val encoded = FileOutputStream(out).use { stream ->
                target.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
            if (!encoded) return null
            ImageExifHelper.copyExifMetadata(staged, out)
            ExifRationals.copyExact(staged.inputStream().use { it.readNBytes(1 shl 20) }, out)
            val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight
            val savedPixels = upright.width.toLong() * upright.height
            return Reencoded(plan, downsized = sourcePixels > 0 && savedPixels < sourcePixels)
        } finally {
            if (target !== upright) target.recycle()
            upright.recycle()
        }
    }

    /**
     * Asks the phone itself whether it can show [file] as an Ultra HDR photo. This is the last
     * check before a finished file replaces anything, so only the decoder's own answer counts:
     * the right labels inside a file prove nothing about whether the phone can read it.
     */
    fun decoderSeesGainMap(file: File): Boolean = try {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        val result = bitmap?.hasGainmap() == true
        bitmap?.recycle()
        result
    } catch (_: Throwable) {
        false
    }

    /** Width times height of the picture in [bytes], or 0 when it can't be read. */
    fun pixelCount(bytes: ByteArray): Long {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth.toLong() * bounds.outHeight
        } else {
            0L
        }
    }

    /** The phone's decoder alone: does it find a gain map in [bytes]? */
    fun decoderSeesGainMap(bytes: ByteArray): Boolean = try {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val result = bitmap?.hasGainmap() == true
        bitmap?.recycle()
        result
    } catch (_: Throwable) {
        false
    }

    /**
     * Every already-HDR check, each named, for skipping: "markers" (the
     * labels anywhere in the file), "decoder" (the phone's decoder) and "layout" (the file holds a
     * second picture described as a gain map). Empty when none of them fires.
     */
    fun hdrChecks(bytes: ByteArray): List<String> = buildList {
        if (ImageExifHelper.looksLikeUltraHdr(bytes)) add("markers")
        if (decoderSeesGainMap(bytes)) add("decoder")
        if (UltraHdrContainer.inspect(bytes) is UltraHdrContainer.Inspection.AlreadyHdr) add("layout")
    }

    /** Is this photo HDR already, by its labels or by the phone's own decoder? Used for skipping. */
    fun hasGainMapBytes(bytes: ByteArray): Boolean = try {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val result = bitmap?.hasGainmap() == true
        bitmap?.recycle()
        result || ImageExifHelper.looksLikeUltraHdr(bytes)
    } catch (_: Throwable) {
        ImageExifHelper.looksLikeUltraHdr(bytes)
    }
}
