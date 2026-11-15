package com.example.ultrahdr

import com.example.ultrahdr.processing.ImageExifHelper
import com.example.ultrahdr.processing.JpegMpfFixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale

class LogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** The search as it was written before, over text. The byte search must answer the same. */
    private fun labelsAsText(header: ByteArray, length: Int = header.size): Boolean {
        val size = minOf(length, header.size)
        val content = String(header, 0, size, Charsets.ISO_8859_1)
        val isHeif = size >= 12 && content.startsWith("ftyp", startIndex = 4)
        if (isHeif && (content.contains("tmap") || content.contains("hdrgainmap"))) return true
        return content.contains("urn:iso:std:iso:ts:21496:-1") ||
            content.contains("Item:Semantic=\"GainMap\"") ||
            content.contains("http://ns.google.com/photos/1.0/gainmap/") ||
            content.contains("xmlns:hdrgm=\"http://ns.google.com/photos/1.0/gainmap/\"") ||
            content.contains("http://ns.adobe.com/hdr-gain-map/1.0/") ||
            content.contains("hdrgm:Version") ||
            content.contains("hdrgm:GainMapMin") ||
            content.contains("hdrgm:GainMapMax")
    }

    @Test
    fun theByteSearchFindsExactlyWhatTheTextSearchFound() {
        val words = listOf(
            "urn:iso:std:iso:ts:21496:-1", "Item:Semantic=\"GainMap\"",
            "http://ns.google.com/photos/1.0/gainmap/", "xmlns:hdrgm=\"http://ns.google.com/photos/1.0/gainmap/\"",
            "http://ns.adobe.com/hdr-gain-map/1.0/", "hdrgm:Version", "hdrgm:GainMapMin", "hdrgm:GainMapMax",
            "tmap", "hdrgainmap",
            // Near misses: one letter short or different.
            "hdrgm:Versio", "hdrgm:GainMapMid", "urn:iso:std:iso:ts:21496:-2", "http://ns.google.com/photos/1.0/gainma"
        )
        val random = java.util.Random(7)
        var checked = 0
        for (heif in listOf(false, true)) for (word in words) for (at in listOf(0, 13, 500, 1000 - word.length, 990)) {
            val bytes = ByteArray(1000).also { random.nextBytes(it) }
            if (heif) "\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
            val start = maxOf(if (heif) 12 else 0, at)
            word.toByteArray(Charsets.ISO_8859_1).let { w -> w.copyInto(bytes, start, 0, minOf(w.size, bytes.size - start)) }
            // The whole array, a cut that ends inside the word, and one that ends right after it.
            for (length in listOf(bytes.size, start + word.length - 1, start + word.length)) {
                assertEquals("$word at $start, length $length", labelsAsText(bytes, length), ImageExifHelper.looksLikeUltraHdr(bytes, length))
                checked++
            }
        }
        assertTrue(checked > 400)
        assertFalse(ImageExifHelper.looksLikeUltraHdr(ByteArray(0)))
    }

    @Test
    fun testUltraHdrDetectionOnIso21496Marker() {
        val testFile = tempFolder.newFile("test_iso21496.jpg")
        FileOutputStream(testFile).use { out ->
            val payload = "Some mock JPEG bytes with urn:iso:std:iso:ts:21496:-1 gainmap metadata included"
            out.write(payload.toByteArray(Charsets.ISO_8859_1))
        }

        val isHdr = ImageExifHelper.isAlreadyUltraHdr(testFile)
        assertTrue("File with ISO 21496-1 marker must be recognized as Ultra HDR", isHdr)
    }

    @Test
    fun testUltraHdrDetectionOnGoogleContainerMarker() {
        val testFile = tempFolder.newFile("test_google_hdr.jpg")
        FileOutputStream(testFile).use { out ->
            val payload = "JPEG with http://ns.google.com/photos/1.0/container/ and Item:Semantic=\"GainMap\""
            out.write(payload.toByteArray(Charsets.ISO_8859_1))
        }

        val isHdr = ImageExifHelper.isAlreadyUltraHdr(testFile)
        assertTrue("File with Google container gainmap must be recognized as Ultra HDR", isHdr)
    }

    @Test
    fun testSamsungMotionPhotoNotFalselyDetectedAsUltraHdr() {
        val testFile = tempFolder.newFile("test_samsung_motion_photo.jpg")
        FileOutputStream(testFile).use { out ->
            val payload = "JPEG with http://ns.google.com/photos/1.0/container/ and Item:Semantic=\"MotionPhoto\" but no gain map"
            out.write(payload.toByteArray(Charsets.ISO_8859_1))
        }

        val isHdr = ImageExifHelper.isAlreadyUltraHdr(testFile)
        assertFalse("Samsung Motion Photo without gainmap must NOT be detected as Ultra HDR", isHdr)
    }

    @Test
    fun testUltraHdrDetectionOnRegularSdrJpeg() {
        val testFile = tempFolder.newFile("test_regular_sdr.jpg")
        FileOutputStream(testFile).use { out ->
            val payload = "Standard SDR photo without any HDR gain map metadata"
            out.write(payload.toByteArray(Charsets.ISO_8859_1))
        }

        val isHdr = ImageExifHelper.isAlreadyUltraHdr(testFile)
        assertFalse("Regular SDR photo must NOT be detected as Ultra HDR", isHdr)
    }

    @Test
    fun testWritableMetadataTagsCompleteness() {
        val tags = ImageExifHelper.WRITABLE_METADATA_TAGS
        assertTrue("Must support camera make", tags.contains("Make"))
        assertTrue("Must support camera model", tags.contains("Model"))
        assertTrue("Must support exposure time", tags.contains("ExposureTime"))
        assertTrue("Must support F-number", tags.contains("FNumber"))
        assertTrue("Must support ISO speed rating", tags.contains("PhotographicSensitivity"))
        assertTrue("Must support focal length", tags.contains("FocalLength"))
        assertTrue("Must support original datetime", tags.contains("DateTimeOriginal"))
        assertTrue("Must support GPS latitude", tags.contains("GPSLatitude"))
        assertTrue("Must support LensModel", tags.contains("LensModel"))
    }

    @Test
    fun testCaptureTimestampParsing() {
        val dateStr = "2024:11:10 14:30:00"
        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val expectedTime = format.parse(dateStr)?.time

        assertTrue(expectedTime != null && expectedTime > 0)
    }

    @Test
    fun testCloudPhotoDetection() {
        val googlePhotosUri = "content://com.google.android.apps.photos.contentprovider/-1/1/content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F123/ORIGINAL/NONE/image%2Fjpeg/123456789"
        val isCloud = googlePhotosUri.contains("com.google.android.apps.photos")
        assertTrue("Google Photos URI must be identified as cloud photo", isCloud)
    }

    @Test
    fun testMpfFixerRepairsPrimaryAndGainmapEntriesBigEndian() {
        val (jpegBytes, mpfTiffStart, gainmapStart, primaryEnd) =
            buildSyntheticMultiPictureJpeg(ByteOrder.BIG_ENDIAN, stalePrimarySize = 123, staleGmOffset = 456)

        val (fixedBytes, report) = JpegMpfFixer.fixMpfBytes(jpegBytes)

        assertTrue("MPF segment should be patched", report.patchedMpf)
        assertEquals(primaryEnd, report.primarySize)
        assertEquals(gainmapStart, report.gainmapStart)
        assertEquals(jpegBytes.size - gainmapStart, report.gainmapSize)

        val (entry0Size, entry0Offset, entry1Size, entry1Offset) =
            readMpfEntries(fixedBytes, mpfTiffStart, ByteOrder.BIG_ENDIAN)
        assertEquals(primaryEnd, entry0Size)
        assertEquals(0, entry0Offset)
        assertEquals(jpegBytes.size - gainmapStart, entry1Size)
        assertEquals(gainmapStart - mpfTiffStart, entry1Offset)
    }

    @Test
    fun testMpfFixerSupportsLittleEndianTiffHeader() {
        val (jpegBytes, mpfTiffStart, gainmapStart, primaryEnd) =
            buildSyntheticMultiPictureJpeg(ByteOrder.LITTLE_ENDIAN, stalePrimarySize = 999, staleGmOffset = 111)

        val (fixedBytes, report) = JpegMpfFixer.fixMpfBytes(jpegBytes)

        assertTrue(report.patchedMpf)
        val (entry0Size, entry0Offset, entry1Size, entry1Offset) =
            readMpfEntries(fixedBytes, mpfTiffStart, ByteOrder.LITTLE_ENDIAN)
        assertEquals(primaryEnd, entry0Size)
        assertEquals(0, entry0Offset)
        assertEquals(jpegBytes.size - gainmapStart, entry1Size)
        assertEquals(gainmapStart - mpfTiffStart, entry1Offset)
    }

    @Test
    fun testMpfFixerAfterApp1ExifInsertionAtOffset2() {
        val (initialBytes, _, initialGainmapStart, initialPrimaryEnd) =
            buildSyntheticMultiPictureJpeg(ByteOrder.BIG_ENDIAN)

        // Simulate ExifInterface.saveAttributes inserting an APP1 EXIF segment right after SOI (offset 2),
        // copying APP2 MPF and trailing GainMap verbatim without updating MPEntry sizes/offsets.
        val insertedExifPayload = ByteArray(140)
        "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII).copyInto(insertedExifPayload, 0)
        val segLen = insertedExifPayload.size + 2
        val baos = ByteArrayOutputStream()
        baos.write(initialBytes, 0, 2) // FF D8
        baos.write(0xFF)
        baos.write(0xE1)
        baos.write((segLen shr 8) and 0xFF)
        baos.write(segLen and 0xFF)
        baos.write(insertedExifPayload)
        baos.write(initialBytes, 2, initialBytes.size - 2)

        val jpegFile = tempFolder.newFile("ultrahdr_exif_test.jpg")
        jpegFile.writeBytes(baos.toByteArray())

        val insertedDelta = 4 + insertedExifPayload.size
        val shiftedMpfTiffStart = findMpfTiffStart(jpegFile.readBytes())
        val (staleEntry0Size, _, _, staleEntry1Offset) =
            readMpfEntries(jpegFile.readBytes(), shiftedMpfTiffStart, ByteOrder.BIG_ENDIAN)
        assertEquals(initialPrimaryEnd, staleEntry0Size)
        assertEquals(initialGainmapStart - (shiftedMpfTiffStart - insertedDelta), staleEntry1Offset)

        // Repair via JpegMpfFixer.fixMpfAndContainer(File)
        val report = JpegMpfFixer.fixMpfAndContainer(jpegFile)
        assertTrue(report.patchedMpf)

        val repairedBytes = jpegFile.readBytes()
        val (fixedEntry0Size, fixedEntry0Offset, fixedEntry1Size, fixedEntry1Offset) =
            readMpfEntries(repairedBytes, shiftedMpfTiffStart, ByteOrder.BIG_ENDIAN)

        assertEquals(initialPrimaryEnd + insertedDelta, fixedEntry0Size)
        assertEquals(0, fixedEntry0Offset)
        assertEquals(report.gainmapSize, fixedEntry1Size)
        assertEquals(fixedEntry0Size - shiftedMpfTiffStart, fixedEntry1Offset)

        // Verify GainMap JPEG SOI starts at the exact repaired MPF offset + TIFF start
        val gmAbsStart = shiftedMpfTiffStart + fixedEntry1Offset
        assertEquals(0xFF.toByte(), repairedBytes[gmAbsStart])
        assertEquals(0xD8.toByte(), repairedBytes[gmAbsStart + 1])
    }

    private data class SyntheticJpeg(
        val bytes: ByteArray,
        val mpfTiffStart: Int,
        val gainmapStart: Int,
        val primaryEnd: Int
    )

    private fun buildSyntheticMultiPictureJpeg(
        order: ByteOrder,
        stalePrimarySize: Int? = null,
        staleGmOffset: Int? = null
    ): SyntheticJpeg {
        val baos = ByteArrayOutputStream()
        // SOI
        baos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

        // APP2 MPF segment: 4 bytes ("MPF\0") + 8 bytes TIFF header + 26 bytes IFD (2 entries) + 32 bytes MPEntry[2]
        // Total payload = 70 bytes -> segment length = 72 bytes
        val mpfPayloadLen = 4 + 8 + (2 + 2 * 12 + 4) + 32
        val mpfHeaderPos = baos.size() + 4 + 4 // start of TIFF header inside APP2
        val mpfPayload = ByteArray(mpfPayloadLen)
        mpfPayload[0] = 'M'.code.toByte()
        mpfPayload[1] = 'P'.code.toByte()
        mpfPayload[2] = 'F'.code.toByte()
        mpfPayload[3] = 0

        val bb = ByteBuffer.wrap(mpfPayload, 4, mpfPayloadLen - 4).slice().order(order)
        if (order == ByteOrder.BIG_ENDIAN) {
            bb.put(0, 'M'.code.toByte()); bb.put(1, 'M'.code.toByte())
            bb.putShort(2, 0x002A)
        } else {
            bb.put(0, 'I'.code.toByte()); bb.put(1, 'I'.code.toByte())
            bb.putShort(2, 0x002A)
        }
        bb.putInt(4, 8) // IFD0 offset
        val ifdStart = 8
        bb.putShort(ifdStart, 2) // 2 entries
        // Entry 0: NumberOfImages (0xB001) = 2
        bb.putShort(ifdStart + 2, 0xB001.toShort())
        bb.putShort(ifdStart + 4, 4)
        bb.putInt(ifdStart + 6, 1)
        bb.putInt(ifdStart + 10, 2)
        // Entry 1: MPEntry (0xB002), 32 bytes at offset 38
        val mpEntriesOffset = ifdStart + 2 + 2 * 12 + 4
        bb.putShort(ifdStart + 14, 0xB002.toShort())
        bb.putShort(ifdStart + 16, 7) // UNDEFINED
        bb.putInt(ifdStart + 18, 32)
        bb.putInt(ifdStart + 22, mpEntriesOffset)
        bb.putInt(ifdStart + 26, 0) // next IFD = 0

        baos.write(0xFF)
        baos.write(0xE2)
        val segLen = mpfPayloadLen + 2
        baos.write((segLen shr 8) and 0xFF)
        baos.write(segLen and 0xFF)
        baos.write(mpfPayload)

        // Primary SOS segment + entropy bytes containing stuffed FF 00 and RST0 (FF D0) + EOI
        baos.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x04, 0x01, 0x00))
        baos.write(byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0xD0.toByte(), 0x34))
        baos.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val primaryEnd = baos.size()

        // Secondary GainMap JPEG
        val gainmapStart = baos.size()
        baos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        baos.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x04, 0x01, 0x00))
        baos.write(byteArrayOf(0xAA.toByte(), 0xFF.toByte(), 0x00, 0xBB.toByte()))
        baos.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val bytes = baos.toByteArray()

        // Populate initial (optionally stale) MPEntry values
        val gmSize = bytes.size - gainmapStart
        val tiffBb = ByteBuffer.wrap(bytes, mpfHeaderPos, mpfPayloadLen - 4).slice().order(order)
        tiffBb.putInt(mpEntriesOffset + 4, stalePrimarySize ?: primaryEnd)
        tiffBb.putInt(mpEntriesOffset + 8, 0)
        tiffBb.putInt(mpEntriesOffset + 16 + 4, gmSize)
        tiffBb.putInt(mpEntriesOffset + 16 + 8, staleGmOffset ?: (gainmapStart - mpfHeaderPos))

        return SyntheticJpeg(bytes, mpfHeaderPos, gainmapStart, primaryEnd)
    }

    private fun findMpfTiffStart(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 8) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xE2.toByte() &&
                bytes[i + 4] == 'M'.code.toByte() && bytes[i + 5] == 'P'.code.toByte() &&
                bytes[i + 6] == 'F'.code.toByte() && bytes[i + 7] == 0.toByte()
            ) {
                return i + 8
            }
        }
        error("MPF segment not found")
    }

    private fun readMpfEntries(bytes: ByteArray, mpfTiffStart: Int, order: ByteOrder): IntArray {
        val bb = ByteBuffer.wrap(bytes, mpfTiffStart, bytes.size - mpfTiffStart).slice().order(order)
        val ifdOffset = bb.getInt(4)
        val count = bb.getShort(ifdOffset).toInt() and 0xFFFF
        for (i in 0 until count) {
            val rel = ifdOffset + 2 + i * 12
            if ((bb.getShort(rel).toInt() and 0xFFFF) == 0xB002) {
                val off = bb.getInt(rel + 8)
                return intArrayOf(
                    bb.getInt(off + 4),
                    bb.getInt(off + 8),
                    bb.getInt(off + 16 + 4),
                    bb.getInt(off + 16 + 8)
                )
            }
        }
        error("MPEntry tag 0xB002 not found")
    }
}
