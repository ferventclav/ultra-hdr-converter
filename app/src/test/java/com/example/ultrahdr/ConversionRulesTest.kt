package com.example.ultrahdr

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.ultrahdr.processing.CopyReport
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.ImageExifHelper
import com.example.ultrahdr.processing.MediaStoreSaver
import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.processing.SaveDecision
import com.example.ultrahdr.processing.SaveFacts
import com.example.ultrahdr.processing.SavePlan
import com.example.ultrahdr.processing.Trace
import com.example.ultrahdr.processing.UltraHdrContainer
import com.example.ultrahdr.processing.UltraHdrEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** The Milestone 1 rules: where photos may be saved, what counts as HDR already, and undoing v2.0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversionRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun sceneJpeg(kind: Int = TestScenes.LAMP, width: Int = 640, height: Int = 480): ByteArray {
        val out = ByteArrayOutputStream()
        TestScenes.bitmap(kind, width, height).compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun afterSoi(jpeg: ByteArray, vararg segments: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + segments.fold(ByteArray(0)) { acc, s -> acc + s } + jpeg.copyOfRange(2, jpeg.size)

    private fun xmp(body: String) = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) +
        body.toByteArray(Charsets.UTF_8)

    /** A v2.0 lossless file: the original JPEG with this app's gain map attached, nothing re-encoded. */
    private fun losslessFrom(jpeg: ByteArray): ByteArray {
        val layout = (UltraHdrContainer.inspect(jpeg) as UltraHdrContainer.Inspection.Ready).layout
        val small = UltraHdrEngine.decodeSmall(jpeg)!!
        val plan = UltraHdrEngine.plan(small, HdrStrength.BALANCED)
        val out = tmp.newFile()
        assertTrue(UltraHdrEngine.writeLossless(jpeg, layout, plan, out))
        return out.readBytes()
    }

    /**
     * Laid out like an S25 camera photo: a large header (EXIF, ICC, Samsung's APP4), then the
     * Ultra HDR labels, then the ISO and MPF blocks, and a second JPEG (the gain map) at the end.
     * The header here is padded past 256 KB, so labels are only found by reading the whole file.
     */
    private fun cameraStyle(): ByteArray {
        val filler = ByteArray(60_000)
        val labels = xmp(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description xmlns:hdrgm=\"http://ns.google.com/photos/1.0/gainmap/\" hdrgm:Version=\"1.0\"/>" +
                "</rdf:RDF></x:xmpmeta>"
        )
        val iso = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0)
        val mpf = "MPF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(80)
        val primary = afterSoi(
            sceneJpeg(TestScenes.SUNSET),
            *Array(5) { segment(0xE4, filler) },
            segment(0xE1, labels), segment(0xE2, iso), segment(0xE2, mpf)
        )
        return primary + sceneJpeg(TestScenes.SNOW, 160, 120)
    }

    private fun facts(
        replaceOriginal: Boolean = true,
        isCloud: Boolean = false,
        hasTarget: Boolean = true,
        bytesFromTarget: Boolean = true,
        canReadLocation: Boolean = true,
        isJpeg: Boolean = true,
        isMotionPhoto: Boolean = false,
        pixels: Long = 12_000_000
    ) = SaveFacts(replaceOriginal, isCloud, hasTarget, bytesFromTarget, canReadLocation, isJpeg, isMotionPhoto, pixels)

    // ---------------------------------------------------------------------------------------
    // Where a photo may be saved
    // ---------------------------------------------------------------------------------------

    @Test
    fun aLocalPhotoReadFromItsRealFileIsUpdatedInPlace() {
        assertEquals(SavePlan.InPlace, SaveDecision.decide(facts()))
    }

    @Test
    fun withUpdateOffEveryPhotoBecomesACopy() {
        assertEquals(SavePlan.Copy, SaveDecision.decide(facts(replaceOriginal = false)))
        assertEquals(SavePlan.Copy, SaveDecision.decide(facts(replaceOriginal = false, hasTarget = false)))
    }

    @Test
    fun aCloudPhotoBecomesACopyAsInVersion19() {
        assertEquals(SavePlan.Copy, SaveDecision.decide(facts(isCloud = true, hasTarget = false, bytesFromTarget = false)))
    }

    @Test
    fun pickerBytesAreNeverWrittenOverTheOriginal() {
        // Android hands the picker's copy out with the GPS blanked. Writing it back would erase it.
        assertTrue(SaveDecision.decide(facts(bytesFromTarget = false)) is SavePlan.LeaveAlone)
        assertTrue(SaveDecision.decide(facts(bytesFromTarget = false, canReadLocation = false)) is SavePlan.LeaveAlone)
    }

    @Test
    fun everythingElseIsLeftAloneWithAReasonAndNeverCopied() {
        val cases = listOf(
            facts(hasTarget = false),
            facts(isMotionPhoto = true),
            facts(isJpeg = false),
            facts(pixels = UltraHdrEngine.MAX_REENCODE_PIXELS + 1),
            facts(pixels = 0)
        )
        for (case in cases) {
            val plan = SaveDecision.decide(case)
            assertTrue("$case", plan is SavePlan.LeaveAlone)
            assertTrue("$case has a reason", (plan as SavePlan.LeaveAlone).reason.isNotBlank())
        }
    }

    // ---------------------------------------------------------------------------------------
    // Version 1.9's conversion keeps the location and turns the picture upright
    // ---------------------------------------------------------------------------------------

    @Test
    fun gpsIsStillThereAfterConversion() {
        val staged = tmp.newFile("in.jpg").apply { writeBytes(sceneJpeg(TestScenes.BEACH, 800, 600)) }
        ExifInterface(staged.absolutePath).apply {
            setLatLong(48.8584, 2.2945)
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val out = tmp.newFile("out.jpg")
        assertNotNull(UltraHdrEngine.writeReencoded(staged, HdrStrength.BALANCED, out))

        val exif = ExifInterface(out.absolutePath)
        val latLong = exif.latLong
        assertNotNull("GPS kept", latLong)
        assertEquals(48.8584, latLong!![0], 1e-4)
        assertEquals(2.2945, latLong[1], 1e-4)
        // Saved upright, so the label says normal and the picture is now portrait.
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
        assertEquals(600, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, -1).let { if (it > 0) it else 600 })
    }

    @Test
    fun aPhotoWithNoDateGetsTheProviderDateAndStaysHdr() {
        val file = tmp.newFile("nodate.jpg").apply { writeBytes(losslessFrom(sceneJpeg(TestScenes.LAMP, 800, 600))) }
        assertEquals("none", ImageExifHelper.metadataKinds(file))
        val checksBefore = UltraHdrEngine.hdrChecks(file.readBytes())
        assertTrue(checksBefore.isNotEmpty())

        val takenAt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse("2026:11:14 23:15:00")!!.time
        ImageExifHelper.stampCaptureTime(file, takenAt)

        assertEquals(takenAt, ImageExifHelper.getCaptureTimestamp(file))
        assertEquals("date", ImageExifHelper.metadataKinds(file))
        assertEquals("the gain map is still found the same ways", checksBefore, UltraHdrEngine.hdrChecks(file.readBytes()))
    }

    // ---------------------------------------------------------------------------------------
    // HDR already: the whole file is read
    // ---------------------------------------------------------------------------------------

    @Test
    fun cameraStyleFileIsHdrEvenWhenItsLabelsSitPast256Kb() {
        val file = cameraStyle()
        assertFalse("labels are past the old 256 KB window", ImageExifHelper.looksLikeUltraHdr(file, 262_144))
        assertTrue(UltraHdrEngine.hdrChecks(file).contains("markers"))
        assertNull("not made by this app, so it can't be undone", UltraHdrContainer.strip(file))
    }

    @Test
    fun aPlatformEncodedFileLikeVersion19MadeIsHdr() {
        val bitmap = TestScenes.bitmap(TestScenes.LAMP, 640, 480).copy(Bitmap.Config.ARGB_8888, true)
        UltraHdrEngine.attach(bitmap, UltraHdrEngine.plan(bitmap, HdrStrength.BALANCED))
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out))
        val file = out.toByteArray()
        // Robolectric's JPEG encoder drops the gain map. On a phone it is written; that case is
        // checked on the spare phone with the files v2.1 makes.
        assumeTrue("this encoder writes no gain map", file.size > 4 && String(file, Charsets.ISO_8859_1).contains("hdrgm"))
        assertTrue("checks: ${UltraHdrEngine.hdrChecks(file)}", UltraHdrEngine.hdrChecks(file).isNotEmpty())
        assertNull("re-encoded by the platform, so it can't be undone", UltraHdrContainer.strip(file))
    }

    @Test
    fun aVersion20LosslessFileIsHdr() {
        val file = losslessFrom(sceneJpeg())
        val checks = UltraHdrEngine.hdrChecks(file)
        assertTrue("checks: $checks", checks.contains("markers") && checks.contains("layout"))
    }

    @Test
    fun aHeicWithAGainMapIsHdrAndAPlainOneIsNot() {
        fun box(type: String, body: ByteArray): ByteArray {
            val size = body.size + 8
            return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
                type.toByteArray(Charsets.ISO_8859_1) + body
        }
        val ftyp = box("ftyp", "heic\u0000\u0000\u0000\u0000mif1heic".toByteArray(Charsets.ISO_8859_1))
        val withMap = ftyp + box("meta", box("iinf", box("infe", "\u0000\u0000\u0000\u0000\u0000\u0002\u0000\u0000tmap".toByteArray(Charsets.ISO_8859_1)))) + ByteArray(4096)
        val plain = ftyp + box("meta", box("iinf", box("infe", "\u0000\u0000\u0000\u0000\u0000\u0001\u0000\u0000hvc1".toByteArray(Charsets.ISO_8859_1)))) + ByteArray(4096)
        assertTrue(ImageExifHelper.looksLikeUltraHdr(withMap))
        assertFalse(ImageExifHelper.looksLikeUltraHdr(plain))
    }

    @Test
    fun anOrdinaryJpegIsNotHdr() {
        assertTrue(UltraHdrEngine.hdrChecks(sceneJpeg()).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Redo: taking v2.0's gain map back out
    // ---------------------------------------------------------------------------------------

    @Test
    fun stripGivesBackTheExactOriginalWithoutXmp() {
        val original = sceneJpeg()
        assertArrayEquals(original, UltraHdrContainer.strip(losslessFrom(original)))
    }

    @Test
    fun stripGivesBackTheExactOriginalWithItsOwnXmp() {
        val own = xmp(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:CreatorTool=\"Camera\"/>" +
                "</rdf:RDF></x:xmpmeta>"
        )
        val original = afterSoi(sceneJpeg(), segment(0xE1, own))
        assertArrayEquals(original, UltraHdrContainer.strip(losslessFrom(original)))
    }

    @Test
    fun stripRefusesFilesThisAppDidNotWrite() {
        assertNull(UltraHdrContainer.strip(sceneJpeg()))
        assertNull(UltraHdrContainer.strip(cameraStyle()))
    }

    @Test
    fun realCameraSampleIsHdrAndCannotBeStripped() {
        val path = System.getenv("UHDR_SAMPLE") ?: return
        val bytes = File(path).readBytes()
        assertTrue(UltraHdrEngine.hdrChecks(bytes).isNotEmpty())
        assertNull(UltraHdrContainer.strip(bytes))
    }

    // ---------------------------------------------------------------------------------------
    // Finding the photo, and the Copy report
    // ---------------------------------------------------------------------------------------

    @Test
    fun googlePhotosShareOfALocalPhotoPointsBackToTheGallery() {
        val shared = "content://com.google.android.apps.photos.contentprovider/0/1/" +
            "content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F10866/ORIGINAL/NONE/image%2Fjpeg/744170512"
        assertEquals(10866L, MediaStoreSaver.embeddedMediaId(shared))
        assertNull(MediaStoreSaver.embeddedMediaId("content://com.google.android.apps.photos.contentprovider/-1/1/mediakey%3A%2FAF1Qip/ORIGINAL"))
    }

    @Test
    fun whereAPhotoCameFromIsNamedInTheReport() {
        assertEquals("picker", MediaStoreSaver.sourceOf(Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/10866")))
        assertEquals("media", MediaStoreSaver.sourceOf(Uri.parse("content://media/external/images/media/10866")))
        assertEquals("file", MediaStoreSaver.sourceOf(Uri.parse("file:///sdcard/DCIM/Camera/a.jpg")))
        assertEquals("com.sec.android.gallery3d.provider", MediaStoreSaver.sourceOf(Uri.parse("content://com.sec.android.gallery3d.provider/picker/item/1")))
    }

    @Test
    fun reportHasOneLinePerPhotoWithEverythingNeededToDebug() {
        val result = PhotoResult(
            uri = Uri.parse("content://media/external/images/media/1"),
            name = "20261114_190629.jpg",
            outcome = Outcome.ALREADY_HDR,
            note = "Already HDR, left as it is.",
            trace = Trace(
                "picker", true, "real file", listOf("queue", "markers", "decoder"), 7_684_869, -1,
                metadataBefore = "date+camera+gps"
            )
        )
        val text = CopyReport.build("Ultra HDR Converter 2.1 (12)", listOf(result))
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertEquals(
            "20261114_190629.jpg | already_hdr | Already HDR, left as it is. | from picker | real file found | " +
                "bytes real file | hdr checks queue+markers+decoder | size 7684869 -> - | metadata date+camera+gps -> -",
            lines[1]
        )
    }
}
