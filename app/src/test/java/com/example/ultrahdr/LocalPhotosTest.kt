package com.example.ultrahdr

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ultrahdr.processing.LocalPhoto
import com.example.ultrahdr.processing.LocalPhotos
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalPhotosTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun photo(id: Long, album: String, taken: String) =
        LocalPhoto(id, Uri.parse("content://media/external/images/media/$id"), album, at(taken), at(taken))

    @Test
    fun cameraComesFirstThenTheBiggestAlbums() {
        val photos = listOf(
            photo(1, "WhatsApp Images", "2026-11-15T10:00:00"),
            photo(2, "WhatsApp Images", "2026-11-15T09:00:00"),
            photo(3, "WhatsApp Images", "2026-11-15T08:00:00"),
            photo(4, "Screenshots", "2026-11-15T07:00:00"),
            photo(5, "Download", "2026-11-15T06:00:00"),
            photo(6, "Download", "2026-11-15T05:00:00"),
            photo(7, "Camera", "2026-11-15T04:00:00"),
            photo(8, "", "2026-11-15T03:00:00")
        )
        assertEquals(listOf("Camera", "WhatsApp Images", "Download", "Screenshots"), LocalPhotos.albums(photos))
        assertEquals("WhatsApp", LocalPhotos.albumLabel("WhatsApp Images"))
        assertEquals("Downloads", LocalPhotos.albumLabel("Download"))
        assertEquals("Camera", LocalPhotos.albumLabel("Camera"))
    }

    @Test
    fun noPhotosMeansNoAlbums() {
        assertEquals(emptyList<String>(), LocalPhotos.albums(emptyList()))
    }
}
