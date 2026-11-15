package com.example.ultrahdr

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.ui.galleryButtonLabel
import com.example.ultrahdr.ui.openConvertedPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The finished screen's Gallery button: which app it opens, and what it says. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OpenGalleryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun converted(i: Int) =
        PhotoResult(ScreensTest.uri(i), "photo $i.jpg", Outcome.UPDATED, output = ScreensTest.uri(i))

    private val failed = PhotoResult(ScreensTest.uri(9), "photo 9.jpg", Outcome.FAILED, note = "Couldn't open this photo.")

    @Test
    fun theLabelSaysWhatOpens() {
        assertEquals("Open photo", galleryButtonLabel(listOf(converted(0))))
        // A photo that failed can't be opened, so one success among failures still opens that photo.
        assertEquals("Open photo", galleryButtonLabel(listOf(converted(0), failed)))
        assertEquals("Open Gallery", galleryButtonLabel(listOf(converted(0), converted(1))))
    }

    @Test
    fun onePhotoOpensThatPhotoInSamsungGallery() {
        openConvertedPhotos(app, listOf(failed, converted(3)))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(ScreensTest.uri(3), started.data)
        assertEquals("com.sec.android.gallery3d", started.`package`)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun severalPhotosOpenSamsungGalleryItself() {
        openConvertedPhotos(app, listOf(converted(0), converted(1), failed, converted(2)))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals("com.sec.android.gallery3d", started.`package`)
        assertNull(started.data)
        // Opened the way its home screen icon opens it, so it comes up as the user left it.
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    // The other phones' path, Gallery found through Android's gallery category, isn't tested here:
    // Robolectric ignores the selector that path relies on.
    @Test
    fun withNothingToOpenThePhotosTheAppStaysUp() {
        shadowOf(app).checkActivities(true)
        openConvertedPhotos(app, listOf(converted(0), converted(1)))
        openConvertedPhotos(app, listOf(converted(0)))
        openConvertedPhotos(app, listOf(failed))
    }
}
