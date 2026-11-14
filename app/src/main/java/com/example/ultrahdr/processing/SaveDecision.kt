package com.example.ultrahdr.processing

/** Where a converted photo goes. Decided before any work is done. */
sealed class SavePlan {
    /** Rewrite the original file where it is. */
    object InPlace : SavePlan()

    /** Save a new photo in Pictures/UltraHDR. */
    object Copy : SavePlan()

    /** Change nothing, and say why in one plain line. */
    class LeaveAlone(val reason: String) : SavePlan()
}

/** The facts about one photo that decide where it may be saved. */
data class SaveFacts(
    val replaceOriginal: Boolean,
    val isCloud: Boolean,
    val hasTarget: Boolean,
    /** The bytes were read from the real file, or proven to be the same as it. */
    val bytesFromTarget: Boolean,
    val canReadLocation: Boolean,
    val isJpeg: Boolean,
    val isMotionPhoto: Boolean,
    val pixels: Long
)

/**
 * With "Update original photos" on, a photo is updated in place or left alone. The only copy made
 * is for a cloud photo, as in version 1.9. With the switch off, every photo becomes a copy.
 */
object SaveDecision {

    fun decide(f: SaveFacts): SavePlan = when {
        !f.replaceOriginal -> SavePlan.Copy
        f.isCloud -> SavePlan.Copy
        !f.hasTarget -> SavePlan.LeaveAlone("Couldn't find the original file on this phone, so it wasn't changed.")
        !f.bytesFromTarget && !f.canReadLocation ->
            SavePlan.LeaveAlone("Needs the photo location permission, or updating it would erase where it was taken.")
        !f.bytesFromTarget ->
            SavePlan.LeaveAlone("Couldn't confirm the picked photo is the file on the phone, so the file wasn't changed.")
        !f.isJpeg -> SavePlan.LeaveAlone("Not a JPEG, so it was left as it is.")
        f.isMotionPhoto -> SavePlan.LeaveAlone("Motion photo. Saving it again would remove its video.")
        f.pixels <= 0 -> SavePlan.LeaveAlone("Couldn't read the picture's size, so it wasn't changed.")
        f.pixels > UltraHdrEngine.MAX_REENCODE_PIXELS ->
            SavePlan.LeaveAlone("Too large to save again safely, so it wasn't changed.")
        else -> SavePlan.InPlace
    }
}

/** Plain text for the clipboard: one header line, then one line per photo. */
object CopyReport {

    fun build(header: String, results: List<PhotoResult>): String =
        (listOf(header) + results.map { line(it) }).joinToString("\n")

    fun line(r: PhotoResult): String {
        val t = r.trace
        return listOf(
            r.name,
            r.outcome.name.lowercase(),
            r.note ?: "-",
            "from ${t.source.ifEmpty { "?" }}",
            "real file ${if (t.realFileFound) "found" else "not found"}",
            "bytes ${t.bytesFrom.ifEmpty { "-" }}",
            "hdr checks ${t.hdrChecks.joinToString("+").ifEmpty { "none" }}",
            "size ${size(t.sizeBefore)} -> ${size(t.sizeAfter)}"
        ).joinToString(" | ")
    }

    private fun size(bytes: Long) = if (bytes < 0) "-" else bytes.toString()
}
