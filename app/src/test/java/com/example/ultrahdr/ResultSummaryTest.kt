package com.example.ultrahdr

import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.ui.LineKind
import com.example.ultrahdr.ui.Tone
import com.example.ultrahdr.ui.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The words on the result card, for every way a batch can end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ResultSummaryTest {

    private fun result(i: Int, outcome: Outcome, note: String? = null, kept: Boolean = true) = PhotoResult(
        uri = ScreensTest.uri(i),
        name = "IMG_$i.jpg",
        outcome = outcome,
        output = if (outcome == Outcome.UPDATED || outcome == Outcome.COPIED) ScreensTest.uri(i) else null,
        keptOriginalPixels = kept && (outcome == Outcome.UPDATED || outcome == Outcome.COPIED),
        note = note
    )

    @Test
    fun allUpdated() {
        val s = summarize(listOf(result(1, Outcome.UPDATED), result(2, Outcome.UPDATED)), total = 2, stopped = false)
        assertEquals("2 photos are now HDR", s.title)
        assertEquals(Tone.SUCCESS, s.tone)
        assertEquals(listOf("2 updated in place in Gallery"), s.lines.map { it.text })
        assertTrue(s.notes.isEmpty())
        assertNotNull("Lossless results say so", s.reassurance)
    }

    @Test
    fun onePhotoUsesTheSingular() {
        val s = summarize(listOf(result(1, Outcome.COPIED, note = "Not stored on this phone, so a copy was saved.")), 1, false)
        assertEquals("1 photo is now HDR", s.title)
        assertEquals(listOf("1 copy in the UltraHDR album"), s.lines.map { it.text })
        assertEquals(listOf("IMG_1.jpg: Not stored on this phone, so a copy was saved."), s.notes)
    }

    @Test
    fun aReEncodedCopyDoesNotClaimTheOriginalDataWasKept() {
        val s = summarize(
            listOf(result(1, Outcome.UPDATED), result(2, Outcome.COPIED, note = "Not a JPEG, so the HDR version was saved as a JPEG copy.", kept = false)),
            total = 2, stopped = false
        )
        assertNull(s.reassurance)
        assertEquals(listOf(LineKind.UPDATED, LineKind.COPIED), s.lines.map { it.kind })
    }

    @Test
    fun mixedBatchListsEveryOutcomeOnce() {
        val s = summarize(
            listOf(
                result(1, Outcome.UPDATED), result(2, Outcome.COPIED, note = "Reason A"),
                result(3, Outcome.COPIED, note = "Reason A"), result(4, Outcome.COPIED, note = "Reason A"),
                result(5, Outcome.ALREADY_HDR), result(6, Outcome.NO_HIGHLIGHTS, note = "ignored in notes"),
                result(7, Outcome.FAILED, note = "Couldn't open this photo.")
            ),
            total = 7, stopped = false
        )
        assertEquals("4 photos are now HDR", s.title)
        assertEquals(
            listOf(
                "1 updated in place in Gallery", "3 copies in the UltraHDR album", "1 skipped, already HDR",
                "1 skipped, no bright highlights", "1 failed"
            ),
            s.lines.map { it.text }
        )
        // Photos that share a reason are grouped, and long groups are shortened.
        assertEquals(
            listOf("IMG_2.jpg, IMG_3.jpg and 1 more: Reason A", "IMG_7.jpg: Couldn't open this photo."),
            s.notes
        )
    }

    @Test
    fun aSingleFailureIsNotCountedTwice() {
        val s = summarize(listOf(result(1, Outcome.FAILED, note = "Couldn't open this photo.")), 1, false)
        assertEquals("This photo couldn't be converted", s.title)
        assertEquals(Tone.ERROR, s.tone)
        assertTrue(s.lines.isEmpty())
        assertEquals(listOf("IMG_1.jpg: Couldn't open this photo."), s.notes)
        assertNull(s.reassurance)
    }

    @Test
    fun nothingToDoIsNotAnError() {
        val s = summarize(listOf(result(1, Outcome.ALREADY_HDR), result(2, Outcome.NO_HIGHLIGHTS)), 2, false)
        assertEquals("Nothing needed changing", s.title)
        assertEquals(Tone.INFO, s.tone)
    }

    @Test
    fun stoppingEarlySaysHowManyWereLeft() {
        val s = summarize(listOf(result(1, Outcome.UPDATED)), total = 5, stopped = true)
        assertEquals(listOf("1 updated in place in Gallery", "4 not started"), s.lines.map { it.text })
    }
}
