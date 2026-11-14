package com.example.ultrahdr

import com.example.ultrahdr.processing.PhotoNames
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class PhotoNamesTest {

    private val taken = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261114_231500")!!.time

    @Test
    fun aGooglePhotosCacheNameBecomesTheCaptureTime() {
        assertEquals(
            "20261114_231500.jpg",
            PhotoNames.readable("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d-1_all_12345.jpg", taken)
        )
    }

    @Test
    fun aBareNumberBecomesTheCaptureTime() {
        assertEquals("20261114_231500.jpg", PhotoNames.readable("10866.jpg", taken))
    }

    @Test
    fun withNoNameAndNoDateTheTimeNowIsUsed() {
        assertEquals("20261114_231500.jpg", PhotoNames.readable(null, null, now = taken))
    }

    @Test
    fun realNamesAreKept() {
        listOf(
            "20251114_231500.jpg",
            "IMG_1234.JPG",
            "PXL_20251114_123456789.jpg",
            "IMG-20251114-WA0003.jpg",
            "Holiday at the beach.jpg"
        ).forEach { assertEquals(it, PhotoNames.readable(it, taken)) }
    }
}
