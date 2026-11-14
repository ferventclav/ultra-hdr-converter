package com.example.ultrahdr.processing

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Some apps hand over a photo under a name made for their own cache: Google Photos sends a cloud
 * photo as "<uuid>-1_all_<number>.jpg" from the picker and as "image.jpg" when it shares one, and
 * the photo picker can send a bare number. Such a name is replaced by the time the photo was taken,
 * the way a Galaxy camera names its photos (20261114_231500.jpg). Real names are kept as they are.
 */
object PhotoNames {

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val BARE_NUMBER = Regex("[0-9]+")

    /** "image", "Photo (2)", "picture_1": names that say nothing about the photo. */
    private val GENERIC = Regex(
        """(image|photo|picture|untitled|download|file|share|shared|temp|tmp)( ?\(\d{1,3}\)|[ _-]?\d{1,3})?""",
        RegexOption.IGNORE_CASE
    )

    fun readable(name: String?, takenAt: Long?, now: Long = System.currentTimeMillis()): String {
        val base = name?.substringBeforeLast('.', name)?.trim().orEmpty()
        val meaningless = base.isEmpty() || UUID.containsMatchIn(base) || BARE_NUMBER.matches(base) ||
            GENERIC.matches(base)
        if (!meaningless) return name!!
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(takenAt ?: now)) + ".jpg"
    }
}
