package com.example.ultrahdr.processing

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** The photo's bytes, and whether they were read from the real file on the phone. */
class OriginalBytes(val bytes: ByteArray, val fromTarget: Boolean)

object MediaStoreSaver {

    /** Photos above this size are not loaded into memory. */
    private const val MAX_FILE_BYTES = 200L * 1024 * 1024

    const val COPY_FOLDER = "UltraHDR"

    private enum class WriteState { DONE_FILE, DONE_MEDIA, UNTOUCHED, BROKEN }

    /** How an in-place update ended. */
    enum class InPlace {
        DONE,
        /** Android did not let the app open the original for writing. Nothing was changed. */
        NOT_ALLOWED,
        /** No room for the safety backup, so the original was not touched. */
        NO_BACKUP,
        /** Writing failed part way. The original was put back, or waits in the backup for the next launch. */
        FAILED
    }

    // ---------------------------------------------------------------------------------------
    // Finding and reading the photo
    // ---------------------------------------------------------------------------------------

    fun isMediaStoreImage(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY &&
            uri.toString().contains("/images/media/")

    /** The file name a person would recognise, such as 20240518_191204.jpg. */
    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * When the gallery, the photo picker or the sharing app says the photo was taken, in epoch
     * milliseconds. Null when the provider doesn't say.
     */
    fun dateTaken(context: Context, uri: Uri): Long? {
        if (uri.scheme == "file") return null
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (index != -1 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
                }
                ?.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    /** The real file behind a picked photo, or null when it is not a local file that can be edited. */
    fun resolveTarget(context: Context, uri: Uri): PhotoTarget? {
        if (uri.scheme == "file") return uri.path?.let { PhotoTarget(File(it), null) }
        embeddedMediaUri(context, uri)?.let { media ->
            return PhotoTarget(getRealPathFromUri(context, media), media)
        }
        if (isCloudOrRemoteUri(context, uri)) return null
        val file = getRealPathFromUri(context, uri)
        val media = resolveMediaStoreUri(context, uri)
        return if (file == null && media == null) null else PhotoTarget(file, media)
    }

    /**
     * Google Photos shares a photo that is on the phone under its own address, with the gallery
     * address written inside it (content%3A%2F%2Fmedia%2F...%2F10866). Returns that gallery address
     * when the gallery still has the photo, so it is treated as local and not as a cloud photo.
     */
    internal fun embeddedMediaUri(context: Context, uri: Uri): Uri? {
        if (uri.authority?.startsWith("com.google.android.apps.photos") != true) return null
        val id = embeddedMediaId(uri.toString()) ?: return null
        val media = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            context.contentResolver.query(media, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { if (it.moveToFirst()) media else null }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun embeddedMediaId(uri: String): Long? =
        Regex("""content://media/[a-z0-9_]+/images/media/(\d+)""")
            .find(Uri.decode(uri))?.groupValues?.get(1)?.toLongOrNull()

    /** Where a picked photo's address came from, for the Copy report. */
    fun sourceOf(uri: Uri): String = when {
        uri.scheme == "file" -> "file"
        uri.authority == MediaStore.AUTHORITY && uri.path.orEmpty().contains("/picker") -> "picker"
        uri.authority == MediaStore.AUTHORITY -> "media"
        else -> uri.authority ?: uri.scheme ?: "unknown"
    }

    /**
     * Without this permission Android hands out photos with the GPS position blanked. Writing such
     * bytes back over an original would erase its location for good, so in-place updates need it.
     */
    fun canReadOriginals(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reads the photo. When [target] is given, the bytes come from the real file itself, and only
     * if that file is provably the picked photo. Otherwise they come from [uri], which is fine for
     * making a copy but must never be written back over an original.
     */
    fun readOriginal(context: Context, uri: Uri, target: PhotoTarget?): OriginalBytes? {
        if (target != null && canReadOriginals(context)) {
            val fromTarget = readTarget(context, target)
            if (fromTarget != null) {
                val isSameUri = uri.scheme == "file" || (isMediaStoreImage(uri) && uri == target.mediaUri)
                if (isSameUri) return OriginalBytes(fromTarget, fromTarget = true)
                val picked = readStream(context, uri)
                if (picked != null && samePhoto(fromTarget, picked)) {
                    return OriginalBytes(fromTarget, fromTarget = true)
                }
                // Same name, different picture. Use what was picked and leave that file alone.
                return picked?.let { OriginalBytes(it, fromTarget = false) }
            }
        }
        return readStream(context, uri)?.let { OriginalBytes(it, fromTarget = false) }
    }

    private fun readTarget(context: Context, target: PhotoTarget): ByteArray? {
        val file = target.file
        if (file != null && file.canRead() && file.length() in 1..MAX_FILE_BYTES) {
            try {
                return file.readBytes()
            } catch (_: Exception) {
            }
        }
        val media = target.mediaUri ?: return null
        return try {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(media))
                ?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readStream(context: Context, uri: Uri): ByteArray? {
        return try {
            val length = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (_: Exception) {
                -1L
            }
            if (length > MAX_FILE_BYTES) return null
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when two files are the same photo. Android may blank the GPS numbers in one of them,
     * which only changes bytes inside the header, so everything from the image data on must match.
     */
    internal fun samePhoto(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        val from = imageDataStart(a)
        for (i in from until a.size) if (a[i] != b[i]) return false
        return true
    }

    /** Offset of the first scan in a JPEG, or 0 when the file is something else. */
    private fun imageDataStart(bytes: ByteArray): Int {
        if (!UltraHdrContainer.isJpeg(bytes)) return 0
        var pos = 2
        while (pos + 4 <= bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) return 0
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) return pos
            val length = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (length < 2) return 0
            pos += 2 + length
        }
        return 0
    }

    internal fun currentSize(context: Context, target: PhotoTarget): Long {
        target.file?.takeIf { it.exists() }?.let { return it.length() }
        val media = target.mediaUri ?: return -1L
        return try {
            context.contentResolver.openAssetFileDescriptor(media, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /**
     * Replaces the photo's contents with [content], keeping the same file, name and gallery entry.
     * The original bytes are parked in [RecoveryStore] until the new file is fully on disk, and are
     * written back straight away if anything goes wrong. Never makes a copy.
     */
    suspend fun replaceInPlace(
        context: Context,
        target: PhotoTarget,
        content: File,
        original: ByteArray,
        name: String
    ): InPlace = withContext(Dispatchers.IO + NonCancellable) {
        val ticket = try {
            RecoveryStore.begin(context, original, name, target, content.length())
        } catch (_: Throwable) {
            return@withContext InPlace.NO_BACKUP
        }
        val state = writeInPlace(context, target) { out ->
            FileInputStream(content).use { it.copyTo(out, 1 shl 16) }
        }
        when (state) {
            WriteState.DONE_FILE, WriteState.DONE_MEDIA -> {
                RecoveryStore.commit(ticket)
                afterWrite(context, target, state)
                InPlace.DONE
            }
            WriteState.UNTOUCHED -> {
                RecoveryStore.commit(ticket)
                InPlace.NOT_ALLOWED
            }
            WriteState.BROKEN -> {
                // Put the original back. If even that fails, the backup waits for the next launch.
                val back = writeInPlace(context, target) { it.write(original) }
                if (back == WriteState.DONE_FILE || back == WriteState.DONE_MEDIA) {
                    RecoveryStore.commit(ticket)
                    afterWrite(context, target, back)
                }
                InPlace.FAILED
            }
        }
    }

    /** Tells the gallery about the new contents, the way version 1.9 did for each kind of write. */
    private fun afterWrite(context: Context, target: PhotoTarget, state: WriteState) {
        if (state == WriteState.DONE_FILE) {
            val path = target.file?.absolutePath ?: return
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg"), null)
        } else {
            val media = target.mediaUri ?: return
            try {
                if (isMediaStoreImage(media)) {
                    val values = ContentValues().apply { put(MediaStore.Images.Media.ORIENTATION, 0) }
                    context.contentResolver.update(media, values, null, null)
                }
            } catch (_: Throwable) {
            }
        }
    }

    internal fun writeBytesInPlace(context: Context, target: PhotoTarget, bytes: ByteArray): Boolean {
        val state = writeInPlace(context, target) { it.write(bytes) }
        if (state == WriteState.DONE_FILE || state == WriteState.DONE_MEDIA) {
            afterWrite(context, target, state)
            return true
        }
        return false
    }

    /**
     * Version 1.9's two in-place writes, the ones proven on the S25: the real file through a plain
     * FileOutputStream when the app may write to it, otherwise the gallery entry through
     * openOutputStream(uri, "wt"). Both keep the same file and the same gallery row.
     */
    private fun writeInPlace(
        context: Context,
        target: PhotoTarget,
        write: (OutputStream) -> Unit
    ): WriteState {
        val file = target.file
        if (file != null && file.canWrite()) {
            val out = try {
                FileOutputStream(file)
            } catch (_: Throwable) {
                null
            }
            if (out != null) {
                return try {
                    out.use {
                        write(it)
                        it.fd.sync()
                    }
                    WriteState.DONE_FILE
                } catch (_: Throwable) {
                    WriteState.BROKEN
                }
            }
        }
        val media = target.mediaUri ?: return WriteState.UNTOUCHED
        val out = try {
            context.contentResolver.openOutputStream(media, "wt")
        } catch (_: Throwable) {
            null
        } ?: return WriteState.UNTOUCHED
        return try {
            out.use { write(it) }
            WriteState.DONE_MEDIA
        } catch (_: Throwable) {
            WriteState.BROKEN
        }
    }

    private fun getAbsolutePathFromUri(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Saves [content] as a new photo in Pictures/UltraHDR. */
    suspend fun saveCopy(
        context: Context,
        content: File,
        displayName: String,
        captureTime: Long?
    ): Result<Uri> = withContext(Dispatchers.IO + NonCancellable) {
        val resolver = context.contentResolver
        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (captureTime != null && captureTime > 0) {
                    put(MediaStore.Images.Media.DATE_TAKEN, captureTime)
                }
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + COPY_FOLDER
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val imageUri = resolver.insert(collection, values)
                ?: throw IOException("Failed to create a gallery entry for $safeName")
            try {
                FileInputStream(content).use { input ->
                    resolver.openOutputStream(imageUri).use { output ->
                        if (output == null) throw IOException("Failed to open the new photo for writing")
                        input.copyTo(output, 1 shl 16)
                    }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, values, null, null)
                Result.success(imageUri)
            } catch (e: Throwable) {
                resolver.delete(imageUri, null, null)
                throw e
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Working out where a picked photo lives
    // ---------------------------------------------------------------------------------------

    /**
     * Checks if a photo is an online/cloud copy (e.g. from Google Photos or cloud provider)
     * that does not have an in-place local file on storage.
     */
    fun isCloudOrRemoteUri(context: Context, uri: Uri): Boolean {
        val uriStr = uri.toString().lowercase()
        // Explicit Google Photos authorities
        if (uriStr.contains("com.google.android.apps.photos") ||
            uriStr.contains("com.google.android.apps.photos.contentprovider") ||
            uriStr.contains("google_photos")) {
            return true
        }

        if (uri.scheme == "file") return false

        // Check if a real physical local file exists on disk
        val localFile = getRealPathFromUri(context, uri)
        if (localFile != null && localFile.exists()) {
            return false
        }

        // Check if it resolves to a MediaStore entry
        val mediaStoreUri = resolveMediaStoreUri(context, uri)
        return mediaStoreUri == null
    }

    /**
     * Finds the real physical file on storage for a given content or MediaStore URI.
     */
    fun getRealPathFromUri(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it) }
        }

        // 1. If it's already a MediaStore URI, query DATA column directly
        if (uri.toString().contains("media/external/images/media")) {
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (dataIdx != -1) {
                            val path = cursor.getString(dataIdx)
                            if (!path.isNullOrBlank()) {
                                val file = File(path)
                                if (file.exists()) return file
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 2. Query MediaStore by DISPLAY_NAME and SIZE to find the canonical file
        var displayName: String? = null
        var size: Long = -1L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) displayName = cursor.getString(nameIdx)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Throwable) {}

        if (!displayName.isNullOrBlank()) {
            try {
                val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media._ID)
                val selection = if (size > 0) {
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ?"
                } else {
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
                }
                val selectionArgs = if (size > 0) arrayOf(displayName, size.toString()) else arrayOf(displayName)

                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media._ID} DESC"
                )?.use { mediaCursor ->
                    if (mediaCursor.moveToFirst()) {
                        val dataIdx = mediaCursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (dataIdx != -1) {
                            val path = mediaCursor.getString(dataIdx)
                            if (!path.isNullOrBlank()) {
                                val file = File(path)
                                if (file.exists()) return file
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}

            // Direct fallback checks in standard camera and gallery folders
            val candidateDirs = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )
            for (dir in candidateDirs) {
                val candidateFile = File(dir, displayName!!)
                if (candidateFile.exists()) {
                    return candidateFile
                }
            }
        }

        return null
    }

    /**
     * Resolves a PhotoPicker or virtual content URI to a canonical MediaStore URI.
     */
    fun resolveMediaStoreUri(context: Context, uri: Uri): Uri? {
        val uriStr = uri.toString()
        if (uriStr.contains("media/external/images/media")) {
            return uri
        }

        // Try 1: If PhotoPicker URI, extract the numeric MediaStore ID from the last path segment
        val lastSegment = uri.lastPathSegment
        val numericId = lastSegment?.toLongOrNull()
        if (numericId != null) {
            val candidate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, numericId)
            try {
                context.contentResolver.query(candidate, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return candidate
                    }
                }
            } catch (_: Throwable) {}
        }

        // Try 2: If we can resolve to a physical file, query MediaStore by DATA
        val realFile = getRealPathFromUri(context, uri)
        if (realFile != null && realFile.exists()) {
            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DATA} = ?",
                    arrayOf(realFile.absolutePath),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            } catch (_: Throwable) {}
        }

        // Try 3: Query by DISPLAY_NAME and SIZE
        return try {
            var displayName: String? = null
            var size: Long = -1L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }

            if (!displayName.isNullOrBlank()) {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                if (size > 0) {
                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ?",
                        arrayOf(displayName, size.toString()),
                        "${MediaStore.Images.Media._ID} DESC"
                    )?.use { mediaCursor ->
                        if (mediaCursor.moveToFirst()) {
                            val id = mediaCursor.getLong(mediaCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                            return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        }
                    }
                }

                // Fallback: match by DISPLAY_NAME only
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(displayName),
                    "${MediaStore.Images.Media._ID} DESC"
                )?.use { mediaCursor ->
                    if (mediaCursor.moveToFirst()) {
                        val id = mediaCursor.getLong(mediaCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
