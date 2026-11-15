package com.example.ultrahdr.processing

import android.net.Uri
import java.io.File

/** Where a picked photo really lives on the phone, when it does. */
data class PhotoTarget(val file: File?, val mediaUri: Uri?)

/** One photo in the queue. */
data class PhotoItem(
    val uri: Uri,
    val name: String,
    /** False until the background check has looked at the file. */
    val checked: Boolean = false,
    /** Set when the photo is a real file on this phone that can be updated where it is. */
    val target: PhotoTarget? = null,
    val alreadyHdr: Boolean = false,
    /**
     * The HDR in this photo was added by this app's lossless mode, so the untouched original can
     * be taken back out of it and the photo done again.
     */
    val redoable: Boolean = false,
    /** When the gallery or the sharing app says the photo was taken. Used when the file has no date. */
    val takenAt: Long? = null
) {
    val onDevice: Boolean get() = target != null

    /** HDR from a camera or another app. These are never touched. */
    val skippedAsHdr: Boolean get() = alreadyHdr && !redoable
}

enum class Outcome {
    /** The photo in the gallery is now HDR. */
    UPDATED,
    /** An HDR copy was saved to Pictures/UltraHDR. */
    COPIED,
    ALREADY_HDR,
    /** Nothing bright enough to boost, so the file was left alone. */
    NO_HIGHLIGHTS,
    /** Could not be updated safely where it is, so nothing was changed. The note says why. */
    LEFT_ALONE,
    FAILED
}

/** The note a plain already-HDR photo gets. The result card does not repeat it. */
const val PLAIN_HDR_NOTE = "Already HDR, left as it is."

/** The choices that apply to a whole batch. */
data class ConvertOptions(
    val replaceOriginal: Boolean,
    val strength: HdrStrength,
    /**
     * Attach the gain map behind the untouched picture, and only save the picture again when its
     * layout can't take that. False saves every picture again, as version 1.9 did.
     */
    val lossless: Boolean = true,
    /** Take the gain map back out of photos an earlier version added it to, and convert them again. */
    val redo: Boolean = false
)

data class PhotoResult(
    val uri: Uri,
    val name: String,
    val outcome: Outcome,
    /** The HDR photo to open, when there is one. */
    val output: Uri? = null,
    /** True when the original picture data was kept byte for byte. */
    val keptOriginalPixels: Boolean = false,
    /** Plain-language reason: why it was copied, skipped or failed. */
    val note: String? = null,
    val peakBoost: Float = 1f
) {
    val converted: Boolean get() = outcome == Outcome.UPDATED || outcome == Outcome.COPIED
}
