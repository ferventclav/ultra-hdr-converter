package com.example.ultrahdr.processing

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.ConcurrentLinkedQueue

/** A photo stored on this phone, as Android's media store lists it. Cloud photos are never in it. */
data class LocalPhoto(
    val id: Long,
    val uri: Uri,
    val album: String,
    /** When it was taken, or last changed when the file has no date. */
    val takenAt: Long,
    /** Last change to the file. A converted photo keeps its own, so [size] tells the two apart. */
    val modified: Long,
    /** File size. A converted photo grows by its gain map, so nothing cached for it is reused. */
    val size: Long = 0
)

/** How much of the photo library Android lets the app see. */
enum class PhotoAccess { ALL, SOME, NONE }

/** The app's own photo grid reads from here, so every photo in it is a real file on the phone. */
object LocalPhotos {

    fun access(context: Context): PhotoAccess {
        fun granted(permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when {
            granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.ALL
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.SOME
            else -> PhotoAccess.NONE
        }
    }

    /** Every JPEG on the phone, newest first. Only JPEGs are converted, so nothing else is listed. */
    fun load(context: Context): List<LocalPhoto> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE
        )
        val photos = ArrayList<LocalPhoto>()
        context.contentResolver.query(
            collection, projection, "${MediaStore.Images.Media.MIME_TYPE} = ?", arrayOf("image/jpeg"), null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val modified = cursor.getLong(modifiedColumn) * 1000
                val taken = cursor.getLong(takenColumn)
                photos += LocalPhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    album = cursor.getString(albumColumn).orEmpty(),
                    takenAt = if (taken > 0) taken else modified,
                    modified = modified,
                    size = cursor.getLong(sizeColumn)
                )
            }
        }
        return photos.sortedByDescending { it.takenAt }
    }

    /** Album tabs after "All": Camera first, then the biggest albums. */
    fun albums(photos: List<LocalPhoto>): List<String> {
        val counts = photos.groupingBy { it.album }.eachCount()
        return counts.keys
            .filter { it.isNotBlank() }
            .sortedWith(
                compareByDescending<String> { it == "Camera" }
                    .thenByDescending { counts.getValue(it) }
                    .thenBy { it.lowercase() }
            )
    }

    /** Shorter names for the folders other apps make. */
    fun albumLabel(album: String): String = when (album) {
        "WhatsApp Images" -> "WhatsApp"
        "Download" -> "Downloads"
        else -> album
    }

    /** Whether the start of the file carries HDR labels. Enough for a badge; conversion checks properly. */
    fun looksHdr(context: Context, uri: Uri): Boolean = runCatching {
        // The grid checks many photos as it scrolls, so the buffers are reused, not made per photo.
        val head = spareHeads.poll() ?: ByteArray(HEAD_BYTES)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var filled = 0
                while (filled < head.size) {
                    val read = input.read(head, filled, head.size - filled)
                    if (read < 0) break
                    filled += read
                }
                ImageExifHelper.looksLikeUltraHdr(head, filled)
            } ?: false
        } finally {
            spareHeads.offer(head)
        }
    }.getOrDefault(false)

    private val spareHeads = ConcurrentLinkedQueue<ByteArray>()

    /** The labels sit in the first few blocks of the file, after EXIF and its thumbnail. */
    private const val HEAD_BYTES = 256 * 1024
}
