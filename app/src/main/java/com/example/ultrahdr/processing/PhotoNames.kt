package com.example.ultrahdr.processing

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Some apps hand over a photo under a name made for their own cache: Google Photos sends a cloud
 * photo as "<uuid>-1_all_<number>.jpg", the photo picker can send a bare number. Such a name is
 * replaced by the time the photo was taken, the way a Galaxy camera names its photos
 * (20261114_231500.jpg). Real names are kept as they are.
 */
object PhotoNames {

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val BARE_NUMBER = Regex("[0-9]+")

    fun readable(name: String?, takenAt: Long?, now: Long = System.currentTimeMillis()): String {
        val base = name?.substringBeforeLast('.', name)?.trim().orEmpty()
        if (base.isNotEmpty() && !UUID.containsMatchIn(base) && !BARE_NUMBER.matches(base)) return name!!
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(takenAt ?: now)) + ".jpg"
    }
}
