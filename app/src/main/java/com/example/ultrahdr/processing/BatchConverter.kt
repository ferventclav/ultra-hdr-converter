package com.example.ultrahdr.processing

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Converts photos one at a time.
 *
 * The rule that shapes everything here: an original is only ever changed when its picture data can
 * stay byte for byte the same and the phone confirms the result is a real Ultra HDR photo. In every
 * other case the original is left alone and an HDR copy is saved to Pictures/UltraHDR instead.
 */
class BatchConverter(
    private val context: Context,
    /** Checks a finished file. The phone's own decoder by default. */
    private val verify: (File) -> Boolean = UltraHdrEngine::decoderSeesGainMap
) {

    suspend fun convertAll(
        items: List<PhotoItem>,
        replaceOriginal: Boolean,
        strength: HdrStrength,
        onStart: (index: Int, item: PhotoItem) -> Unit,
        onResult: (PhotoResult) -> Unit
    ) {
        items.forEachIndexed { index, item ->
            // Stopping is only allowed between photos, never part way through one.
            coroutineContext.ensureActive()
            onStart(index, item)
            // A photo that has been started is always finished and reported, even if Stop is
            // pressed meanwhile. Otherwise a photo could be updated without the result saying so.
            val result = withContext(NonCancellable) { convertOne(item, replaceOriginal, strength) }
            onResult(result)
        }
    }

    suspend fun convertOne(
        item: PhotoItem,
        replaceOriginal: Boolean,
        strength: HdrStrength
    ): PhotoResult = withContext(Dispatchers.Default) {
        val workDir = File(context.cacheDir, "work").apply { mkdirs() }
        val stamp = System.nanoTime()
        val out = File(workDir, "out_$stamp.jpg")
        val staged = File(workDir, "in_$stamp")

        fun failed(reason: String) = PhotoResult(item.uri, item.name, Outcome.FAILED, note = reason)

        try {
            val target = if (replaceOriginal) item.target else null
            val original = withContext(Dispatchers.IO) {
                MediaStoreSaver.readOriginal(context, item.uri, target)
            } ?: return@withContext failed("Couldn't open this photo.")
            val bytes = original.bytes

            // 1. Leave photos alone that are HDR already.
            if (item.alreadyHdr ||
                ImageExifHelper.looksLikeUltraHdr(bytes, ImageExifHelper.HEADER_SCAN_BYTES) ||
                UltraHdrEngine.hasGainMapBytes(bytes)
            ) {
                return@withContext PhotoResult(item.uri, item.name, Outcome.ALREADY_HDR)
            }
            val inspection = UltraHdrContainer.inspect(bytes)
            if (inspection is UltraHdrContainer.Inspection.AlreadyHdr) {
                return@withContext PhotoResult(item.uri, item.name, Outcome.ALREADY_HDR)
            }

            // 2. Work out the gain map from a small copy of the picture.
            val small = UltraHdrEngine.decodeSmall(bytes)
                ?: return@withContext failed("This file isn't a picture the phone can read.")
            val plan = try {
                if (small.hasGainmap()) {
                    return@withContext PhotoResult(item.uri, item.name, Outcome.ALREADY_HDR)
                }
                UltraHdrEngine.plan(small, strength)
            } finally {
                small.recycle()
            }
            if (!plan.hasVisibleEffect) {
                return@withContext PhotoResult(
                    item.uri, item.name, Outcome.NO_HIGHLIGHTS,
                    note = "No bright highlights to boost, so it was left as it is."
                )
            }

            // 3. Conversion: try lossless injection first, fallback to high-fidelity platform encoding.
            var lossless = false
            var conversionSucceeded = false
            var downsized = false

            if (inspection is UltraHdrContainer.Inspection.Ready) {
                if (UltraHdrEngine.writeLossless(bytes, inspection.layout, plan, out)) {
                    if (verify(out)) {
                        conversionSucceeded = true
                        lossless = true
                    }
                }
            }

            if (!conversionSucceeded) {
                staged.writeBytes(bytes)
                val reencoded = UltraHdrEngine.writeReencoded(staged, strength, out)
                if (reencoded != null && verify(out)) {
                    conversionSucceeded = true
                    downsized = reencoded.downsized
                }
            }

            if (!conversionSucceeded) {
                return@withContext failed("The HDR photo couldn't be created.")
            }

            // 4. Update the original in place when requested, without creating duplicates.
            var note: String? = null
            if (downsized) {
                note = "Saved at a lower resolution."
            }

            if (replaceOriginal && target != null) {
                if (MediaStoreSaver.replaceInPlace(context, target, out, bytes, item.name)) {
                    return@withContext PhotoResult(
                        uri = item.uri,
                        name = item.name,
                        outcome = Outcome.UPDATED,
                        output = target.mediaUri ?: target.file?.let { Uri.fromFile(it) } ?: item.uri,
                        keptOriginalPixels = lossless,
                        note = note,
                        peakBoost = plan.peakBoost
                    )
                }
                note = listOfNotNull(note, "The original couldn't be changed directly, so an HDR copy was saved.").joinToString(" ")
            } else if (replaceOriginal && target == null) {
                note = "The original file couldn't be located on storage, so an HDR copy was saved."
            }

            // 5. Save as a copy only when requested or when in-place update isn't possible.
            val base = item.name.substringBeforeLast('.', item.name).ifBlank { "photo" }
            val saved = MediaStoreSaver.saveCopy(
                context = context,
                content = out,
                displayName = "${base}_HDR.jpg",
                captureTime = ImageExifHelper.getCaptureTimestamp(bytes)
            )
            val savedUri = saved.getOrNull() ?: return@withContext failed("Couldn't save the HDR photo.")
            PhotoResult(
                uri = item.uri,
                name = item.name,
                outcome = Outcome.COPIED,
                output = savedUri,
                keptOriginalPixels = lossless,
                note = note,
                peakBoost = plan.peakBoost
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            failed("Not enough memory for this photo.")
        } catch (_: Throwable) {
            failed("Something went wrong with this photo.")
        } finally {
            out.delete()
            staged.delete()
        }
    }

    private fun whyNotInPlace(reason: UltraHdrContainer.Blocker?): String = when (reason) {
        UltraHdrContainer.Blocker.NOT_JPEG ->
            "Not a JPEG, so the HDR version was saved as a JPEG copy."
        UltraHdrContainer.Blocker.MOTION_PHOTO ->
            "Motion photo. A still HDR copy was saved so the video stays intact."
        else ->
            "This file has an unusual layout, so a copy was saved."
    }
}
