package com.example.ultrahdr.processing

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Keeps a private copy of an original photo for the moment it is being overwritten.
 *
 * Writing a file in place is not instant. If the app is killed half way, the gallery would be left
 * with a broken picture. So the original bytes are parked here first, removed once the new file is
 * fully written, and put back on the next launch if they are still here.
 */
internal object RecoveryStore {

    private const val DIR = "recovery"

    class Ticket internal constructor(internal val data: File, internal val meta: File)

    /** [waiting]: originals that could not be put back yet. They are tried again next launch. */
    class Report(val restored: Int, val waiting: Int) {
        val any: Boolean get() = restored + waiting > 0
    }

    fun begin(
        context: Context,
        original: ByteArray,
        name: String,
        target: PhotoTarget,
        expectedSize: Long,
        fileTime: Long? = null
    ): Ticket {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val data = File(dir, "$id.bin")
        val meta = File(dir, "$id.txt")
        FileOutputStream(data).use { out ->
            out.write(original)
            out.fd.sync()
        }
        // The ticket only counts once this small file exists, so a half-written backup is never used.
        meta.writeText(
            listOf(
                name,
                target.file?.absolutePath.orEmpty(),
                target.mediaUri?.toString().orEmpty(),
                expectedSize.toString(),
                (fileTime ?: -1L).toString()
            ).joinToString("\n")
        )
        return Ticket(data, meta)
    }

    fun commit(ticket: Ticket) {
        ticket.meta.delete()
        ticket.data.delete()
    }

    /**
     * Ends the ticket but hands its backup over instead of deleting it (for Undo). Once the ticket
     * is gone the backup is no longer put back at launch.
     */
    fun release(ticket: Ticket): File {
        ticket.meta.delete()
        return ticket.data
    }

    /** Run once at launch. Puts back any original whose update was cut off. */
    suspend fun recover(context: Context): Report {
        val dir = File(context.filesDir, DIR)
        val metas = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
        var restored = 0
        var waiting = 0

        for (meta in metas) {
            val data = File(dir, meta.name.removeSuffix(".txt") + ".bin")
            val lines = runCatching { meta.readLines() }.getOrDefault(emptyList())
            if (!data.exists() || lines.size < 4) {
                meta.delete()
                data.delete()
                continue
            }
            val name = lines[0]
            val target = PhotoTarget(
                file = lines[1].takeIf { it.isNotEmpty() }?.let { File(it) },
                mediaUri = lines[2].takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
            )
            val expectedSize = lines[3].toLongOrNull() ?: -1L

            // The update finished and only the tidy-up was missed: nothing to put back.
            if (expectedSize > 0 && MediaStoreSaver.currentSize(context, target) == expectedSize) {
                meta.delete()
                data.delete()
                continue
            }

            val original = runCatching { data.readBytes() }.getOrNull()
            if (original == null) {
                meta.delete()
                data.delete()
                continue
            }
            val fileTime = lines.getOrNull(4)?.toLongOrNull()?.takeIf { it > 0 }
            if (MediaStoreSaver.writeBytesInPlace(context, target, original, fileTime)) {
                restored++
                meta.delete()
                data.delete()
            } else {
                // Only ever put back where it was. Never saved as a second photo: the backup stays
                // here and is tried again on the next launch.
                waiting++
            }
        }

        // Backups without a ticket were never relied on.
        dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()
            .filter { !File(dir, it.name.removeSuffix(".bin") + ".txt").exists() }
            .forEach { it.delete() }

        return Report(restored, waiting)
    }
}
