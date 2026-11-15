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
 * Converts photos one at a time with version 1.9's gain map. The gain map is stored behind the
 * untouched picture, so colour detail and the photo's own metadata stay exactly as they were. Only
 * a file whose layout can't take that is decoded and saved again, the way version 1.9 did: upright,
 * JPEG at quality 96, EXIF and MPF copied. Either way it is saved in place with version 1.9's writes.
 *
 * With "Update original photos" on, a photo that is HDR already is never touched, a cloud photo
 * becomes a copy, a local photo is updated in place, and anything else is left alone with a reason.
 * There is no fallback copy.
 */
class BatchConverter(
    private val context: Context,
    /** Checks a finished file. The phone's own decoder by default. */
    private val verify: (File) -> Boolean = UltraHdrEngine::decoderSeesGainMap
) {

    suspend fun convertAll(
        items: List<PhotoItem>,
        options: ConvertOptions,
        onStart: (index: Int, item: PhotoItem) -> Unit,
        onResult: (PhotoResult) -> Unit
    ) {
        items.forEachIndexed { index, item ->
            // Stopping is only allowed between photos, never part way through one.
            coroutineContext.ensureActive()
            onStart(index, item)
            // A photo that has been started is always finished and reported, even if Stop is
            // pressed meanwhile. Otherwise a photo could be updated without the result saying so.
            val result = withContext(NonCancellable) { convertOne(item, options) }
            onResult(result)
        }
    }

    suspend fun convertOne(item: PhotoItem, options: ConvertOptions): PhotoResult = withContext(Dispatchers.Default) {
        val workDir = File(context.cacheDir, "work").apply { mkdirs() }
        val stamp = System.nanoTime()
        val out = File(workDir, "out_$stamp.jpg")
        val staged = File(workDir, "in_$stamp")
        val target = item.target
        var trace = Trace(source = MediaStoreSaver.sourceOf(item.uri), realFileFound = target != null)

        fun result(outcome: Outcome, note: String? = null, output: Uri? = null, peak: Float = 1f, kept: Boolean = false) =
            PhotoResult(
                item.uri, item.name, outcome, output = output, keptOriginalPixels = kept,
                note = note, peakBoost = peak, trace = trace
            )

        try {
            // Read from the real file whenever it can be proven to be the picked photo.
            val original = withContext(Dispatchers.IO) {
                MediaStoreSaver.readOriginal(context, item.uri, target)
            } ?: return@withContext result(Outcome.FAILED, "Couldn't open this photo.")
            var bytes = original.bytes
            trace = trace.copy(
                bytesFrom = if (original.fromTarget) "real file" else "picked copy",
                sizeBefore = bytes.size.toLong(),
                metadataBefore = ImageExifHelper.metadataKinds(bytes)
            )

            // 1. HDR already? Checked when the photo was queued and again here, on the bytes in hand.
            val checks = buildList {
                if (item.alreadyHdr) add("queue")
                addAll(UltraHdrEngine.hdrChecks(bytes))
            }
            trace = trace.copy(hdrChecks = checks)
            if (checks.isNotEmpty()) {
                val stripped = UltraHdrContainer.strip(bytes)
                    ?: return@withContext result(Outcome.ALREADY_HDR, PLAIN_HDR_NOTE)
                if (!options.redo) {
                    return@withContext result(
                        Outcome.ALREADY_HDR,
                        "Converted by an earlier version. Turn on Redo photos to convert it again."
                    )
                }
                if (UltraHdrEngine.hdrChecks(stripped).isNotEmpty()) {
                    return@withContext result(Outcome.ALREADY_HDR, "Already HDR underneath, left as it is.")
                }
                bytes = stripped
            }

            // 2. Where it may go, decided before any work is done.
            val isCloud = target == null && MediaStoreSaver.isCloudOrRemoteUri(context, item.uri)
            val inspection = UltraHdrContainer.inspect(bytes)
            val layout = (inspection as? UltraHdrContainer.Inspection.Ready)?.layout?.takeIf { options.lossless }
            val pixels = UltraHdrEngine.pixelCount(bytes)
            val plan = SaveDecision.decide(
                SaveFacts(
                    replaceOriginal = options.replaceOriginal,
                    isCloud = isCloud,
                    hasTarget = target != null,
                    bytesFromTarget = original.fromTarget,
                    canReadLocation = MediaStoreSaver.canReadOriginals(context),
                    isJpeg = UltraHdrContainer.isJpeg(bytes),
                    isMotionPhoto = inspection is UltraHdrContainer.Inspection.Blocked &&
                        inspection.reason == UltraHdrContainer.Blocker.MOTION_PHOTO,
                    pixels = pixels,
                    keepsPicture = layout != null
                )
            )
            if (plan is SavePlan.LeaveAlone) return@withContext result(Outcome.LEFT_ALONE, plan.reason)

            // 3. Nothing bright enough to boost: the file stays exactly as it is.
            val small = UltraHdrEngine.decodeSmall(bytes)
                ?: return@withContext result(Outcome.FAILED, "This file isn't a picture the phone can read.")
            val quick = try {
                UltraHdrEngine.plan(small, options.strength)
            } finally {
                small.recycle()
            }
            if (!quick.hasVisibleEffect) {
                return@withContext result(Outcome.NO_HIGHLIGHTS, "No bright highlights to boost, so it was left as it is.")
            }

            // 4. Dates. A copy carries its capture date with a time zone, so it sits in the same
            //    place in Gallery whether it came from the picker, a share or a download. An updated
            //    photo keeps its last-change time, which is all a WhatsApp photo sorts by. Only when
            //    Android won't let the app put that time back does the date go inside the file.
            val inPlace = plan == SavePlan.InPlace && target != null
            val fileTime = target?.file?.lastModified()?.takeIf { it > 0 }
            val stamp = plan == SavePlan.Copy || (inPlace && !MediaStoreSaver.canKeepFileTime(target!!))
            val stampTime = if (plan == SavePlan.Copy) item.takenAt else item.takenAt ?: fileTime
            fun finish(file: File) {
                if (stamp) ImageExifHelper.stampCaptureDate(file, bytes, stampTime)
            }

            // 5. The picture is kept exactly as it is, with the gain map stored behind it. It is
            //    saved again the way version 1.9 did (upright, JPEG 96, EXIF and MPF) only when its
            //    layout can't take that, or the phone can't read the result.
            var made: Made? = null
            if (layout != null && UltraHdrEngine.writeLossless(bytes, layout, quick, out)) {
                finish(out)
                if (verify(out)) made = Made(quick.peakBoost, kept = true, downsized = false) else out.delete()
            }
            if (made == null) {
                // A copy may come out smaller; an original is never replaced by a smaller picture.
                if (inPlace && pixels > UltraHdrEngine.MAX_REENCODE_PIXELS) {
                    return@withContext result(Outcome.LEFT_ALONE, SaveDecision.TOO_LARGE)
                }
                staged.writeBytes(bytes)
                val encoded = UltraHdrEngine.writeReencoded(staged, options.strength, out)
                if (encoded != null) finish(out)
                if (encoded == null || !verify(out)) {
                    return@withContext result(Outcome.FAILED, "The HDR photo couldn't be made. Nothing was changed.")
                }
                made = Made(encoded.plan.peakBoost, kept = false, downsized = encoded.downsized)
            }
            trace = trace.copy(
                sizeAfter = out.length(),
                metadataAfter = ImageExifHelper.metadataKinds(out),
                picture = if (made.kept) "kept" else "saved again"
            )
            val peak = made.peak
            val takenAt = ImageExifHelper.getCaptureTimestamp(bytes) ?: item.takenAt

            // 6. Save. In place never falls back to a copy.
            if (inPlace) {
                val replaced = MediaStoreSaver.replaceInPlace(
                    context, target!!, out, original.bytes, item.name, upright = !made.kept, fileTime = fileTime
                )
                return@withContext when (replaced) {
                    MediaStoreSaver.InPlace.DONE -> result(
                        Outcome.UPDATED,
                        output = target.mediaUri ?: target.file?.let { Uri.fromFile(it) } ?: item.uri,
                        peak = peak,
                        kept = made.kept
                    )
                    MediaStoreSaver.InPlace.NOT_ALLOWED ->
                        result(Outcome.LEFT_ALONE, "Android didn't allow changing this photo, so it wasn't changed.")
                    MediaStoreSaver.InPlace.NO_BACKUP ->
                        result(Outcome.LEFT_ALONE, "Not enough free space for a safety backup, so it wasn't changed.")
                    MediaStoreSaver.InPlace.FAILED ->
                        result(Outcome.FAILED, "Saving failed. The original was put back, or will be when the app next opens.")
                }
            }

            val base = item.name.substringBeforeLast('.', item.name).ifBlank { "photo" }
            val saved = MediaStoreSaver.saveCopy(
                context = context,
                content = out,
                displayName = "${base}_HDR.jpg",
                captureTime = takenAt
            ).getOrNull() ?: return@withContext result(Outcome.FAILED, "Couldn't save the HDR copy.")
            if (saved.isNew) runCatching { UndoStore.keepCopy(context, saved.uri) }
            // Google Photos hands out a cloud photo it hasn't downloaded as a small copy with no
            // camera details. Nothing in the app can fetch the original, so say how to get it.
            val fromGoogle = trace.source == "picker" || trace.source.startsWith("com.google.android.apps.photos")
            val note = listOfNotNull(
                "Cloud photo, so an HDR copy was saved.".takeIf { isCloud && options.replaceOriginal },
                SMALL_CLOUD_COPY.takeIf { isCloud && fromGoogle && "camera" !in trace.metadataBefore },
                "Saved at a lower resolution.".takeIf { made.downsized }
            ).joinToString(" ").ifEmpty { null }
            result(Outcome.COPIED, note, output = saved.uri, peak = peak, kept = made.kept)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            result(Outcome.FAILED, "Not enough memory for this photo. Nothing was changed.")
        } catch (_: Throwable) {
            result(Outcome.FAILED, "Something went wrong with this photo.")
        } finally {
            out.delete()
            staged.delete()
        }
    }

    /** The finished file: its brightest boost, whether the picture was kept as it was, and whether it shrank. */
    private class Made(val peak: Float, val kept: Boolean, val downsized: Boolean)

    companion object {
        const val SMALL_CLOUD_COPY =
            "Google Photos only sent a smaller copy without camera details. For full quality, download the photo in Google Photos first."
    }
}
