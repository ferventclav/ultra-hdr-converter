package com.example.ultrahdr.processing

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * What the last batch changed, so it can be undone from the result screen.
 *
 * An updated photo's safety backup is moved here once the new file is in place, so keeping it
 * costs no extra writing. A new HDR copy is listed so Undo can delete it. Everything here is
 * deleted when the next batch starts or the result screen is left.
 */
internal object UndoStore {

    private const val DIR = "undo"
    private const val ORIGINAL = "original"
    private const val COPY = "copy"
    private const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

    /** [changed]: photos edited since they were converted. Undo leaves those alone. */
    class Report(val putBack: Int, val removed: Int, val changed: Int, val failed: Int) {
        val total: Int get() = putBack + removed
    }

    private class Entry(val meta: File, val lines: List<String>) {
        val kind: String get() = lines.getOrElse(0) { "" }
        val data: File get() = File(meta.parentFile, meta.name.removeSuffix(".txt") + ".bin")
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun entries(context: Context): List<Entry> =
        dir(context).listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
            .sortedBy { it.lastModified() }
            .mapNotNull { meta -> runCatching { Entry(meta, meta.readLines()) }.getOrNull() }

    /**
     * Keeps [backup], the original of a photo that was just updated in place. [newSize] is the
     * size of the HDR file now in its place, so Undo can tell whether the photo was changed since.
     */
    fun keepOriginal(
        context: Context,
        backup: File,
        target: PhotoTarget,
        fileTime: Long?,
        newSize: Long,
        name: String
    ): Boolean {
        val dir = dir(context).apply { mkdirs() }
        // A huge batch must not fill the phone. Past this point its originals are not kept.
        if (dir.usableSpace < MIN_FREE_BYTES) {
            backup.delete()
            return false
        }
        val id = UUID.randomUUID().toString()
        val data = File(dir, "$id.bin")
        if (!backup.renameTo(data)) {
            backup.delete()
            return false
        }
        File(dir, "$id.txt").writeText(
            listOf(
                ORIGINAL,
                name,
                target.file?.absolutePath.orEmpty(),
                target.mediaUri?.toString().orEmpty(),
                (fileTime ?: -1L).toString(),
                newSize.toString()
            ).joinToString("\n")
        )
        return true
    }

    /** Lists a new HDR copy this batch saved, so Undo deletes it. */
    fun keepCopy(context: Context, copy: Uri) {
        val dir = dir(context).apply { mkdirs() }
        File(dir, UUID.randomUUID().toString() + ".txt").writeText("$COPY\n$copy")
    }

    fun any(context: Context): Boolean = entries(context).isNotEmpty()

    /** The gallery entries Undo writes to, for Android's "Allow this app to modify" request. */
    fun photosToPutBack(context: Context): List<Uri> =
        entries(context).filter { it.kind == ORIGINAL }
            .mapNotNull { it.lines.getOrNull(3)?.takeIf { s -> s.isNotEmpty() }?.let(Uri::parse) }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Puts every original back where it was and deletes every new copy, then forgets the batch. */
    suspend fun undo(context: Context): Report {
        var putBack = 0
        var removed = 0
        var changed = 0
        var failed = 0
        for (entry in entries(context)) {
            when (entry.kind) {
                ORIGINAL -> when (putBack(context, entry)) {
                    MediaStoreSaver.InPlace.DONE -> putBack++
                    null -> changed++
                    else -> failed++
                }
                COPY -> {
                    val uri = entry.lines.getOrNull(1)?.let(Uri::parse)
                    val deleted = uri != null && runCatching {
                        context.contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                    if (deleted) removed++ else failed++
                }
            }
        }
        clear(context)
        return Report(putBack, removed, changed, failed)
    }

    /** Null when the photo was changed or removed since it was converted, so it is left alone. */
    private suspend fun putBack(context: Context, entry: Entry): MediaStoreSaver.InPlace? {
        val lines = entry.lines
        if (lines.size < 6 || !entry.data.exists()) return MediaStoreSaver.InPlace.FAILED
        val target = PhotoTarget(
            file = lines[2].takeIf { it.isNotEmpty() }?.let(::File),
            mediaUri = lines[3].takeIf { it.isNotEmpty() }?.let(Uri::parse)
        )
        val fileTime = lines[4].toLongOrNull()?.takeIf { it > 0 }
        val newSize = lines[5].toLongOrNull() ?: -1L
        val now = MediaStoreSaver.readCurrent(context, target) ?: return null
        if (now.size.toLong() != newSize) return null
        return MediaStoreSaver.replaceInPlace(
            context = context,
            target = target,
            content = entry.data,
            original = now,
            name = lines[1],
            upright = false,
            fileTime = fileTime,
            keepForUndo = false
        )
    }
}
