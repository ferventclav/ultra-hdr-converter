package com.example.ultrahdr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.UltraHdrContainer
import com.example.ultrahdr.processing.UltraHdrContainer.Blocker
import com.example.ultrahdr.processing.UltraHdrContainer.Inspection
import com.example.ultrahdr.processing.UltraHdrEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import kotlin.math.abs
import kotlin.math.ln

/**
 * The lossless writer, run against real JPEGs made by Android's own encoder (the Robolectric
 * native graphics runtime), and read back by [TestGainMapReader].
 *
 * Set the environment variable UHDR_SAMPLE to a real camera Ultra HDR photo to also run the
 * tests that use a genuine phone file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UltraHdrContainerTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /** A dim scene with one bright lamp, saved as a JPEG. */
    private fun sceneJpeg(width: Int = 640, height: Int = 480): ByteArray {
        val px = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val dx = x - width * 0.7
            val dy = y - height * 0.3
            if (dx * dx + dy * dy < 45.0 * 45.0) {
                Color.rgb(255, 252, 240)
            } else {
                val base = 40 + x * 90 / width
                Color.rgb(base, base, base + 20)
            }
        }
        val bitmap = Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        return out.toByteArray()
    }

    /** Inserts a block straight after the leading JFIF block, where a camera would put it. */
    private fun withSegment(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray {
        var at = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            at += 2 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        }
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, at) + segment + jpeg.copyOfRange(at, jpeg.size)
    }

    private fun xmpPayload(xml: String) =
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) + xml.toByteArray(Charsets.UTF_8)

    private class Converted(val bytes: ByteArray, val ratioMax: Float, val mapWidth: Int, val mapHeight: Int)

    private fun convert(jpeg: ByteArray, strength: HdrStrength = HdrStrength.BALANCED): Converted {
        val inspection = UltraHdrContainer.inspect(jpeg)
        assertTrue("Expected a file that can take a gain map", inspection is Inspection.Ready)
        val small = UltraHdrEngine.decodeSmall(jpeg)!!
        val plan = UltraHdrEngine.plan(small, strength)
        assertTrue("The test picture should have highlights", plan.hasVisibleEffect)
        val out = temp.newFile()
        assertTrue(UltraHdrEngine.writeLossless(jpeg, (inspection as Inspection.Ready).layout, plan, out))
        return Converted(out.readBytes(), plan.ratioMax, plan.width, plan.height)
    }

    private fun log2(v: Float) = ln(v.toDouble()) / ln(2.0)

    private fun assertReadableAndConsistent(result: Converted): TestGainMapReader.Found {
        val found = TestGainMapReader.read(result.bytes)
        // The two ways of locating the gain map must agree.
        assertEquals(found.primaryEnd, found.gainMapStart)
        assertEquals(found.gainMapStart, found.containerGainMapStart)
        assertEquals(found.gainMapSize, found.containerGainMapSize)
        // The numbers a decoder needs, in both notations.
        assertEquals(log2(result.ratioMax), found.hdrgm.getValue("GainMapMax").toDouble(), 1e-5)
        assertEquals(log2(result.ratioMax), found.hdrgm.getValue("HDRCapacityMax").toDouble(), 1e-5)
        assertEquals(0.0, found.hdrgm.getValue("GainMapMin").toDouble(), 0.0)
        assertEquals(0.0, found.hdrgm.getValue("HDRCapacityMin").toDouble(), 0.0)
        assertEquals(1.0, found.hdrgm.getValue("Gamma").toDouble(), 0.0)
        assertEquals(1.0 / 64.0, found.hdrgm.getValue("OffsetSDR").toDouble(), 1e-9)
        assertEquals(1.0 / 64.0, found.hdrgm.getValue("OffsetHDR").toDouble(), 1e-9)
        assertEquals("False", found.hdrgm.getValue("BaseRenditionIsHDR"))
        assertTrue(found.primaryHasIsoMarker)
        assertEquals(log2(result.ratioMax), found.isoHeadroomLog2!!, 1e-5)
        assertEquals(1.0 / 64.0, found.isoOffsets!!.first, 1e-9)
        assertEquals(1.0 / 64.0, found.isoOffsets!!.second, 1e-9)
        // The gain map itself is a picture the platform can open, at the planned size.
        val map = BitmapFactory.decodeByteArray(result.bytes, found.gainMapStart, found.gainMapSize)
        assertNotNull("The gain map must decode", map)
        assertEquals(result.mapWidth, map.width)
        assertEquals(result.mapHeight, map.height)
        return found
    }

    private fun assertSamePixels(a: ByteArray, b: ByteArray, sample: Int = 1) {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val first = BitmapFactory.decodeByteArray(a, 0, a.size, options)
        val second = BitmapFactory.decodeByteArray(b, 0, b.size, options)
        assertTrue("The photo's pixels changed", first.sameAs(second))
    }

    // ------------------------------------------------------------------------------------------
    // Plain photos
    // ------------------------------------------------------------------------------------------

    @Test
    fun plainJpegGetsAGainMapAndKeepsEveryOriginalByte() {
        val original = sceneJpeg()
        val result = convert(original)
        val found = assertReadableAndConsistent(result)

        assertSamePixels(original, result.bytes)

        // Take the added pieces out again: what is left must be the original file, byte for byte.
        val layout = (UltraHdrContainer.inspect(original) as Inspection.Ready).layout
        val added = result.bytes.size - original.size - found.gainMapSize
        val rebuilt = result.bytes.copyOfRange(0, layout.insertAt) +
            result.bytes.copyOfRange(layout.insertAt + added, found.primaryEnd) +
            result.bytes.copyOfRange(found.gainMapStart + found.gainMapSize, result.bytes.size)
        assertArrayEquals(original, rebuilt)
    }

    @Test
    fun gainMapIsBrightWhereTheLampIsAndDarkElsewhere() {
        val result = convert(sceneJpeg())
        val found = TestGainMapReader.read(result.bytes)
        val map = BitmapFactory.decodeByteArray(result.bytes, found.gainMapStart, found.gainMapSize)
        val lamp = Color.red(map.getPixel((map.width * 0.7).toInt(), (map.height * 0.3).toInt()))
        val corner = Color.red(map.getPixel(5, map.height - 5))
        assertTrue("Lamp should be near full boost, was $lamp", lamp > 235)
        assertTrue("Dark corner should get none, was $corner", corner < 6)
    }

    @Test
    fun convertedFileIsRecognisedAsAlreadyHdr() {
        val result = convert(sceneJpeg())
        assertTrue(UltraHdrContainer.inspect(result.bytes) is Inspection.AlreadyHdr)
    }

    @Test
    fun strongerSettingWritesMoreHeadroom() {
        val jpeg = sceneJpeg()
        val subtle = TestGainMapReader.read(convert(jpeg, HdrStrength.SUBTLE).bytes)
        val vivid = TestGainMapReader.read(convert(jpeg, HdrStrength.VIVID).bytes)
        assertTrue(
            subtle.hdrgm.getValue("GainMapMax").toDouble() < vivid.hdrgm.getValue("GainMapMax").toDouble()
        )
    }

    // ------------------------------------------------------------------------------------------
    // Things cameras and editors add to files
    // ------------------------------------------------------------------------------------------

    @Test
    fun dataAfterThePhotoIsKeptAfterTheGainMap() {
        // Samsung cameras append a block that ends in "SEFT" and is read from the end of the file.
        val trailer = ByteArray(5000) { (it * 31).toByte() } + "SEFH....SEFT".toByteArray(Charsets.US_ASCII)
        val original = sceneJpeg() + trailer
        val result = convert(original)
        val found = assertReadableAndConsistent(result)

        val tail = result.bytes.copyOfRange(result.bytes.size - trailer.size, result.bytes.size)
        assertArrayEquals("The trailer must still close the file, untouched", trailer, tail)
        assertEquals(result.bytes.size - trailer.size, found.gainMapStart + found.gainMapSize)
        assertSamePixels(original, result.bytes)
    }

    @Test
    fun existingXmpKeepsItsContentAndGainsTheNewEntries() {
        val xmp = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmp:Rating="5" xmp:Label="a &gt; b"/>
 </rdf:RDF>
</x:xmpmeta>
${" ".repeat(2000)}
<?xpacket end="w"?>"""
        val original = withSegment(sceneJpeg(), 0xE1, xmpPayload(xmp))
        val result = convert(original)
        val found = assertReadableAndConsistent(result)

        assertTrue("The rating must survive", found.primaryXmp.contains("xmp:Rating=\"5\""))
        assertTrue(found.primaryXmp.contains("xmp:Label=\"a &gt; b\""))
        assertEquals("There must still be exactly one XMP block", 1,
            TestGainMapReader.headerSegments(result.bytes, 0).count { seg ->
                seg.marker == 0xE1 && String(result.bytes, seg.payloadStart, 28, Charsets.US_ASCII)
                    .startsWith("http://ns.adobe.com/xap/1.0/")
            })
        assertSamePixels(original, result.bytes)
    }

    @Test
    fun xmpWithChildElementsIsMergedToo() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Diwali at home</rdf:li></rdf:Alt></dc:title>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""
        val result = convert(withSegment(sceneJpeg(), 0xE1, xmpPayload(xmp)))
        val found = assertReadableAndConsistent(result)
        assertTrue(found.primaryXmp.contains("Diwali at home"))
    }

    @Test
    fun motionPhotosAreNotTouched() {
        val googleStyle = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    GCamera:MotionPhoto="1"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val a = UltraHdrContainer.inspect(withSegment(sceneJpeg(), 0xE1, xmpPayload(googleStyle)))
        assertEquals(Blocker.MOTION_PHOTO, (a as Inspection.Blocked).reason)

        val samsungStyle = sceneJpeg() + "....MotionPhoto_Data....ftypmp42".toByteArray(Charsets.US_ASCII)
        val b = UltraHdrContainer.inspect(samsungStyle)
        assertEquals(Blocker.MOTION_PHOTO, (b as Inspection.Blocked).reason)
    }

    @Test
    fun filesThatAlreadyHoldSeveralPicturesAreNotTouched() {
        val mpf = "MPF\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(40)
        val inspection = UltraHdrContainer.inspect(withSegment(sceneJpeg(), 0xE2, mpf))
        assertEquals(Blocker.HAS_EXTRA_IMAGES, (inspection as Inspection.Blocked).reason)
    }

    @Test
    fun samsungMultipleXmpBlocksDoNotBlockAsUnusualMetadata() {
        // Samsung camera JPEGs carry a second XMP block for extended attributes.
        // This must not be classified as UNUSUAL_METADATA; the file is a normal JPEG
        // that should proceed to lossless injection.
        val primaryXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="3"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val secondXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:sec="http://sec.samsung.com/1.0/" sec:LensFacing="0"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val jpeg = withSegment(
            withSegment(sceneJpeg(), 0xE1, xmpPayload(primaryXmp)),
            0xE1, xmpPayload(secondXmp)
        )
        val inspection = UltraHdrContainer.inspect(jpeg)
        assertTrue("Samsung multi-XMP should be Ready, not Blocked", inspection is Inspection.Ready)
    }

    @Test
    fun samsungMotionPhotoInSecondXmpIsStillDetected() {
        // A Samsung Motion Photo where the MotionPhoto marker is in the second XMP block
        // must still be blocked, not silently accepted.
        val primaryXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    GCamera:MotionPhoto="1"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val jpeg = withSegment(
            withSegment(sceneJpeg(), 0xE1, xmpPayload(primaryXmp)),
            0xE1, xmpPayload(motionXmp)
        )
        val inspection = UltraHdrContainer.inspect(jpeg)
        assertEquals(Blocker.MOTION_PHOTO, (inspection as Inspection.Blocked).reason)
    }

    @Test
    fun gainMapInSecondXmpIsDetectedAsAlreadyHdr() {
        // A previously converted file whose gain map marker ended up in a second XMP block.
        val primaryXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="5"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val gainXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
    hdrgm:Version="1.0"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val jpeg = withSegment(
            withSegment(sceneJpeg(), 0xE1, xmpPayload(primaryXmp)),
            0xE1, xmpPayload(gainXmp)
        )
        assertTrue(UltraHdrContainer.inspect(jpeg) is Inspection.AlreadyHdr)
    }

    @Test
    fun otherFormatsAndBrokenFilesAreRejected() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertEquals(Blocker.NOT_JPEG, (UltraHdrContainer.inspect(png) as Inspection.Blocked).reason)

        val jpeg = sceneJpeg()
        val cutShort = jpeg.copyOfRange(0, jpeg.size - 200)
        assertEquals(Blocker.UNREADABLE, (UltraHdrContainer.inspect(cutShort) as Inspection.Blocked).reason)
    }

    @Test
    fun xmpThatIsNotSafeToEditBlocksTheFile() {
        val alreadyUsesThePrefix = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:Item="http://example.com/other/" Item:Thing="1"/>
 </rdf:RDF>
</x:xmpmeta>"""
        val a = UltraHdrContainer.inspect(withSegment(sceneJpeg(), 0xE1, xmpPayload(alreadyUsesThePrefix)))
        assertEquals(Blocker.UNUSUAL_METADATA, (a as Inspection.Blocked).reason)

        val broken = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description rdf:about=\"\">"
        val b = UltraHdrContainer.inspect(withSegment(sceneJpeg(), 0xE1, xmpPayload(broken)))
        assertEquals(Blocker.UNUSUAL_METADATA, (b as Inspection.Blocked).reason)
    }

    // ------------------------------------------------------------------------------------------
    // A real phone file
    // ------------------------------------------------------------------------------------------

    private fun realSample(): ByteArray {
        val path = System.getenv("UHDR_SAMPLE")
        assumeTrue("Set UHDR_SAMPLE to a camera Ultra HDR photo to run this test", !path.isNullOrBlank())
        return File(path!!).readBytes()
    }

    @Test
    fun readerUnderstandsARealCameraFile() {
        val sample = realSample()
        val found = TestGainMapReader.read(sample)
        println("REAL primaryEnd=${found.primaryEnd} gainMap=${found.gainMapStart}+${found.gainMapSize} " +
            "hdrgm=${found.hdrgm} isoLog2=${found.isoHeadroomLog2} isoOffsets=${found.isoOffsets}")
        assertEquals("In a camera file the gain map follows the photo directly", found.primaryEnd, found.gainMapStart)
        assertEquals(found.gainMapSize, found.containerGainMapSize)
        assertTrue(found.primaryHasIsoMarker)
        assertEquals(found.hdrgm.getValue("GainMapMax").toDouble(), found.isoHeadroomLog2!!, 1e-4)
        assertTrue(UltraHdrContainer.inspect(sample) is Inspection.AlreadyHdr)
    }

    @Test
    fun realCameraPhotoStrippedToSdrConvertsCleanly() {
        val sample = realSample()
        val found = TestGainMapReader.read(sample)

        // Rebuild what the photo would have been without HDR: drop the XMP, ISO and MPF blocks and
        // the gain map, keep everything else the camera wrote.
        val drop = TestGainMapReader.headerSegments(sample, 0).filter { seg ->
            val sig = String(sample, seg.payloadStart, minOf(seg.payloadLength, 30), Charsets.US_ASCII)
            (seg.marker == 0xE1 && sig.startsWith("http://ns.adobe.com/xap/1.0/")) ||
                (seg.marker == 0xE2 && (sig.startsWith("MPF") || sig.startsWith("urn:iso:std:iso:ts:21496")))
        }
        val sdr = ByteArrayOutputStream()
        var at = 0
        for (seg in drop) {
            sdr.write(sample, at, seg.start - at)
            at = seg.payloadStart + seg.payloadLength
        }
        sdr.write(sample, at, found.primaryEnd - at)
        val photoOnly = sdr.toByteArray()
        val cameraTrailer = sample.copyOfRange(found.gainMapStart + found.gainMapSize, sample.size)

        // With the camera's trailer (this sample is a motion photo) the file must be refused.
        if (String(cameraTrailer, Charsets.ISO_8859_1).contains("MotionPhoto_Data")) {
            val withVideo = UltraHdrContainer.inspect(photoOnly + cameraTrailer)
            assertEquals(Blocker.MOTION_PHOTO, (withVideo as Inspection.Blocked).reason)
        }

        // As a plain photo it converts, with the camera's EXIF, colour profile and pixels untouched.
        val result = convert(photoOnly)
        val out = assertReadableAndConsistent(result)
        println("REAL converted size=${result.bytes.size} (+${result.bytes.size - photoOnly.size}) " +
            "ratioMax=${result.ratioMax} map=${result.mapWidth}x${result.mapHeight} gainMapBytes=${out.gainMapSize}")
        assertSamePixels(photoOnly, result.bytes, sample = 4)

        val before = TestGainMapReader.headerSegments(photoOnly, 0).map { it.marker to it.payloadLength }
        val after = TestGainMapReader.headerSegments(result.bytes, 0).map { it.marker to it.payloadLength }
        assertTrue("Every block the camera wrote is still there, in order", after.containsAllInOrder(before))
        assertEquals(before.size + 3, after.size)
        assertFalse(abs(result.ratioMax - 1f) < 0.01f)
    }

    private fun <T> List<T>.containsAllInOrder(other: List<T>): Boolean {
        var i = 0
        for (item in this) if (i < other.size && item == other[i]) i++
        return i == other.size
    }
}
